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
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.SnapshotScheduler
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.tagsToJson
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.CardHydrator
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.relay.OgFetcher
import com.unsilence.app.data.relay.toEventJson
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.UserRepository
import java.util.concurrent.ConcurrentHashMap
import com.unsilence.app.data.wallet.NwcManager
import com.unsilence.app.data.wallet.WalletPaymentPendingException
import com.unsilence.app.data.wallet.ZapRepository
import com.unsilence.app.data.wallet.ZapRequest
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip18Reposts.RepostEvent
import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

private const val CARD_WINDOW_WARM_ABOVE = 2
private const val CARD_WINDOW_WARM_LOOKAHEAD = 12
private const val CARD_WINDOW_WARM_MAX_ROWS = 18
private const val CARD_WINDOW_IMAGE_CAP = 4
private const val CARD_WINDOW_OG_CAP = 4
private const val CARD_WINDOW_VIDEO_THUMB_CAP = 8
private const val CARD_WINDOW_PROFILE_CAP = 16
private const val CARD_WINDOW_REFERENCE_CAP = 4
private const val CARD_WINDOW_ARTICLE_CAP = 2
private const val CARD_WINDOW_ENGAGEMENT_LOOKAHEAD = 6
private const val CARD_WINDOW_ENGAGEMENT_DEBOUNCE_MS = 250L
private const val ZAP_REQUEST_MAX_RELAYS = 6
private const val ZAP_REQUEST_MAX_AUTHOR_WRITE_RELAYS = 4
private const val ZAP_REQUEST_MAX_OWN_READ_RELAYS = 2
private const val ZAP_REQUEST_MAX_SOURCE_RELAYS = 2
private const val ZAP_REQUEST_MAX_HINT_RELAYS = 1

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
    private val cardHydrator: CardHydrator,
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

    /**
     * CACHE-ONLY EventModel lookup (no parse). Returns null if the model has not
     * already been parsed. Safe to call per-row on the UI path — unlike
     * [getEventModel], it never triggers ContentParser on the composition thread.
     * Used by VideoPlaybackScope to discover quote-only video rows.
     */
    fun getCachedEventModel(eventId: String) = memoryEventStore.getEventModel(eventId)

    /**
     * Shared pre-viewport card warm path for non-feed surfaces that still render
     * through EventCard/eventFeedItems: profile timelines, search results,
     * threads, article comments, nested reposts/quotes, and longform embeds.
     *
     * The feed ViewModel has its own high-frequency lane because it owns the
     * subscription window. Other screens call this from LazyList viewport samples.
     */
    fun warmCardWindow(
        rows: List<FeedRow>,
        first: Int,
        last: Int,
        cardWidthPx: Int,
        hydrateEngagement: Boolean = true,
        maxRows: Int = CARD_WINDOW_WARM_MAX_ROWS,
    ) {
        if (rows.isEmpty() || cardWidthPx <= 0) return
        val safeFirst = first.coerceIn(0, rows.lastIndex)
        val safeLast = last.coerceAtLeast(safeFirst).coerceAtMost(rows.lastIndex)
        val warmStart = (safeFirst - CARD_WINDOW_WARM_ABOVE).coerceAtLeast(0)
        val warmEnd = (safeLast + 1 + CARD_WINDOW_WARM_LOOKAHEAD).coerceAtMost(rows.size)
        if (warmStart >= warmEnd) return

        val key = CardWindowWarmKey(
            rowCount = rows.size,
            first = warmStart,
            last = warmEnd - 1,
            firstId = rows[warmStart].id,
            lastId = rows[warmEnd - 1].id,
            cardWidthPx = cardWidthPx,
            hydrateEngagement = hydrateEngagement,
        )
        if (key == lastCardWindowWarmKey) return
        lastCardWindowWarmKey = key

        val visibleEnd = (safeLast + 1).coerceAtMost(warmEnd)
        val warmRows = buildList {
            addAll(rows.subList(safeFirst, visibleEnd))
            if (warmStart < safeFirst) addAll(rows.subList(warmStart, safeFirst))
            if (visibleEnd < warmEnd) addAll(rows.subList(visibleEnd, warmEnd))
        }
        viewModelScope.launch(Dispatchers.Default) {
            cardHydrator.warmUpcomingAssets(
                events = warmRows,
                cardWidthPx = cardWidthPx,
                maxRows = maxRows,
                maxImagePrefetches = CARD_WINDOW_IMAGE_CAP,
                maxOgFetches = CARD_WINDOW_OG_CAP,
                maxVideoThumbnails = CARD_WINDOW_VIDEO_THUMB_CAP,
                maxProfileFetches = CARD_WINDOW_PROFILE_CAP,
                maxReferenceFetches = CARD_WINDOW_REFERENCE_CAP,
                maxArticleFetches = CARD_WINDOW_ARTICLE_CAP,
            )
        }

        if (!hydrateEngagement) return
        val engagementEnd = (safeLast + 1 + CARD_WINDOW_ENGAGEMENT_LOOKAHEAD).coerceAtMost(rows.size)
        if (safeFirst >= engagementEnd) return
        val engagementRows = rows.subList(safeFirst, engagementEnd).toList()
        val viewportIds = engagementRows.map { it.id }.toSet()
        cardWindowHydrationJob?.cancel()
        cardWindowHydrationJob = viewModelScope.launch(Dispatchers.Default) {
            delay(CARD_WINDOW_ENGAGEMENT_DEBOUNCE_MS)
            cardHydrator.hydrateVisibleCards(engagementRows, viewportIds = viewportIds)
        }
    }

    private data class CardWindowWarmKey(
        val rowCount: Int,
        val first: Int,
        val last: Int,
        val firstId: String,
        val lastId: String,
        val cardWidthPx: Int,
        val hydrateEngagement: Boolean,
    )

    private var lastCardWindowWarmKey: CardWindowWarmKey? = null
    private var cardWindowHydrationJob: Job? = null

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

    /** Optimistic sats: per-event amount to add on top of MES zapTotalSats. */
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
                if (result.isSuccess) {
                    _zapFlashState.value = ZapFlashState(
                        noteId = id,
                        success = true,
                    )
                } else {
                    val error = result.exceptionOrNull()
                    val message = if (error is WalletPaymentPendingException) {
                        error.message ?: "Wallet has not confirmed this zap yet"
                    } else {
                        "Zap failed: ${error?.message ?: "unknown error"}"
                    }
                    _actionError.emit(message)
                }
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

    /** Outbox-correct target relays for publishing an engagement event (H20c) —
     *  own write + target author's read/inbox + the event's seen relays + hints +
     *  optional fallback; NEVER a broadcast. Falls back to GLOBAL only if empty. */
    private fun engagementTargets(targetId: String, targetAuthor: String, fallbackHint: String?): List<String> {
        val own = pubkeyHex
        return engagementPublishRelays(
            ownWrite         = own?.let { memoryEventStore.writeRelaysFor(it) } ?: emptyList(),
            targetAuthorRead = memoryEventStore.readRelaysFor(targetAuthor),
            eventSeen        = memoryEventStore.getNostrEvent(targetId)?.relaysSeen?.toList() ?: emptyList(),
            relayHints       = memoryEventStore.relayHintsForEvent(targetId),
            fallbackHint     = fallbackHint,
            blocked          = own?.let { memoryEventStore.getBlockedRelayUrls(it).toSet() } ?: emptySet(),
        ).ifEmpty { GLOBAL_RELAY_URLS }
    }

    /** Relay set for the NIP-57 zap-request ["relays", …] tag — where the wallet
     *  publishes the kind-9735 receipt. Mirrors the engagement READ path (target
     *  author WRITE + own READ + event seen/hints) so the receipt lands where the
     *  app actually fetches it; the inverse of [engagementTargets]. Falls back to
     *  GLOBAL only if empty. */
    private fun zapReceiptTargets(targetId: String, targetAuthor: String, fallbackHint: String?): List<String> {
        val own = pubkeyHex
        return zapReceiptRelays(
            targetAuthorWrite = memoryEventStore.writeRelaysFor(targetAuthor),
            ownRead           = own?.let { memoryEventStore.readRelaysFor(it) } ?: emptyList(),
            eventSeen         = memoryEventStore.getNostrEvent(targetId)?.relaysSeen?.toList() ?: emptyList(),
            relayHints        = memoryEventStore.relayHintsForEvent(targetId),
            fallbackHint      = fallbackHint,
            blocked           = own?.let { memoryEventStore.getBlockedRelayUrls(it).toSet() } ?: emptySet(),
            maxRelays         = ZAP_REQUEST_MAX_RELAYS,
            maxAuthorWrite    = ZAP_REQUEST_MAX_AUTHOR_WRITE_RELAYS,
            maxOwnRead        = ZAP_REQUEST_MAX_OWN_READ_RELAYS,
            maxSourceRelays   = ZAP_REQUEST_MAX_SOURCE_RELAYS,
            maxRelayHints     = ZAP_REQUEST_MAX_HINT_RELAYS,
        ).ifEmpty { GLOBAL_RELAY_URLS }
    }

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

            relayPool.publish(toEventJson(signed), engagementTargets(eventId, eventPubkey, null))

            // Optimistic insert → MES actor-index updates → reactedEventIdsFlow re-emits
            memoryEventStore.insert(signedEventToNostrEvent(signed))
            snapshotScheduler.scheduleImmediate()
        }
    }

    fun repost(eventId: String, eventPubkey: String, eventRelayUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val nowSeconds = System.currentTimeMillis() / 1000L
            // Full NostrEvent: we need the ORIGINAL's kind (to pick 6 vs 16), its d tag
            // (for an addressable a-coordinate), and a relay it was actually seen on.
            val original = memoryEventStore.getNostrEvent(eventId)
            if (original == null) {
                _actionError.tryEmit("Repost failed — original note not found")
                return@launch
            }
            val originalJson = nostrEventToJson(original)
            // Prefer a relay the original was seen on — the passed eventRelayUrl may be
            // the repost-wrapper row's relay, not the original's.
            val relayHint = original.relaysSeen.firstOrNull { it.isNotBlank() }
                ?: original.relayUrl.takeIf { it.isNotBlank() }
                ?: eventRelayUrl

            // NIP-18 kind + tags (pure, unit-tested in buildRepostDescriptor).
            val dTag = original.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.getOrNull(1)
            val desc = buildRepostDescriptor(
                targetId = eventId,
                targetPubkey = eventPubkey,
                targetKind = original.kind,
                targetDTag = dTag,
                relayHint = relayHint,
            )
            // kind-6 keeps the dedicated RepostEvent type; kind-16 uses a generic
            // EventTemplate<Event> (RepostEvent assumes kind-6 — guardrail).
            val signed: Event? = if (desc.kind == RepostEvent.KIND) {
                signingManager.sign(EventTemplate<RepostEvent>(
                    createdAt = nowSeconds, kind = desc.kind, tags = desc.tags, content = originalJson,
                ))
            } else {
                signingManager.sign(EventTemplate<Event>(
                    createdAt = nowSeconds, kind = desc.kind, tags = desc.tags, content = originalJson,
                ))
            }

            if (signed == null) {
                _actionError.tryEmit("Repost failed — signing rejected (check Amber permissions)")
                return@launch
            }

            relayPool.publish(toEventJson(signed), engagementTargets(eventId, eventPubkey, relayHint))

            // Optimistic insert → MES actor-index updates → repostedEventIdsFlow re-emits
            memoryEventStore.insert(signedEventToNostrEvent(signed, rootId = eventId))
            snapshotScheduler.scheduleImmediate()
        }
    }

    fun zap(eventId: String, eventPubkey: String, relayUrl: String, request: ZapRequest) {
        if (eventId in _zapLoading.value) return
        val amountSats = request.amountSats
        _zapLoading.value = _zapLoading.value + eventId
        viewModelScope.launch(Dispatchers.IO) {
            // Outbox-targeted zap receipt: the wallet publishes the kind-9735 to the
            // zap-request ["relays", …] tag, so it must point at the engagement READ
            // path (recipient WRITE + own READ + event relays) — NOT the engagement
            // publish path (own write + recipient read) used for reactions/reposts.
            // Otherwise the receipt lands where this app never fetches it and the sats
            // count never updates.
            val zapTargets = zapReceiptTargets(eventId, eventPubkey, relayUrl)
            val result = zapRepository.zap(eventId, eventPubkey, relayUrl, request, zapTargets)
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
                // A private zap is anon-signed, so its kind-9735 receipt carries the
                // anon pubkey (== signed.pubKey), not ours. Register it so MES promotes
                // that receipt's drawer row → own (collapsing it against the optimistic
                // own row); also reconciles a receipt that already arrived. Sender-local.
                if (request.isPrivate && pubkeyHex != null && signed.pubKey != pubkeyHex) {
                    memoryEventStore.registerPendingPrivateZap(eventId, signed.pubKey)
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

    /** In-flight receipt waiters keyed by eventId — re-zapping the same note
     *  cancels the previous waiter instead of leaking a suspended coroutine. */
    private val optimisticClearJobs = ConcurrentHashMap<String, Job>()

    /**
     * Auto-clear the optimistic sats overlay for [eventId] once OUR own kind-9735
     * receipt arrives. Identity-based: fires only for our own zap (public sender ==
     * own, or a private zap promoted anon→own in MES), so someone else's zap on the
     * same post doesn't clear our overlay.
     *
     * Race-safe SUBSCRIBE-THEN-CHECK: ownZapReceivedFlow is a replay-0 SharedFlow, so
     * a receipt processed before we subscribe would be missed. We start collecting
     * FIRST, then read the [hasOwnZapReceipt] state predicate — which reflects every
     * receipt processed up to that read. Anything in the gap is caught by the
     * already-active collector; the clear is idempotent so a double-fire is harmless.
     */
    private fun clearOptimisticOnReceipt(eventId: String) {
        optimisticClearJobs[eventId]?.cancel()
        val job = viewModelScope.launch {
            val collector = launch {
                memoryEventStore.ownZapReceivedFlow.first { it == eventId }
                _optimisticZapSats.value = _optimisticZapSats.value - eventId
            }
            // THEN check: a receipt seen before the collector subscribed is caught here.
            val already = withContext(Dispatchers.IO) { memoryEventStore.hasOwnZapReceipt(eventId) }
            if (already) {
                _optimisticZapSats.value = _optimisticZapSats.value - eventId
                collector.cancel()
            }
        }
        optimisticClearJobs[eventId] = job
        job.invokeOnCompletion { optimisticClearJobs.remove(eventId, job) }
    }

    /** Re-read NWC configured state from storage. Call after external changes (e.g. ZapSettingsScreen). */
    fun refreshNwcConfigured() { isNwcConfigured = nwcManager.isConfigured }

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
        val cached = memoryEventStore.getUserEntity(pubkey)
        if (cached != null && !cached.picture.isNullOrBlank()) return cached
        // Trigger profile fetch — fetchMissingProfiles pre-filters via
        // profileResolver.filterUnresolved() and has in-flight guards.
        userRepository.fetchMissingProfiles(listOf(pubkey))
        if (cached != null) {
            return withTimeoutOrNull(2_000L) {
                memoryEventStore.userEntityFlow(pubkey)
                    .filter { !it?.picture.isNullOrBlank() }
                    .first()
            } ?: cached
        }
        return withTimeoutOrNull(5_000L) {
            memoryEventStore.userEntityFlow(pubkey).filterNotNull().first()
        }
    }

    /**
     * Look up an event by ID. Checks MemoryEventStore first; if missing, triggers a one-shot relay
     * fetch and waits up to 5 seconds for the event to arrive via EventProcessor → MemoryEventStore.
     * [relayHints] from nevent1 URIs are used for targeted fetching.
     *
     * Fetch is triggered once per event ID (fetchedQuoteIds guard), but MES observation
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

        // Curated fallback: own read relays, else GLOBAL — matches ThreadViewModel
        val curatedFallback by lazy {
            val ownPk = pubkeyHex ?: ""
            val readRelays = memoryEventStore.getReadWriteRelayConfigs(ownPk)
                .filter { it.marker == null || it.marker == "read" }
                .mapNotNull { normalizeRelayUrl(it.url) }
            readRelays.ifEmpty { GLOBAL_RELAY_URLS }
        }

        // Guard concurrent lookups for the same event — cleared after completion
        // so evicted events can be re-fetched on next recomposition.
        val shouldFetch = synchronized(fetchingQuoteIds) { fetchingQuoteIds.add(eventId) }
        try {
            if (shouldFetch) {
                withContext(Dispatchers.IO) {
                    if (allHints.isNotEmpty()) {
                        relayPool.fetchEventById(eventId, allHints, bypassDedup = outboxHints.isNotEmpty())
                    } else {
                        relayPool.fetchEventById(eventId, curatedFallback, bypassDedup = true)
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

    fun hasCachedOgMetadata(url: String): Boolean =
        ogFetcher.hasCached(url)

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

    /** Reconstruct the original event's wire JSON from a stored NostrEvent (embedded
     *  in a repost's content so it renders without a refetch). */
    private fun nostrEventToJson(event: NostrEvent): String = buildJsonObject {
        put("id",         event.id)
        put("pubkey",     event.pubkey)
        put("created_at", event.createdAt)
        put("kind",       event.kind)
        put("tags",       NostrJson.parseToJsonElement(event.tagsJson))
        put("content",    event.content)
        put("sig",        event.sig)
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

/** The repost kind + tags for a target, per NIP-18. Pure (unit-tested). */
internal data class RepostDescriptor(val kind: Int, val tags: Array<Array<String>>) {
    // data class with Array — equals/hashCode unused by callers/tests (we assert on
    // kind + tag contents directly), so the default reference-based ones are fine.
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * NIP-18 repost descriptor:
 *  - kind-1 target → kind-6 note repost, tags e(id,relay) + p(author) + k("1").
 *  - any other kind → kind-16 generic repost, tags e + p + k(<originalKind>); an
 *    addressable target (has a non-blank d tag, e.g. 30023) also gets an
 *    a("<kind>:<pubkey>:<d>") coordinate. A non-note target without a d tag still
 *    publishes kind-16 with e/p/k (and the embedded JSON content), but no malformed a.
 */
internal fun buildRepostDescriptor(
    targetId: String,
    targetPubkey: String,
    targetKind: Int,
    targetDTag: String?,
    relayHint: String,
): RepostDescriptor {
    if (targetKind == 1) {
        return RepostDescriptor(
            kind = RepostEvent.KIND,
            tags = arrayOf(
                arrayOf("e", targetId, relayHint),
                arrayOf("p", targetPubkey),
                arrayOf("k", "1"),
            ),
        )
    }
    val tags = mutableListOf(
        arrayOf("e", targetId, relayHint),
        arrayOf("p", targetPubkey),
        arrayOf("k", targetKind.toString()),
    )
    if (!targetDTag.isNullOrBlank()) {
        tags.add(arrayOf("a", "$targetKind:$targetPubkey:$targetDTag", relayHint))
    }
    return RepostDescriptor(kind = 16, tags = tags.toTypedArray())
}

/**
 * Target relay set for publishing an engagement event (reaction/repost) — the
 * outbox-correct destinations, NOT a broadcast to every open socket (H20c) and
 * NOT the read-path resolveEngagementRelays (that's for FETCHING engagement).
 * = own write + the target author's read/inbox + the event's seen relays + stored
 * hints + an optional UI fallback; normalized, blocked-filtered, deduped. Pure +
 * testable. The caller snapshots relaysSeen via .toList() before passing it
 * (it's a ConcurrentHashMap.newKeySet mutated on other threads).
 */
internal fun engagementPublishRelays(
    ownWrite: List<String>,
    targetAuthorRead: List<String>,
    eventSeen: Collection<String>,
    relayHints: Collection<String>,
    fallbackHint: String?,
    blocked: Set<String>,
): List<String> {
    val blockedNorm = blocked.mapNotNull { normalizeRelayUrl(it) }.toSet()
    return buildList {
        addAll(ownWrite)
        addAll(targetAuthorRead)
        addAll(eventSeen)
        addAll(relayHints)
        fallbackHint?.takeIf { it.isNotBlank() }?.let { add(it) }
    }.mapNotNull { normalizeRelayUrl(it) }
        .filter { it !in blockedNorm }
        .distinct()
}

/**
 * Target relay set for the NIP-57 zap-request ["relays", …] tag — where the
 * recipient's wallet/LNURL provider publishes the kind-9735 receipt. This is the
 * deliberate INVERSE of [engagementPublishRelays]: the app FETCHES zap receipts
 * from the engagement READ path (target author WRITE + own READ, see
 * OutboxRelayResolver.resolveEngagementRelays), so the wallet must publish there
 * or the receipt lands where we never query and the sats count never updates.
 * = target author write + own read + the event's seen relays + stored hints +
 * an optional UI fallback; normalized, blocked-filtered, deduped. Pure + testable.
 * The caller snapshots relaysSeen via .toList() before passing it in (it's a
 * ConcurrentHashMap.newKeySet mutated on other threads).
 */
internal fun zapReceiptRelays(
    targetAuthorWrite: List<String>,
    ownRead: List<String>,
    eventSeen: Collection<String>,
    relayHints: Collection<String>,
    fallbackHint: String?,
    blocked: Set<String>,
    maxRelays: Int = Int.MAX_VALUE,
    maxAuthorWrite: Int = Int.MAX_VALUE,
    maxOwnRead: Int = Int.MAX_VALUE,
    maxSourceRelays: Int = Int.MAX_VALUE,
    maxRelayHints: Int = Int.MAX_VALUE,
): List<String> {
    val blockedNorm = blocked.mapNotNull { normalizeRelayUrl(it) }.toSet()
    if (maxRelays <= 0) return emptyList()
    val result = ArrayList<String>(maxRelays.coerceAtMost(8))

    fun append(urls: Iterable<String>, maxFromBucket: Int) {
        if (maxFromBucket <= 0 || result.size >= maxRelays) return
        var addedFromBucket = 0
        for (raw in urls) {
            if (addedFromBucket >= maxFromBucket || result.size >= maxRelays) break
            val normalized = normalizeRelayUrl(raw) ?: continue
            if (normalized in blockedNorm || normalized in result) continue
            result.add(normalized)
            addedFromBucket++
        }
    }

    append(targetAuthorWrite, maxAuthorWrite)
    append(ownRead, maxOwnRead)
    append(eventSeen, maxSourceRelays)
    append(relayHints, maxRelayHints)
    fallbackHint?.takeIf { it.isNotBlank() }?.let { append(listOf(it), 1) }
    return result
}
