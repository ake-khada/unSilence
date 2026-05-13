package com.unsilence.app.data.repository

import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.SnapshotScheduler
import com.unsilence.app.data.memory.tagsToJson
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.toEventJson
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val COALESCE_WINDOW_MS = 500L

enum class MuteResult {
    /** Local mute applied + network publish scheduled. */
    Queued,
    /** Local mute applied but network publish skipped (Amber decrypt not available). */
    LocalOnly,
}

@Singleton
class MuteListRepository @Inject constructor(
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val snapshotScheduler: SnapshotScheduler,
) {
    /** Process-lifetime scope — survives ViewModel teardown. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var publishJob: Job? = null

    /** Set of event IDs we've published ourselves — to skip re-decrypt of our own echo. */
    private val selfPublishedEventIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Tracks whether we've confirmed the user's network mute-list state.
     * Only true when:
     *   a) bootstrap completed AND fetchMuteList timed out with no event found
     *      (legitimately empty), OR
     *   b) a kind-10000 was found AND its private content successfully decrypted, OR
     *   c) a kind-10000 was found AND its content is empty (no private mutes), OR
     *   d) nsec mode (decrypt always works inline)
     *
     * False during decrypt-in-flight, decrypt-failed, or bootstrap-in-progress.
     */
    private val _publishSafe = MutableStateFlow(false)
    val publishSafe: StateFlow<Boolean> = _publishSafe.asStateFlow()

    fun markPublishSafe() { _publishSafe.value = true }

    fun markPublishUnsafe(reason: String) { _publishSafe.value = false }

    /** Called from MES handleMuteList to check if an arriving event is our own echo. */
    fun isSelfPublished(eventId: String): Boolean = eventId in selfPublishedEventIds

    /**
     * Mute a user. Local mute is ALWAYS applied (feed filtering works immediately).
     * Network publish is only scheduled when publishSafe is true — i.e. we have
     * confirmed the full mute list state from relays + successful decrypt.
     * Without this gate, premature publish replaces the relay-side list with a stub.
     */
    fun muteUser(targetPubkey: String): MuteResult {
        memoryEventStore.addPrivateMute(targetPubkey)
        snapshotScheduler.scheduleImmediate()   // persist optimistic state — survives hard kill mid-debounce
        if (!_publishSafe.value) return MuteResult.LocalOnly
        schedulePublish()
        return MuteResult.Queued
    }

    /**
     * Unmute a user. Local unmute is ALWAYS applied.
     * Network publish gated on publishSafe — same rationale as muteUser.
     */
    fun unmuteUser(targetPubkey: String): MuteResult {
        memoryEventStore.removePrivateMute(targetPubkey)
        snapshotScheduler.scheduleImmediate()   // persist optimistic state — survives hard kill mid-debounce
        if (!_publishSafe.value) return MuteResult.LocalOnly
        schedulePublish()
        return MuteResult.Queued
    }

    private fun schedulePublish() {
        publishJob?.cancel()
        publishJob = scope.launch {
            delay(COALESCE_WINDOW_MS)
            publishCurrentMuteList()
        }
    }

    private suspend fun publishCurrentMuteList() {
        if (!_publishSafe.value) {
            memoryEventStore.clearMuteListOptimisticFloor()
            return
        }

        val ownPubkey = keyManager.getPublicKeyHex() ?: run {
            memoryEventStore.clearMuteListOptimisticFloor()
            return
        }
        val muteList = memoryEventStore.getMuteList(ownPubkey) ?: run {
            memoryEventStore.clearMuteListOptimisticFloor()
            return
        }

        // Public tags — unchanged by add/remove of private mutes
        val publicTags = mutableListOf<Array<String>>()
        muteList.pubkeys.forEach { publicTags.add(arrayOf("p", it)) }
        muteList.hashtags.forEach { publicTags.add(arrayOf("t", it)) }
        muteList.words.forEach { publicTags.add(arrayOf("word", it)) }
        muteList.eventIds.forEach { publicTags.add(arrayOf("e", it)) }

        // Private tags as JSON array of tag arrays — encrypt-to-self
        val privateTagsJson = buildPrivateTagsJson(muteList)
        val encryptedContent = signingManager.encrypt(privateTagsJson, ownPubkey) ?: run {
            memoryEventStore.clearMuteListOptimisticFloor()
            return
        }

        val template = EventTemplate<Event>(
            createdAt = System.currentTimeMillis() / 1000L,
            kind = 10000,
            tags = publicTags.toTypedArray(),
            content = encryptedContent,
        )
        val signed = signingManager.sign(template) ?: run {
            memoryEventStore.clearMuteListOptimisticFloor()
            return
        }

        // Register self-publish BEFORE sending — handleMuteList must already know
        // by the time the echo arrives.
        selfPublishedEventIds.add(signed.id)

        // Store the signed event in MES immediately so the next snapshot save
        // captures the latest mute list. Without this, backgrounding before the
        // relay echo arrives would save the OLD kind-10000 event, and cold-start
        // would restore stale mute state until Phase 2 re-fetches.
        val tags = signed.tags.map { it.toList() }
        val localEvent = NostrEvent(
            id = signed.id,
            pubkey = signed.pubKey,
            kind = 10000,
            content = signed.content,
            createdAt = signed.createdAt,
            tags = tags,
            tagsJson = tagsToJson(tags),
            sig = signed.sig,
            relayUrl = "",
            replyToId = null,
            rootId = null,
            hasContentWarning = false,
            contentWarningReason = null,
            firstSeenAt = System.currentTimeMillis(),
            relaysSeen = ConcurrentHashMap.newKeySet(),
        )
        memoryEventStore.storeLocalEvent(localEvent)
        snapshotScheduler.scheduleImmediate()

        val writeRelays = memoryEventStore.writeRelaysFor(ownPubkey)
        if (writeRelays.isEmpty()) {
            selfPublishedEventIds.remove(signed.id)
            memoryEventStore.clearMuteListOptimisticFloor()
            return
        }
        relayPool.publishToRelays(toEventJson(signed), writeRelays)
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
