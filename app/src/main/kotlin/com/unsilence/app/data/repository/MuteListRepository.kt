package com.unsilence.app.data.repository

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.toEventJson
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MuteRepo"
private const val COALESCE_WINDOW_MS = 500L

@Singleton
class MuteListRepository @Inject constructor(
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
) {
    /** Process-lifetime scope — survives ViewModel teardown. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var publishJob: Job? = null

    /** Optimistic local mute + debounced network publish. */
    fun muteUser(targetPubkey: String) {
        memoryEventStore.addPrivateMute(targetPubkey)
        schedulePublish()
    }

    /** Optimistic local unmute + debounced network publish. */
    fun unmuteUser(targetPubkey: String) {
        memoryEventStore.removePrivateMute(targetPubkey)
        schedulePublish()
    }

    private fun schedulePublish() {
        publishJob?.cancel()
        publishJob = scope.launch {
            delay(COALESCE_WINDOW_MS)
            publishCurrentMuteList()
        }
    }

    private suspend fun publishCurrentMuteList() {
        val ownPubkey = keyManager.getPublicKeyHex() ?: run {
            Log.w(TAG, "No own pubkey — abort publish")
            memoryEventStore.clearMuteListOptimisticFloor()
            return
        }
        val muteList = memoryEventStore.getMuteList(ownPubkey) ?: run {
            Log.w(TAG, "No mute list to publish")
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
            Log.w(TAG, "NIP-44 encrypt failed; aborting publish (local mute stays)")
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
            Log.w(TAG, "Sign failed; aborting publish")
            memoryEventStore.clearMuteListOptimisticFloor()
            return
        }

        val writeRelays = memoryEventStore.writeRelaysFor(ownPubkey)
        if (writeRelays.isEmpty()) {
            Log.w(TAG, "No write relays — publish abandoned")
            memoryEventStore.clearMuteListOptimisticFloor()
            return
        }
        relayPool.publishToRelays(toEventJson(signed), writeRelays)
        val allPubkeys = muteList.pubkeys.size + muteList.privatePubkeys.size
        val allWords = muteList.words.size + muteList.privateWords.size
        Log.i(TAG, "Published kind-10000: ${muteList.totalCount} mutes " +
            "(${allPubkeys}p, ${allWords}w) to ${writeRelays.size} relays")
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
