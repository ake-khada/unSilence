package com.unsilence.app.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.db.entity.EventEntity
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.EventRepository
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
    private val relayPool: RelayPool,
    private val eventRepository: EventRepository,
) : ViewModel() {

    /** Pubkey for the avatar in the compose UI. */
    val pubkeyHex: String? = keyManager.getPublicKeyHex()

    /** True once the note has been signed, published, and inserted into Room. */
    var published by mutableStateOf(false)
        private set

    fun publishNote(content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val template = TextNoteEvent.build(note = content)
            val signed   = signingManager.sign(template) ?: return@launch

            // Publish wire command to all connected relays
            relayPool.publish(toEventJson(signed))

            // Optimistic insert → appears in feed immediately (relay_url must match feed query)
            val nowSeconds = System.currentTimeMillis() / 1000L
            eventRepository.insertEvent(
                EventEntity(
                    id        = signed.id,
                    pubkey    = signed.pubKey,
                    kind      = signed.kind,
                    content   = signed.content,
                    createdAt = signed.createdAt,
                    tags      = tagsToJson(signed.tags),
                    sig       = signed.sig,
                    // Use first relay so the event appears in the connected-relay feed
                    relayUrl  = "wss://relay.damus.io",
                    cachedAt  = nowSeconds,
                )
            )

            published = true
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Serialise a signed event to the Nostr wire JSON object string. */
    private fun toEventJson(event: Event): String = buildJsonObject {
        put("id",         event.id)
        put("pubkey",     event.pubKey)
        put("created_at", event.createdAt)
        put("kind",       event.kind)
        put("tags",       buildJsonArray {
            event.tags.forEach { row ->
                add(buildJsonArray { row.forEach { cell -> add(JsonPrimitive(cell)) } })
            }
        })
        put("content",    event.content)
        put("sig",        event.sig)
    }.toString()

    /** Convert Array<Array<String>> tags to the JSON string stored in Room. */
    private fun tagsToJson(tags: Array<Array<String>>): String = buildJsonArray {
        tags.forEach { row ->
            add(buildJsonArray { row.forEach { cell -> add(JsonPrimitive(cell)) } })
        }
    }.toString()
}
