package com.unsilence.app.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.db.entity.EventEntity
import com.unsilence.app.data.db.entity.ReactionEntity
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.EventRepository
import com.unsilence.app.data.wallet.NwcManager
import com.unsilence.app.data.wallet.ZapRepository
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip18Reposts.RepostEvent
import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * Shared ViewModel for note actions (react, repost) that works across FeedScreen and ThreadScreen.
 * Scoped to the Activity, so a single instance is shared by all NoteCard composables.
 */
@HiltViewModel
class NoteActionsViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val relayPool: RelayPool,
    private val eventRepository: EventRepository,
    private val nwcManager: NwcManager,
    private val zapRepository: ZapRepository,
) : ViewModel() {

    private val pubkeyHex: String? = keyManager.getPublicKeyHex()

    /** True if a nostr+walletconnect:// URI has been saved. */
    val isNwcConfigured: Boolean get() = nwcManager.isConfigured

    /**
     * Set of event IDs the current user has reacted to.
     * Room re-emits on every reactions table write, keeping this live.
     */
    val reactedEventIds: StateFlow<Set<String>> =
        pubkeyHex?.let { pk ->
            eventRepository.reactedEventIds(pk)
                .map { it.toHashSet() }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
        } ?: MutableStateFlow(emptySet())

    /**
     * Set of event IDs the current user has reposted.
     * Room re-emits on every events table write.
     */
    val repostedEventIds: StateFlow<Set<String>> =
        pubkeyHex?.let { pk ->
            eventRepository.repostedEventIds(pk)
                .map { it.toHashSet() }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
        } ?: MutableStateFlow(emptySet())

    /**
     * Set of event IDs the current user has zapped (from stored kind 9734 events).
     * Room re-emits on every events table write.
     */
    val zappedEventIds: StateFlow<Set<String>> =
        pubkeyHex?.let { pk ->
            eventRepository.zappedEventIds(pk)
                .map { it.toHashSet() }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
        } ?: MutableStateFlow(emptySet())

    // ── Public actions ────────────────────────────────────────────────────────

    fun react(eventId: String, eventPubkey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val signer    = buildSigner() ?: return@launch
            val nowSeconds = System.currentTimeMillis() / 1000L

            val template = EventTemplate<ReactionEvent>(
                createdAt = nowSeconds,
                kind      = ReactionEvent.KIND,
                tags      = arrayOf(
                    arrayOf("e", eventId),
                    arrayOf("p", eventPubkey),
                ),
                content   = "+",
            )
            val signed = runCatching { signer.sign(template) }.getOrNull() ?: return@launch

            relayPool.publish(toEventJson(signed))

            // Optimistic insert → reactions table updates → reactedEventIds re-emits
            eventRepository.insertReaction(
                ReactionEntity(
                    eventId       = signed.id,
                    targetEventId = eventId,
                    pubkey        = signed.pubKey,
                    content       = "+",
                    createdAt     = signed.createdAt,
                )
            )
        }
    }

    fun repost(eventId: String, eventPubkey: String, eventRelayUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val signer     = buildSigner() ?: return@launch
            val nowSeconds  = System.currentTimeMillis() / 1000L
            val original   = eventRepository.getEventById(eventId) ?: return@launch
            val originalJson = entityToJson(original)

            val template = EventTemplate<RepostEvent>(
                createdAt = nowSeconds,
                kind      = RepostEvent.KIND,
                tags      = arrayOf(
                    arrayOf("e", eventId, eventRelayUrl),
                    arrayOf("p", eventPubkey),
                    arrayOf("k", "1"),
                ),
                content   = originalJson,
            )
            val signed = runCatching { signer.sign(template) }.getOrNull() ?: return@launch

            relayPool.publish(toEventJson(signed))

            // Optimistic insert as kind 6 with rootId = eventId (drives repost_count JOIN)
            eventRepository.insertEvent(
                EventEntity(
                    id        = signed.id,
                    pubkey    = signed.pubKey,
                    kind      = signed.kind,
                    content   = signed.content,
                    createdAt = signed.createdAt,
                    tags      = tagsToJson(signed.tags),
                    sig       = signed.sig,
                    relayUrl  = "wss://relay.damus.io",
                    rootId    = eventId,
                    cachedAt  = nowSeconds,
                )
            )
        }
    }

    fun zap(eventId: String, eventPubkey: String, relayUrl: String, amountSats: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            zapRepository.zap(eventId, eventPubkey, relayUrl, amountSats)
        }
    }

    /** Parse and persist a nostr+walletconnect:// URI. Returns true on success. */
    fun saveNwcUri(uri: String): Boolean = nwcManager.save(uri)

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildSigner(): NostrSignerInternal? {
        val privKeyHex   = keyManager.getPrivateKeyHex() ?: return null
        val privKeyBytes = privKeyHex.hexToByteArray()
        return NostrSignerInternal(KeyPair(privKey = privKeyBytes))
    }

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

    /** Reconstruct the original event's wire JSON from its stored EventEntity. */
    private fun entityToJson(entity: EventEntity): String = buildJsonObject {
        put("id",         entity.id)
        put("pubkey",     entity.pubkey)
        put("created_at", entity.createdAt)
        put("kind",       entity.kind)
        put("tags",       NostrJson.parseToJsonElement(entity.tags))
        put("content",    entity.content)
        put("sig",        entity.sig)
    }.toString()

    private fun tagsToJson(tags: Array<Array<String>>): String = buildJsonArray {
        tags.forEach { row ->
            add(buildJsonArray { row.forEach { cell -> add(JsonPrimitive(cell)) } })
        }
    }.toString()
}
