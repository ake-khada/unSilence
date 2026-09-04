package com.unsilence.app.data.repository

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.MutePublishSnapshot
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.PendingMuteJournalStore
import com.unsilence.app.data.memory.SnapshotScheduler
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.relay.toEventJson
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

private const val TAG = "MuteListRepository"
private const val COALESCE_WINDOW_MS = 500L
private const val MUTE_PUBLISH_TIMEOUT_MS = 6_000L
private const val MAX_AUTO_RETRIES = 3

@Singleton
class MuteListRepository @Inject constructor(
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val snapshotScheduler: SnapshotScheduler,
    private val pendingMuteJournalStore: PendingMuteJournalStore,
) {
    /** Process-lifetime worker; a conflated channel coalesces rapid mute edits. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val publishRequests = Channel<Unit>(Channel.CONFLATED)
    private val retryLock = Any()
    private var retryJob: Job? = null
    private var retryAttempt = 0

    private val selfPublishedEvents = SelfPublishedEventTracker()

    /** Publishing requires both a verified relay event and completed snapshot restore. */
    private val _publishSafe = MutableStateFlow(false)
    val publishSafe: StateFlow<Boolean> = _publishSafe.asStateFlow()
    private val _syncState = MutableStateFlow(MuteSyncState.Preparing)
    val syncState: StateFlow<MuteSyncState> = _syncState.asStateFlow()
    @Volatile private var snapshotReady = false

    private val mutations = MuteMutationCoordinator(
        record = memoryEventStore::recordPendingMuteMutation,
        persist = { pending -> pendingMuteJournalStore.persist(pending) },
        isPublishSafe = { _publishSafe.value && snapshotReady },
        requestPublish = { requestPublish(resetRetries = true) },
    )

    private val publisher = MutePublishCoordinator(
        loadSnapshot = memoryEventStore::getMutePublishSnapshot,
        sign = ::signMuteList,
        beginPublish = memoryEventStore::beginMutePublish,
        rememberSelfPublished = selfPublishedEvents::add,
        publishAndAwait = ::publishAndAwait,
        commitAccepted = { snapshot, event ->
            val committed = memoryEventStore.commitAcceptedMutePublish(snapshot, event)
            if (committed && !pendingMuteJournalStore.clearIfMatches(snapshot.pending)) {
                // The relay event is already durable remotely and remains in MES.
                // A stale local journal can only cause an idempotent retry after
                // restart; never roll back an accepted publish here.
                Log.e(TAG, "MUTE-PUBLISH accepted but local journal clear failed")
            }
            committed
        },
        requestRetry = ::requestRetry,
        nowSeconds = { System.currentTimeMillis() / 1_000L },
    )

    init {
        scope.launch {
            for (ignored in publishRequests) {
                delay(COALESCE_WINDOW_MS)
                while (publishRequests.tryReceive().isSuccess) {
                    // Drain a rapid burst; the journal already contains the final state.
                }
                if (!_publishSafe.value || !snapshotReady) continue
                val ownPubkey = keyManager.getPublicKeyHex() ?: continue
                val result = publisher.publishPending(ownPubkey)
                handlePublishResult(result)
            }
        }
        scope.launch {
            relayPool.onRelayReconnected.collectLatest {
                // A reconnect burst represents one connectivity recovery episode.
                // Coalesce it, then resume a durable pending edit without polling.
                delay(1_000L)
                val ownPubkey = keyManager.getPublicKeyHex() ?: return@collectLatest
                if (_publishSafe.value && snapshotReady &&
                    memoryEventStore.getPendingMutePublish(ownPubkey) != null
                ) {
                    Log.i(TAG, "MUTE-PUBLISH retrying pending edit after relay recovery")
                    requestPublish(resetRetries = true)
                }
            }
        }
    }

    /** Called immediately before a new account snapshot restore begins. */
    fun markSnapshotPending() {
        snapshotReady = false
    }

    /**
     * Called only after restore returns. This is the second half of the publish
     * fence and guarantees a disk journal cannot appear after we clear it on ACK.
     */
    fun markSnapshotReady() {
        snapshotReady = true
        val ownPubkey = keyManager.getPublicKeyHex() ?: return
        memoryEventStore.getPendingMutePublish(ownPubkey)?.let { pending ->
            if (!pendingMuteJournalStore.persist(pending)) {
                Log.e(TAG, "MUTE-PUBLISH could not mirror restored journal")
            }
            if (_publishSafe.value) requestPublish(resetRetries = true)
        }
    }

    /** Restore the small encrypted journal before the large MES snapshot starts. */
    fun restoreDurablePending(ownerPubkey: String) {
        pendingMuteJournalStore.load(ownerPubkey)?.let { pending ->
            memoryEventStore.restorePendingMutePublishesAfterReset(listOf(pending))
        }
    }

    /**
     * Open the gate only for the exact kind-10000 whose contents were verified.
     * A newer event arriving during an Amber round-trip leaves the gate closed.
     */
    fun markPublishSafe(
        reason: String = "verified",
        expectedEventId: String? = null,
        expectNoCurrentEvent: Boolean = false,
    ): Boolean {
        val ownPubkey = keyManager.getPublicKeyHex() ?: return false
        val opened = memoryEventStore.inspectMuteListBaseAtomically(ownPubkey) { currentEventId ->
            if (!muteBaseMatchesExpectation(
                    currentEventId = currentEventId,
                    expectedEventId = expectedEventId,
                    expectNoCurrentEvent = expectNoCurrentEvent,
                )
            ) {
                false
            } else {
                _publishSafe.value = true
                _syncState.value = MuteSyncState.Ready
                true
            }
        }
        if (!opened) {
            Log.w(TAG, "MUTE-PUBLISH safe rejected: event superseded reason=$reason")
            return false
        }
        Log.i(TAG, "MUTE-PUBLISH safe: $reason")
        if (snapshotReady && memoryEventStore.getPendingMutePublish(ownPubkey) != null) {
            requestPublish(resetRetries = true)
        }
        return true
    }

    fun markPublishUnsafe(
        reason: String,
        state: MuteSyncState = MuteSyncState.Preparing,
    ) {
        _publishSafe.value = false
        _syncState.value = state
        Log.i(TAG, "MUTE-PUBLISH unsafe: $reason")
    }

    /** Called from MES before handling a relay echo. Multiple IDs stay recognized. */
    fun isSelfPublished(eventId: String): Boolean = selfPublishedEvents.contains(eventId)

    fun muteUser(targetPubkey: String): MuteResult = mutations.muteUser(targetPubkey)
    fun unmuteUser(targetPubkey: String): MuteResult = mutations.unmuteUser(targetPubkey)
    fun muteWord(word: String): MuteResult = mutations.muteWord(word)
    fun unmuteWord(word: String): MuteResult = mutations.unmuteWord(word)
    fun muteHashtag(tag: String): MuteResult = mutations.muteHashtag(tag)
    fun unmuteHashtag(tag: String): MuteResult = mutations.unmuteHashtag(tag)

    private fun requestPublish(resetRetries: Boolean) {
        if (resetRetries) {
            synchronized(retryLock) {
                retryAttempt = 0
                retryJob?.cancel()
                retryJob = null
            }
        }
        publishRequests.trySend(Unit)
    }

    /** Bounded retries prevent an offline pending mute from creating a radio loop. */
    private fun requestRetry() {
        val job = synchronized(retryLock) {
            if (retryAttempt >= MAX_AUTO_RETRIES) return
            val delayMs = listOf(2_000L, 5_000L, 15_000L)[retryAttempt++]
            retryJob?.cancel()
            scope.launch(start = CoroutineStart.LAZY) {
                delay(delayMs)
                publishRequests.trySend(Unit)
            }.also { retryJob = it }
        }
        job.start()
    }

    private suspend fun signMuteList(
        snapshot: MutePublishSnapshot,
        createdAt: Long,
    ): SignedMuteList? {
        val ownPubkey = snapshot.pending.ownerPubkey
        val muteList = snapshot.muteList

        val publicTags = mutableListOf<Array<String>>()
        muteList.pubkeys.forEach { publicTags.add(arrayOf("p", it)) }
        muteList.hashtags.forEach { publicTags.add(arrayOf("t", it)) }
        muteList.words.forEach { publicTags.add(arrayOf("word", it)) }
        muteList.eventIds.forEach { publicTags.add(arrayOf("e", it)) }

        val encryptedContent = signingManager.encrypt(buildPrivateTagsJson(muteList), ownPubkey)
            ?: return null
        if (!_publishSafe.value || !snapshotReady) return null

        val template = EventTemplate<Event>(
            createdAt = createdAt,
            kind = 10000,
            tags = publicTags.toTypedArray(),
            content = encryptedContent,
        )
        val signed = signingManager.sign(template) ?: return null
        if (!_publishSafe.value || !snapshotReady) return null

        val tags = signed.tags.map { it.toList() }
        val localEvent = NostrEvent(
            id = signed.id,
            pubkey = signed.pubKey,
            kind = 10000,
            content = signed.content,
            createdAt = signed.createdAt,
            tags = tags,
            sig = signed.sig,
            relayUrl = "",
            replyToId = null,
            rootId = null,
            hasContentWarning = false,
            contentWarningReason = null,
            firstSeenAt = System.currentTimeMillis(),
            relaysSeen = ConcurrentHashMap.newKeySet(),
        )
        return SignedMuteList(
            eventId = signed.id,
            eventJson = toEventJson(signed),
            event = localEvent,
        )
    }

    private suspend fun publishAndAwait(signed: SignedMuteList): Boolean {
        if (!_publishSafe.value || !snapshotReady) return false
        val ownPubkey = signed.event.pubkey
        val configuredTargets = memoryEventStore.writeRelaysFor(ownPubkey)
            .mapNotNull(::normalizeRelayUrl)
        val targets = configuredTargets
            .ifEmpty { GLOBAL_RELAY_URLS.mapNotNull(::normalizeRelayUrl) }
            .toSet()

        return awaitRelayAcceptance(
            targetRelays = targets,
            timeoutMs = MUTE_PUBLISH_TIMEOUT_MS,
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

    private fun handlePublishResult(result: MutePublishResult) {
        when (result) {
            is MutePublishResult.Success -> {
                synchronized(retryLock) {
                    retryAttempt = 0
                    retryJob?.cancel()
                    retryJob = null
                }
                snapshotScheduler.scheduleImmediate()
                Log.i(
                    TAG,
                    "MUTE-PUBLISH accepted revision=${result.revision} createdAt=${result.createdAt}",
                )
            }
            MutePublishResult.NoPending -> Unit
            MutePublishResult.SigningFailed -> {
                Log.w(TAG, "MUTE-PUBLISH pending: signing/encryption failed")
                requestRetry()
            }
            MutePublishResult.ChangedWhileSigning ->
                Log.i(TAG, "MUTE-PUBLISH retry: state changed while signing")
            MutePublishResult.NoRelayAccepted ->
                Log.w(TAG, "MUTE-PUBLISH pending: accepted=0")
            MutePublishResult.SupersededAfterAcceptance ->
                Log.i(TAG, "MUTE-PUBLISH retry: accepted event was superseded locally")
        }
    }

    private fun buildPrivateTagsJson(muteList: MuteList): String =
        buildJsonArray {
            muteList.privatePubkeys.forEach {
                add(buildJsonArray { add(JsonPrimitive("p")); add(JsonPrimitive(it)) })
            }
            muteList.privateHashtags.forEach {
                add(buildJsonArray { add(JsonPrimitive("t")); add(JsonPrimitive(it)) })
            }
            muteList.privateWords.forEach {
                add(buildJsonArray { add(JsonPrimitive("word")); add(JsonPrimitive(it)) })
            }
            muteList.privateEventIds.forEach {
                add(buildJsonArray { add(JsonPrimitive("e")); add(JsonPrimitive(it)) })
            }
        }.toString()
}

/** CAS predicate for opening the publish gate from an event or confirmed-empty base. */
internal fun muteBaseMatchesExpectation(
    currentEventId: String?,
    expectedEventId: String?,
    expectNoCurrentEvent: Boolean,
): Boolean = when {
    expectNoCurrentEvent -> currentEventId == null
    expectedEventId != null -> currentEventId == expectedEventId
    else -> true
}
