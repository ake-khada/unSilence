package com.unsilence.app.data.repository

import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.ProfileMetadataRefreshResult
import kotlinx.coroutines.CancellationException

internal data class SignedProfileMetadata(
    val eventId: String,
    val eventJson: String,
    val event: NostrEvent,
)

internal sealed interface ProfilePublishResult {
    data object Success : ProfilePublishResult
    data object AccountUnavailable : ProfilePublishResult
    data object FreshnessUnavailable : ProfilePublishResult
    data object ProfileUnavailable : ProfilePublishResult
    data object InvalidExistingProfile : ProfilePublishResult
    data object SigningFailed : ProfilePublishResult
    data object ChangedWhileSigning : ProfilePublishResult
    data object NoRelayAccepted : ProfilePublishResult
    data object SupersededAfterAcceptance : ProfilePublishResult
}

/**
 * Safety-critical kind-0 transaction, kept free of Android and Quartz so the
 * unresolved, signing-race, acknowledgement, and persistence edges are all
 * executable in local JVM tests.
 */
internal class ProfilePublishCoordinator(
    private val refreshProfile: suspend (String) -> ProfileMetadataRefreshResult,
    private val loadProfile: (String) -> NostrEvent?,
    private val sign: suspend (createdAt: Long, content: String) -> SignedProfileMetadata?,
    private val publishAndAwait: suspend (pubkey: String, signed: SignedProfileMetadata) -> Boolean,
    private val persistAccepted: (pubkey: String, signed: SignedProfileMetadata) -> Boolean,
    private val nowSeconds: () -> Long,
) {
    suspend fun publish(
        pubkey: String,
        original: EditableProfileMetadata,
        edited: EditableProfileMetadata,
    ): ProfilePublishResult {
        val refreshResult = try {
            refreshProfile(pubkey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ProfileMetadataRefreshResult.UNAVAILABLE
        }
        val existing = loadProfile(pubkey)
        when (refreshResult) {
            ProfileMetadataRefreshResult.SETTLED -> {
                if (existing == null) return ProfilePublishResult.ProfileUnavailable
            }
            ProfileMetadataRefreshResult.CONFIRMED_ABSENT -> {
                // A real empty relay response unlocks a first publish only. If
                // MES still has a profile, its relationship to those empty
                // relays is unresolved and rebuilding from nothing is unsafe.
                if (existing != null) return ProfilePublishResult.FreshnessUnavailable
            }
            ProfileMetadataRefreshResult.UNAVAILABLE ->
                return ProfilePublishResult.FreshnessUnavailable
        }

        val mergedContent = mergeProfileMetadata(existing?.content ?: "{}", edited, original)
            ?: return ProfilePublishResult.InvalidExistingProfile
        val createdAt = existing?.let { maxOf(nowSeconds(), it.createdAt + 1L) } ?: nowSeconds()
        val mergeSourceId = existing?.id

        val signed = try {
            sign(createdAt, mergedContent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return ProfilePublishResult.SigningFailed

        // Amber can suspend for seconds. Never publish a draft merged from a
        // profile that another client replaced while the signer was open.
        if (loadProfile(pubkey)?.id != mergeSourceId) {
            return ProfilePublishResult.ChangedWhileSigning
        }

        val accepted = try {
            publishAndAwait(pubkey, signed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!accepted) return ProfilePublishResult.NoRelayAccepted

        return if (persistAccepted(pubkey, signed)) {
            ProfilePublishResult.Success
        } else {
            ProfilePublishResult.SupersededAfterAcceptance
        }
    }
}
