package com.unsilence.app.data.repository

import com.unsilence.app.data.memory.FollowsSnapshot
import com.unsilence.app.data.memory.NostrEvent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal data class SignedFollowList(
    val eventId: String,
    val eventJson: String,
    val event: NostrEvent,
)

internal sealed interface FollowPublishResult {
    data class Success(val follows: Set<String>) : FollowPublishResult
    data object AccountUnavailable : FollowPublishResult
    data object FollowsUnavailable : FollowPublishResult
    data object SigningFailed : FollowPublishResult
    data object ChangedWhileSigning : FollowPublishResult
    data class NoRelayAccepted(val rollbackRestored: Boolean) : FollowPublishResult
}

/**
 * Owns the safety-critical kind-3 state transition without depending on Android or Quartz.
 * The production adapter supplies signing, relay publishing, and MES compare-and-set hooks;
 * keeping the transaction pure here makes every destructive edge executable in JVM tests.
 */
internal class FollowPublishCoordinator(
    private val loadSnapshot: (String) -> FollowsSnapshot?,
    private val sign: suspend (
        createdAt: Long,
        follows: Set<String>,
        retainedContactList: NostrEvent?,
    ) -> SignedFollowList?,
    private val applyOptimistic: (
        pubkey: String,
        previous: FollowsSnapshot,
        updatedFollows: Set<String>,
        updatedCreatedAt: Long,
    ) -> Boolean,
    private val revertOptimistic: (
        pubkey: String,
        optimisticFollows: Set<String>,
        optimisticCreatedAt: Long,
        previous: FollowsSnapshot,
    ) -> Boolean,
    private val publishAndAwait: suspend (pubkey: String, signed: SignedFollowList) -> Boolean,
    private val persistAccepted: (SignedFollowList) -> Unit,
    private val nowSeconds: () -> Long,
) {
    suspend fun publishMutation(
        pubkey: String,
        mutate: (Set<String>) -> Set<String>,
    ): FollowPublishResult {
        val previous = loadSnapshot(pubkey) ?: return FollowPublishResult.FollowsUnavailable
        val updated = mutate(previous.follows).toSet()
        if (updated == previous.follows) return FollowPublishResult.Success(previous.follows)

        val nextCreatedAt = maxOf(
            nowSeconds(),
            (previous.createdAt ?: 0L) + 1L,
        )
        val signed = try {
            sign(nextCreatedAt, updated, previous.retainedContactList)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return FollowPublishResult.SigningFailed

        // Signing can suspend for an external Amber round-trip. Apply only if the
        // exact set and version captured before signing are still current. MES does
        // the comparison and write atomically, closing the re-read/write race too.
        val applied = applyOptimistic(pubkey, previous, updated, nextCreatedAt)
        if (!applied) return FollowPublishResult.ChangedWhileSigning

        val accepted = try {
            publishAndAwait(pubkey, signed)
        } catch (cancelled: CancellationException) {
            revertOptimistic(pubkey, updated, nextCreatedAt, previous)
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (accepted) {
            // The relay ACK makes this the committed local state. Request a
            // near-immediate snapshot before reporting success so a subsequent
            // force-stop cannot restore the pre-publish follow list.
            persistAccepted(signed)
            return FollowPublishResult.Success(updated)
        }

        return FollowPublishResult.NoRelayAccepted(
            rollbackRestored = revertOptimistic(pubkey, updated, nextCreatedAt, previous),
        )
    }
}

/**
 * Registers before dispatch, accepts only callbacks from the intended targets, and
 * completes on the first OK or once every target has explicitly rejected the event.
 */
internal suspend fun awaitRelayAcceptance(
    targetRelays: Set<String>,
    timeoutMs: Long,
    register: (callback: (relayUrl: String, accepted: Boolean, message: String) -> Unit) -> Unit,
    unregister: () -> Unit,
    dispatch: suspend () -> Unit,
): Boolean {
    if (targetRelays.isEmpty()) return false
    val pending = ConcurrentHashMap.newKeySet<String>().apply { addAll(targetRelays) }
    val verdict = CompletableDeferred<Boolean>()

    register callback@{ relayUrl, accepted, _ ->
        if (verdict.isCompleted || !pending.remove(relayUrl)) return@callback
        if (accepted) {
            verdict.complete(true)
        } else if (pending.isEmpty()) verdict.complete(false)
    }

    return try {
        dispatch()
        withTimeoutOrNull(timeoutMs) { verdict.await() } ?: false
    } finally {
        unregister()
    }
}
