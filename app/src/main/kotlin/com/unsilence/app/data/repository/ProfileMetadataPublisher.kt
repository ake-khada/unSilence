package com.unsilence.app.data.repository

import android.util.Log
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.SnapshotScheduler
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.relay.toEventJson
import com.unsilence.app.data.relay.toNostrEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileMetadataPublisher"

@Singleton
class ProfileMetadataPublisher @Inject constructor(
    signingManager: SigningManager,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val relayPreferencesStore: RelayPreferencesStore,
    private val snapshotScheduler: SnapshotScheduler,
) {
    private val coordinator = ProfilePublishCoordinator(
        refreshProfile = relayPool::refreshProfileMetadata,
        loadProfile = memoryEventStore::getProfile,
        sign = signingManager::signProfileMetadata,
        publishAndAwait = ::publishAndAwait,
        persistAccepted = ::persistAccepted,
        nowSeconds = { System.currentTimeMillis() / 1_000L },
    )

    internal suspend fun publish(
        pubkey: String,
        original: EditableProfileMetadata,
        edited: EditableProfileMetadata,
    ): ProfilePublishResult = coordinator.publish(pubkey, original, edited).also(::logResult)

    private suspend fun publishAndAwait(
        pubkey: String,
        signed: SignedProfileMetadata,
    ): Boolean {
        val targets = publishTargetsWithGlobalFallback(
            writeRelays = memoryEventStore.writeRelaysFor(pubkey),
            additionalRelays = relayPreferencesStore.indexerRelayUrlsSnapshot(),
        )
        return awaitRelayAcceptance(
            targetRelays = targets,
            timeoutMs = PROFILE_PUBLISH_TIMEOUT_MS,
            register = { callback ->
                relayPool.registerPublishCallback(signed.eventId) { relayUrl, accepted, message ->
                    normalizeRelayUrl(relayUrl)?.let { callback(it, accepted, message) }
                }
            },
            unregister = { relayPool.unregisterPublishCallback(signed.eventId) },
            dispatch = {
                withContext(Dispatchers.IO) {
                    relayPool.publish(signed.eventJson, targets.toList())
                }
            },
        )
    }

    /** A relay acknowledgement is the commit point; persist only afterward. */
    private fun persistAccepted(
        pubkey: String,
        signed: SignedProfileMetadata,
    ): Boolean {
        memoryEventStore.insert(signed.event)
        val retained = memoryEventStore.getProfile(pubkey)?.id == signed.eventId
        if (retained) snapshotScheduler.scheduleImmediate()
        return retained
    }

    private fun logResult(result: ProfilePublishResult) {
        when (result) {
            ProfilePublishResult.Success -> Log.i(TAG, "PROFILE-PUBLISH accepted")
            ProfilePublishResult.AccountUnavailable -> Log.w(TAG, "PROFILE-PUBLISH blocked reason=account-unavailable")
            ProfilePublishResult.FreshnessUnavailable -> Log.w(TAG, "PROFILE-PUBLISH blocked reason=freshness-unavailable")
            ProfilePublishResult.ProfileUnavailable -> Log.w(TAG, "PROFILE-PUBLISH blocked reason=profile-unresolved")
            ProfilePublishResult.InvalidExistingProfile -> Log.w(TAG, "PROFILE-PUBLISH blocked reason=invalid-existing-profile")
            ProfilePublishResult.SigningFailed -> Log.w(TAG, "PROFILE-PUBLISH blocked reason=signing-failed")
            ProfilePublishResult.ChangedWhileSigning -> Log.w(TAG, "PROFILE-PUBLISH blocked reason=profile-changed-during-signing")
            ProfilePublishResult.NoRelayAccepted -> Log.w(TAG, "PROFILE-PUBLISH rejected accepted=0")
            ProfilePublishResult.SupersededAfterAcceptance -> Log.w(TAG, "PROFILE-PUBLISH accepted but superseded locally")
        }
    }

    private companion object {
        const val PROFILE_PUBLISH_TIMEOUT_MS = 6_000L
    }
}

internal suspend fun SigningManager.signProfileMetadata(
    pubkey: String,
    createdAt: Long,
    metadata: MergedProfileMetadata,
): SignedProfileMetadata? {
    val template = EventTemplate<Event>(
        createdAt = createdAt,
        kind = 0,
        tags = metadata.tags.map { it.toTypedArray() }.toTypedArray(),
        content = metadata.content,
    )
    val signed = sign(template, expectedPubkey = pubkey) ?: return null
    val localEvent = EventDto(
        id = signed.id,
        pubkey = signed.pubKey,
        kind = signed.kind,
        content = signed.content,
        createdAt = signed.createdAt,
        tags = signed.tags.map { it.toList() },
        sig = signed.sig,
    ).toNostrEvent("local")
    return SignedProfileMetadata(
        eventId = signed.id,
        eventJson = toEventJson(signed),
        event = localEvent,
    )
}
