package com.unsilence.app.ui.feed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.db.entity.EventEntity
import com.unsilence.app.data.db.entity.ReactionEntity
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.EventRepository
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.data.wallet.NwcManager
import com.unsilence.app.data.wallet.ZapRepository
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip18Reposts.RepostEvent
import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    private val signingManager: SigningManager,
    private val relayPool: RelayPool,
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val nwcManager: NwcManager,
    private val zapRepository: ZapRepository,
) : ViewModel() {

    private val pubkeyHex: String? = keyManager.getPublicKeyHex()

    /**
     * True if a nostr+walletconnect:// URI has been saved.
     * mutableStateOf so the UI recomposes immediately after the user connects their wallet —
     * no restart needed for the zap button to become active.
     */
    var isNwcConfigured by mutableStateOf(nwcManager.isConfigured)
        private set

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

    /** Optimistic zap state: event IDs zapped in this session before Room confirms. */
    private val _optimisticZaps = MutableStateFlow(emptySet<String>())

    /**
     * Set of event IDs the current user has zapped.
     * Combines Room-backed storage with in-session optimistic updates so the
     * zap icon turns amber immediately after tapping, without waiting for the
     * NWC round-trip.
     */
    val zappedEventIds: StateFlow<Set<String>> =
        pubkeyHex?.let { pk ->
            combine(
                eventRepository.zappedEventIds(pk).map { it.toHashSet() },
                _optimisticZaps,
            ) { fromRoom, optimistic -> fromRoom + optimistic }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
        } ?: _optimisticZaps
            .map { it.toSet() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // ── Public actions ────────────────────────────────────────────────────────

    fun react(eventId: String, eventPubkey: String) {
        viewModelScope.launch(Dispatchers.IO) {
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
            val signed = signingManager.sign(template) ?: return@launch

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
            val signed = signingManager.sign(template) ?: return@launch

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
        // Optimistic update: icon turns amber immediately, before NWC confirms.
        _optimisticZaps.value = _optimisticZaps.value + eventId
        viewModelScope.launch(Dispatchers.IO) {
            zapRepository.zap(eventId, eventPubkey, relayUrl, amountSats)
        }
    }

    /** Parse and persist a nostr+walletconnect:// URI. Returns true on success. */
    fun saveNwcUri(uri: String): Boolean {
        val saved = nwcManager.save(uri)
        if (saved) isNwcConfigured = true   // triggers recomposition; zap button activates immediately
        return saved
    }

    // ── Lookups for NoteCard embedded content (mentions, quoted posts) ────────

    suspend fun lookupProfile(pubkey: String): UserEntity? =
        userRepository.getUser(pubkey)

    suspend fun lookupEvent(eventId: String): EventEntity? =
        eventRepository.getEventById(eventId)

    // ── Helpers ───────────────────────────────────────────────────────────────

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
