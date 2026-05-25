package com.unsilence.app.ui.feed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.settings.SettingsStore
import com.unsilence.app.data.memory.CustomEmoji
import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.SnapshotScheduler
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.tagsToJson
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.OgFetcher
import com.unsilence.app.data.relay.toEventJson
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.UserRepository
import java.util.concurrent.ConcurrentHashMap
import com.unsilence.app.data.wallet.NwcManager
import com.unsilence.app.data.wallet.ZapRepository
import com.unsilence.app.data.wallet.ZapRequest
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip18Reposts.RepostEvent
import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val userRepository: UserRepository,
    private val memoryEventStore: MemoryEventStore,
    private val snapshotScheduler: SnapshotScheduler,
    private val ogFetcher: OgFetcher,
    private val nwcManager: NwcManager,
    private val zapRepository: ZapRepository,
    private val settingsStore: SettingsStore,
    val sharedPlayerHolder: SharedPlayerHolder,
    val videoThumbnailCache: VideoThumbnailCache,
    val imageDimensionCache: ImageDimensionCache,
) : ViewModel() {

    private val pubkeyHex: String? = keyManager.getPublicKeyHex()

    init {
        viewModelScope.launch { settingsStore.initialize() }
    }

    /**
     * True if a nostr+walletconnect:// URI has been saved.
     * mutableStateOf so the UI recomposes immediately after the user connects their wallet —
     * no restart needed for the zap button to become active.
     */
    var isNwcConfigured by mutableStateOf(nwcManager.isConfigured)
        private set

    /** MES sidecar cache lookup — pre-computed at EventProcessor insert time. */
    fun getVideoRenderModels(eventId: String) = memoryEventStore.getVideoRenderModels(eventId)

    /** MES sidecar cache lookup — image aspect ratios from imeta dims at insert time. */
    fun getImetaImageDims(eventId: String) = memoryEventStore.getImetaImageDims(eventId)

    /** MES sidecar cache lookup — pre-parsed EventModel for rendering. */
    fun getEventModel(eventId: String) = memoryEventStore.getOrParseEventModel(eventId)

    // ── Custom emoji picker data ─────────────────────────────────────────────

    /** All resolved custom emoji for the logged-in user (inline + subscribed sets). */
    fun getSubscribedEmojis(): List<CustomEmoji> =
        pubkeyHex?.let { memoryEventStore.resolvedEmojisFor(it) } ?: emptyList()

    /** Emojis grouped by set name — for category-headed picker rendering. */
    fun getSubscribedEmojisBySet(): List<Pair<String, List<CustomEmoji>>> =
        pubkeyHex?.let { memoryEventStore.resolvedEmojisBySet(it) } ?: emptyList()

    /** Pinned emoji shortcodes (DataStore-backed). */
    val pinnedEmojiShortcodes: StateFlow<Set<String>> = settingsStore.pinnedEmojiShortcodes

    /** Toggle pin state for a shortcode. */
    fun togglePinnedEmoji(shortcode: String) {
        viewModelScope.launch {
            val current = settingsStore.pinnedEmojiShortcodes.value
            val updated = if (shortcode in current) current - shortcode else current + shortcode
            settingsStore.setPinnedEmojiShortcodes(updated)
        }
    }

    /** Resolve pinned shortcodes to full CustomEmoji objects (for quick strip). */
    fun getPinnedEmojis(): List<CustomEmoji> {
        val pinned = settingsStore.pinnedEmojiShortcodes.value
        if (pinned.isEmpty()) return emptyList()
        val all = getSubscribedEmojis()
        val byShortcode = all.associateBy { it.shortcode }
        return pinned.mapNotNull { byShortcode[it] }
    }

    /**
     * Set of event IDs the current user has reacted to.
     * MES re-emits via _actionSignal on every kind-7 insert.
     */
    val reactedEventIds: StateFlow<Set<String>> =
        pubkeyHex?.let { pk ->
            memoryEventStore.reactedEventIdsFlow(pk)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
        } ?: MutableStateFlow(emptySet())

    /**
     * Set of event IDs the current user has reposted.
     * MES re-emits via _actionSignal on every kind-6 insert.
     */
    val repostedEventIds: StateFlow<Set<String>> =
        pubkeyHex?.let { pk ->
            memoryEventStore.repostedEventIdsFlow(pk)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
        } ?: MutableStateFlow(emptySet())

    /** Emitted when react / repost signing fails — screens collect and show a snackbar. */
    private val _actionError = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val actionError: SharedFlow<String> = _actionError.asSharedFlow()

    /** Optimistic sats: per-event amount to add on top of Room's zapTotalSats. */
    private val _optimisticZapSats = MutableStateFlow<Map<String, Long>>(emptyMap())
    val optimisticZapSats: StateFlow<Map<String, Long>> = _optimisticZapSats.asStateFlow()

    /** Event IDs currently being zapped (payment in flight). */
    private val _zapLoading = MutableStateFlow<Set<String>>(emptySet())
    val zapLoading: StateFlow<Set<String>> = _zapLoading.asStateFlow()

    /** Zap results: eventId → success(amountSats) or failure. */
    private val _zapResult = MutableSharedFlow<Pair<String, Result<Long>>>(extraBufferCapacity = 10)

    /**
     * Most recent zap result, lifted to screen-level observation.
     * Cards key their flash effect on this value instead of each collecting the SharedFlow.
     * The [tick] field ensures distinct emissions even if the same note is zapped twice.
     */
    @androidx.compose.runtime.Immutable
    data class ZapFlashState(val noteId: String, val success: Boolean, val message: String? = null, val tick: Long = System.nanoTime())

    private val _zapFlashState = MutableStateFlow<ZapFlashState?>(null)
    val zapFlashState: StateFlow<ZapFlashState?> = _zapFlashState.asStateFlow()

    init {
        viewModelScope.launch {
            _zapResult.collect { (id, result) ->
                _zapFlashState.value = ZapFlashState(
                    noteId = id,
                    success = result.isSuccess,
                    message = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    /**
     * Set of event IDs the current user has zapped.
     * MES re-emits via _actionSignal on every kind-9734 insert.
     */
    val zappedEventIds: StateFlow<Set<String>> =
        pubkeyHex?.let { pk ->
            memoryEventStore.zappedEventIdsFlow(pk)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
        } ?: MutableStateFlow(emptySet())

    // ── Public actions ────────────────────────────────────────────────────────

    fun react(
        eventId: String,
        eventPubkey: String,
        emoji: String = "+",
        customEmojiUrl: String? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val nowSeconds = System.currentTimeMillis() / 1000L

            val baseTags = arrayOf(
                arrayOf("e", eventId),
                arrayOf("p", eventPubkey),
            )
            // NIP-25 + NIP-30: custom emoji reactions include an ["emoji", shortcode, url] tag
            val tags = if (customEmojiUrl != null) {
                val shortcode = emoji.removePrefix(":").removeSuffix(":")
                baseTags + arrayOf(arrayOf("emoji", shortcode, customEmojiUrl))
            } else baseTags

            val template = EventTemplate<ReactionEvent>(
                createdAt = nowSeconds,
                kind      = ReactionEvent.KIND,
                tags      = tags,
                content   = emoji,
            )
            val signed = signingManager.sign(template) ?: run {
                _actionError.tryEmit("React failed — signing rejected (check Amber permissions)")
                return@launch
            }

            relayPool.publish(toEventJson(signed))

            // Optimistic insert → MES actor-index updates → reactedEventIdsFlow re-emits
            memoryEventStore.insert(signedEventToNostrEvent(signed))
            snapshotScheduler.scheduleImmediate()
        }
    }

    fun repost(eventId: String, eventPubkey: String, eventRelayUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val nowSeconds  = System.currentTimeMillis() / 1000L
            val original   = memoryEventStore.getEventEntity(eventId)
            if (original == null) {
                _actionError.tryEmit("Repost failed — original note not found")
                return@launch
            }
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
            val signed = signingManager.sign(template) ?: run {
                _actionError.tryEmit("Repost failed — signing rejected (check Amber permissions)")
                return@launch
            }

            relayPool.publish(toEventJson(signed))

            // Optimistic insert → MES actor-index updates → repostedEventIdsFlow re-emits
            memoryEventStore.insert(signedEventToNostrEvent(signed, rootId = eventId))
            snapshotScheduler.scheduleImmediate()
        }
    }

    fun zap(eventId: String, eventPubkey: String, relayUrl: String, request: ZapRequest) {
        val amountSats = request.amountSats
        _zapLoading.value = _zapLoading.value + eventId
        viewModelScope.launch(Dispatchers.IO) {
            val result = zapRepository.zap(eventId, eventPubkey, relayUrl, request)
            if (result.isSuccess) {
                val signed = result.getOrThrow()
                // Optimistic insert → MES actor-index updates → zappedEventIdsFlow re-emits
                // Icon lights up immediately; sats display waits for kind-9735 receipt
                // from relays (handleZapReceipt is the sole path into zapStatsByEventId).
                val nostrEvent = signedEventToNostrEvent(signed, rootId = eventId)
                // Private zaps are signed by a one-shot anon keypair. Override pubkey
                // to own so MES actor indexes correctly track "has zapped" state.
                val toInsert = if (request.isPrivate && pubkeyHex != null)
                    nostrEvent.copy(pubkey = pubkeyHex)
                else nostrEvent
                memoryEventStore.insert(toInsert)
                // Optimistic drawer chip — shows immediately before kind-9735 arrives.
                if (pubkeyHex != null) {
                    memoryEventStore.addOptimisticZapDetail(
                        eventId, pubkeyHex, amountSats, request.message,
                    )
                }
                snapshotScheduler.scheduleImmediate()
            }
            withContext(Dispatchers.Main) {
                _zapLoading.value = _zapLoading.value - eventId
                if (result.isSuccess) {
                    // Optimistic sats overlay — instant display until kind-9735 receipt
                    // arrives and handleZapReceipt bumps zapStatsByEventId. At that point
                    // clearOptimisticOnReceipt removes the overlay so there's no double-count.
                    _optimisticZapSats.value = _optimisticZapSats.value +
                        (eventId to ((_optimisticZapSats.value[eventId] ?: 0L) + amountSats))
                    clearOptimisticOnReceipt(eventId)
                    _zapResult.emit(eventId to Result.success(amountSats))
                } else {
                    _zapResult.emit(eventId to Result.failure(
                        result.exceptionOrNull() ?: Exception("Zap failed")
                    ))
                }
            }
        }
    }

    /**
     * Auto-clear the optimistic sats overlay for [eventId] once OUR own
     * kind-9735 receipt arrives. Identity-based: fires only when the
     * receipt's embedded kind-9734 author matches ownPubkey, so someone
     * else's zap on the same post doesn't clear our overlay prematurely.
     */
    private fun clearOptimisticOnReceipt(eventId: String) {
        viewModelScope.launch {
            memoryEventStore.ownZapReceivedFlow
                .first { it == eventId }
            _optimisticZapSats.value = _optimisticZapSats.value - eventId
        }
    }

    /** Parse and persist a nostr+walletconnect:// URI. Returns true on success. */
    fun saveNwcUri(uri: String): Boolean {
        val saved = nwcManager.save(uri)
        if (saved) isNwcConfigured = true   // triggers recomposition; zap button activates immediately
        return saved
    }

    // ── Lookups for NoteCard embedded content (mentions, quoted posts) ────────

    /** Event IDs currently being looked up (prevents concurrent relay requests).
     *  Cleared after each lookup completes so evicted events can be re-fetched. */
    private val fetchingQuoteIds = mutableSetOf<String>()

    /**
     * Look up a profile by pubkey. Returns immediately if cached; otherwise
     * triggers a relay fetch and waits up to 5 seconds for the profile to
     * arrive via EventProcessor → MemoryEventStore.
     * Mirrors lookupEvent's fetch-then-wait pattern so embedded quote author
     * profiles resolve even when not pre-fetched by hydrateProfiles.
     */
    suspend fun lookupProfile(pubkey: String): UserEntity? {
        memoryEventStore.getUserEntity(pubkey)?.let { return it }
        // Trigger profile fetch — fetchMissingProfiles pre-filters via
        // profileResolver.filterUnresolved() and has in-flight guards.
        userRepository.fetchMissingProfiles(listOf(pubkey))
        return withTimeoutOrNull(5_000L) {
            memoryEventStore.userEntityFlow(pubkey).filterNotNull().first()
        }
    }

    /**
     * Look up an event by ID. Checks Room first; if missing, triggers a one-shot relay
     * fetch and waits up to 5 seconds for the event to arrive via EventProcessor → Room.
     * [relayHints] from nevent1 URIs are used for targeted fetching.
     *
     * Fetch is triggered once per event ID (fetchedQuoteIds guard), but Room observation
     * happens every call — so recomposition after a late relay arrival can still resolve.
     */
    suspend fun lookupEvent(
        eventId: String,
        relayHints: List<String> = emptyList(),
        authorPubkey: String? = null,
    ): EventEntity? {
        // Fast path: already in MemoryEventStore
        memoryEventStore.getEventEntity(eventId)?.let {
            memoryEventStore.markTouched(eventId)  // LRU bump — quoted event actively resolved
            return it
        }

        // Fast-fail: known unresolved (negative cache, 5-min TTL).
        // Avoids 5s MES-flow wait for events that already failed all outbox phases.
        if (relayPool.isEventUnresolved(eventId)) return null

        // Build relay hints: caller hints + outbox write relays for the author
        val outboxHints = authorPubkey?.let { memoryEventStore.writeRelaysFor(it) } ?: emptyList()
        val allHints = (relayHints + outboxHints).distinct()

        // Guard concurrent lookups for the same event — cleared after completion
        // so evicted events can be re-fetched on next recomposition.
        val shouldFetch = synchronized(fetchingQuoteIds) { fetchingQuoteIds.add(eventId) }
        try {
            if (shouldFetch) {
                withContext(Dispatchers.IO) {
                    if (allHints.isNotEmpty()) {
                        relayPool.fetchEventById(eventId, allHints, bypassDedup = outboxHints.isNotEmpty())
                    } else {
                        relayPool.fetchEventById(eventId)
                    }
                }
            }

            // Wait for the event to appear in MemoryEventStore (via EventProcessor)
            return withTimeoutOrNull(5_000L) {
                memoryEventStore.eventEntityFlow(eventId).filterNotNull().first()
            }
        } finally {
            synchronized(fetchingQuoteIds) { fetchingQuoteIds.remove(eventId) }
        }
    }

    suspend fun fetchOgMetadata(url: String): OgMetadata? =
        ogFetcher.fetch(url)

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    /**
     * Convert a signed Quartz Event to a NostrEvent for MES optimistic insert.
     * Parses NIP-10 e-tag threading to set rootId (used by repost + zap actor indexes).
     */
    private fun signedEventToNostrEvent(
        signed: Event,
        rootId: String? = null,
    ): NostrEvent {
        val tagsList = signed.tags.map { it.toList() }
        // If rootId not explicitly provided, try NIP-10 positional parse from e-tags
        val resolvedRootId = rootId ?: run {
            val eTags = tagsList.filter { it.size >= 2 && it[0] == "e" }
            when {
                eTags.isEmpty() -> null
                eTags.size == 1 -> eTags[0][1]
                else -> {
                    // Marker-based
                    eTags.firstOrNull { it.getOrNull(3) == "root" }?.get(1)
                    // Fallback: positional (first e-tag = root)
                        ?: eTags[0][1]
                }
            }
        }
        val now = System.currentTimeMillis()
        return NostrEvent(
            id = signed.id,
            pubkey = signed.pubKey,
            kind = signed.kind,
            content = signed.content,
            createdAt = signed.createdAt,
            tags = tagsList,
            tagsJson = tagsToJson(tagsList),
            sig = signed.sig,
            relayUrl = "",
            replyToId = null,
            rootId = resolvedRootId,
            hasContentWarning = false,
            contentWarningReason = null,
            firstSeenAt = now,
            relaysSeen = ConcurrentHashMap.newKeySet(),
        )
    }
}
