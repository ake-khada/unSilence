package com.unsilence.app.data.repository

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.SnapshotScheduler
import com.unsilence.app.data.memory.tagsToJson
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.relay.buildFollowContactTags
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.relay.toEventJson
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FollowBatchPublisher"

@Singleton
class FollowBatchPublisher @Inject constructor(
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val relayPreferencesStore: RelayPreferencesStore,
    private val snapshotScheduler: SnapshotScheduler,
) {
    private val coordinator = FollowPublishCoordinator(
        loadSnapshot = memoryEventStore::getPublishableFollowsSnapshot,
        sign = ::signFollowList,
        applyOptimistic = memoryEventStore::applyOptimisticFollows,
        revertOptimistic = memoryEventStore::revertOptimisticFollows,
        publishAndAwait = ::publishAndAwait,
        persistAccepted = ::persistAccepted,
        nowSeconds = { System.currentTimeMillis() / 1_000L },
    )

    /** Adds an onboarding selection without ever treating unresolved follows as empty. */
    internal suspend fun addFollows(selectedPubkeys: Collection<String>): FollowPublishResult {
        val ownPubkey = keyManager.getPublicKeyHex()
            ?: return FollowPublishResult.AccountUnavailable
        return refreshThenPublish(ownPubkey) { existing ->
            buildFollowContactTags(existing, selectedPubkeys)
                .mapTo(linkedSetOf()) { it[1] }
        }.also(::logResult)
    }

    /** Toggles one profile through the same guarded kind-3 transaction. */
    internal suspend fun toggleFollow(targetPubkey: String): FollowPublishResult {
        val ownPubkey = keyManager.getPublicKeyHex()
            ?: return FollowPublishResult.AccountUnavailable
        return refreshThenPublish(ownPubkey) { existing ->
            if (targetPubkey in existing) existing - targetPubkey else existing + targetPubkey
        }.also(::logResult)
    }

    private suspend fun refreshThenPublish(
        ownPubkey: String,
        mutate: (Set<String>) -> Set<String>,
    ): FollowPublishResult {
        val local = memoryEventStore.getFollowsSnapshot(ownPubkey)
        // A locally known-empty account has no metadata to preserve. Every
        // unresolved or previously published list is refreshed before mutation
        // so another client cannot be clobbered merely because its newer event
        // had not reached MES yet.
        val knownEmptyNewAccount = local != null &&
            local.follows.isEmpty() &&
            local.retainedContactList == null
        if (!knownEmptyNewAccount) {
            // Publishing a replaceable contact list is destructive by nature;
            // never reuse the UI freshness window for this preflight.
            if (!relayPool.refreshFollowList(ownPubkey, forceRefresh = true)) {
                return FollowPublishResult.FollowsUnavailable
            }
        }
        return coordinator.publishMutation(ownPubkey, mutate)
    }

    private suspend fun signFollowList(
        createdAt: Long,
        follows: Set<String>,
        retainedContactList: NostrEvent?,
    ): SignedFollowList? {
        val draft = mergeFollowListDraft(
            authoritativeFollows = follows,
            retained = retainedContactList?.let { event ->
                RetainedFollowList(tags = event.tags, content = event.content)
            },
        )
        val template = EventTemplate<Event>(
            createdAt = createdAt,
            kind = 3,
            tags = draft.tags.map { it.toTypedArray() }.toTypedArray(),
            content = draft.content,
        )
        val signed = signingManager.sign(template) ?: return null
        val signedTags = signed.tags.map { it.toList() }
        val localEvent = NostrEvent(
            id = signed.id,
            pubkey = signed.pubKey,
            kind = 3,
            content = signed.content,
            createdAt = signed.createdAt,
            tags = signedTags,
            tagsJson = tagsToJson(signedTags),
            sig = signed.sig,
            relayUrl = "",
            replyToId = null,
            rootId = null,
            hasContentWarning = false,
            contentWarningReason = null,
            firstSeenAt = System.currentTimeMillis(),
            relaysSeen = ConcurrentHashMap.newKeySet(),
        )
        return SignedFollowList(
            eventId = signed.id,
            eventJson = toEventJson(signed),
            event = localEvent,
        )
    }

    /** Relay acceptance is the commit point: retain first, then persist it. */
    private fun persistAccepted(signed: SignedFollowList) {
        if (!memoryEventStore.retainAcceptedOwnContactList(signed.event)) {
            Log.w(TAG, "FOLLOW-PUBLISH accepted metadata superseded event=${signed.eventId.take(8)}")
        }
        snapshotScheduler.scheduleImmediate()
    }

    private suspend fun publishAndAwait(
        ownPubkey: String,
        signed: SignedFollowList,
    ): Boolean {
        val ownWriteRelays = memoryEventStore.writeRelaysFor(ownPubkey)
        val targets = (
            ownWriteRelays.ifEmpty { GLOBAL_RELAY_URLS } +
                relayPreferencesStore.indexerRelayUrlsSnapshot()
        ).mapNotNull(::normalizeRelayUrl).toSet()

        return awaitRelayAcceptance(
            targetRelays = targets,
            timeoutMs = FOLLOW_PUBLISH_TIMEOUT_MS,
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

    private fun logResult(result: FollowPublishResult) {
        when (result) {
            is FollowPublishResult.Success ->
                Log.i(TAG, "FOLLOW-PUBLISH accepted follows=${result.follows.size}")
            FollowPublishResult.AccountUnavailable ->
                Log.w(TAG, "FOLLOW-PUBLISH blocked reason=account-unavailable")
            FollowPublishResult.FollowsUnavailable ->
                Log.w(TAG, "FOLLOW-PUBLISH blocked reason=follows-unresolved")
            FollowPublishResult.SigningFailed ->
                Log.w(TAG, "FOLLOW-PUBLISH blocked reason=signing-failed")
            FollowPublishResult.ChangedWhileSigning ->
                Log.w(TAG, "FOLLOW-PUBLISH blocked reason=follows-changed-during-signing")
            is FollowPublishResult.NoRelayAccepted ->
                Log.w(TAG, "FOLLOW-PUBLISH rejected accepted=0 rollback=${result.rollbackRestored}")
        }
    }

    private companion object {
        const val FOLLOW_PUBLISH_TIMEOUT_MS = 6_000L
    }
}
