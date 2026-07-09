package com.unsilence.app.ui.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.init.InitGate
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.EventStats
import com.unsilence.app.data.memory.ReactionInfo
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.data.memory.RelaySet
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.memory.ZapDetail
import com.unsilence.app.data.relay.CardHydrator
import com.unsilence.app.data.relay.ConnectionPurpose
import com.unsilence.app.data.relay.ENGAGEMENT_LOOKAHEAD
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.OutboxRelayResolver
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.relay.SubRequest
import com.unsilence.app.data.relay.TimelineMerge
import com.unsilence.app.data.relay.TimelineService
import com.unsilence.app.data.relay.WotHydrationCoalescer
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.relay.wotLookupSnapshot
import com.unsilence.app.data.relay.wotSubjectsForFeedRows
import com.unsilence.app.data.model.ReportType
import com.unsilence.app.data.repository.MuteListRepository
import com.unsilence.app.data.repository.MuteResult
import com.unsilence.app.data.repository.ReportRepository
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.ui.shared.TimelineCardData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val TAG = "FeedVM"
private const val TRACE_FEED_VM = false

private inline fun feedTrace(message: () -> String) {
    if (TRACE_FEED_VM) Log.d(TAG, message())
}

sealed class FeedType {
    data object Global    : FeedType()
    data object Following : FeedType()
    data class  RelaySet(val dTag: String, val name: String) : FeedType()
    class SingleRelay private constructor(val url: String, val label: String) : FeedType() {
        val displayLabel: String get() = when {
            url.contains("antiprimal.net/hot") -> "Popular"
            else -> label
        }

        override fun equals(other: Any?): Boolean =
            other is SingleRelay && url == other.url && label == other.label

        override fun hashCode(): Int = 31 * url.hashCode() + label.hashCode()

        override fun toString(): String = "SingleRelay(url=$url, label=$label)"

        companion object {
            // Single choke point: every SingleRelay url is canonicalized (normalizeRelayUrl strips
            // the trailing slash + scheme). Without this, a favorited relay (MES-normalized, no
            // slash) and the same relay from Browse (wss://nos.lol/) compare unequal — breaking
            // the carousel active-pill highlight and the transient-browse dedup. Normalize once,
            // here, not in scattered comparisons.
            operator fun invoke(url: String, label: String): SingleRelay =
                SingleRelay(normalizeRelayUrl(url) ?: url, label)
        }
    }

    companion object {
        val Popular = SingleRelay("wss://antiprimal.net/hot", "Popular")
    }
}

enum class FeedContentFilter(val value: Int) {
    NOTES_ONLY(1),
    REPLIES_ONLY(2),
}

/**
 * THE feed ViewModel -- sole owner of feed-screen state.
 *
 * Owns:
 *   - UI state: feedType, contentFilter, coldStart, splash, userAvatar,
 *     userSets, pinnedRelays, hasFollows, filter
 *   - Timeline state: _events, _newEvents, feedRows, showDot, pendingCount,
 *     isLoading, isLoadingMore (mirrors Jumble NoteList component state)
 *   - Actions: setFeedType, setContentFilter, updateFilter,
 *     onViewportChanged, onDotTapped, loadMore, refresh
 *   - pinnedRelays = the user's kind-10012 favorites (carousel single-relay feeds)
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class FeedViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val memoryEventStore: MemoryEventStore,
    private val timelineService: TimelineService,
    private val outboxResolver: OutboxRelayResolver,
    private val userRepository: UserRepository,
    private val timelineCardData: TimelineCardData,
    private val relayPreferencesStore: RelayPreferencesStore,
    private val initGate: InitGate,
    private val cardHydrator: CardHydrator,
    private val wotHydrationCoalescer: WotHydrationCoalescer,
    private val relayPool: RelayPool,
    private val muteListRepository: MuteListRepository,
    private val reportRepository: ReportRepository,
) : ViewModel() {

    // ── Timeline state (mirrors Jumble NoteList component state) ──────────────

    /** Main timeline events, sorted by createdAt-DESC. */
    private val _events = MutableStateFlow<List<NostrEvent>>(emptyList())
    val events: StateFlow<List<NostrEvent>> = _events.asStateFlow()

    /** Pending events buffer — populated when user is scrolled away from top. */
    private val _newEvents = MutableStateFlow<List<NostrEvent>>(emptyList())

    private val _isAtTop = MutableStateFlow(true)
    val isAtTop: StateFlow<Boolean> = _isAtTop.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    private val _isLoadingMore = MutableStateFlow(false)

    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    val pendingCount: StateFlow<Int> = _newEvents
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val showDot: StateFlow<Boolean> = _newEvents
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val rawEventCount: StateFlow<Int> = _events
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** IDs of events that arrived via live-tail (not initial load, snapshot, or Load More). */
    private val _liveArrivalIds = MutableStateFlow<Set<String>>(emptySet())
    val liveArrivalIds: StateFlow<Set<String>> = _liveArrivalIds.asStateFlow()

    /** Non-null when the current SingleRelay feed's relay is auth-unavailable. */
    private val _authUnavailableRelay = MutableStateFlow<String?>(null)
    val authUnavailableRelay: StateFlow<String?> = _authUnavailableRelay.asStateFlow()

    fun clearLiveArrival(id: String) {
        _liveArrivalIds.update { it - id }
    }

    private var currentHandle: TimelineService.TimelineHandle? = null
    private var lastFeedType: FeedType? = null

    /** URL we tagged as FEED_SUB for the current single-relay feed.
     *  Tracked so we can cleanly remove the tag when switching feeds. */
    @Volatile private var feedSubPersistentUrl: String? = null

    // -- Content filter (must be before feedRows which references it) ----------

    private val _contentFilter = MutableStateFlow(FeedContentFilter.NOTES_ONLY)
    val contentFilter: StateFlow<FeedContentFilter> = _contentFilter.asStateFlow()

    private val _filter = MutableStateFlow(FeedFilter())
    val filterFlow: StateFlow<FeedFilter> = _filter.asStateFlow()

    // ── feedRows derivation (incremental row cache) ────────────────────────────

    /** Bounded row cache — retains rows across slice swaps so pull-refresh hits instead of re-synth. */
    private val feedRowCache = androidx.collection.LruCache<String, FeedRow>(FEED_ROW_CACHE_SIZE)

    val sensitiveContentMode: StateFlow<SensitiveContentMode> =
        relayPreferencesStore.sensitiveContentModeFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, SensitiveContentMode.BLUR)

    private val eventsWithFilter = combine(_events, _filter) { events, filter -> events to filter }

    val feedRows: StateFlow<List<FeedRow>> =
        combine(
            eventsWithFilter,
            _contentFilter,
            memoryEventStore.ownMuteListFlow(),
            relayPreferencesStore.sensitiveContentModeFlow(),
            memoryEventStore.feedSignalFlow,
        ) { eventFilterPair, cf, muteList, scm, _ ->
            val (events, filter) = eventFilterPair
            if (events.isEmpty()) {
                feedRowCache.evictAll()
                return@combine emptyList()
            }
            val hideSensitive = scm == SensitiveContentMode.HIDE
            val nowSec = System.currentTimeMillis() / 1000L
            val preActivityCandidates = events.asSequence()
                .filterNot { memoryEventStore.isDeleted(it) }
                .filter { matchesFeedFilterBeforeActivity(it, filter, nowSec) }
                .filter { matchesContentFilter(it, cf) }
                .filter { !isMuted(it, muteList) }
                .filter { !hideSensitive || !it.hasContentWarning }
                .toList()
            requestActivityCandidateSweep(filter, preActivityCandidates)

            val displayed = preActivityCandidates.asSequence()
                .filter { matchesActivityThresholds(it, filter) }
                .take(FEED_DISPLAY_CAP)
                .toList()
            if (displayed.isEmpty()) {
                feedRowCache.evictAll()
                return@combine emptyList()
            }

            // Determine which IDs need fresh rows (not in cache)
            val displayedIds = displayed.map { it.id }.toSet()

            // Protect displayed events from LRU eviction — events in the
            // active timeline must stay in MES for EventModel resolution.
            memoryEventStore.markTouched(displayedIds)

            val missingIds = displayedIds.filter { feedRowCache.get(it) == null }
            val hitCount = displayedIds.size - missingIds.size
            feedTrace { "feedRowCache hit=${hitCount} miss=${missingIds.size}" }

            // Fetch only missing rows from MES
            if (missingIds.isNotEmpty()) {
                val missingSet = missingIds.toSet()
                val newRows = memoryEventStore.feedRowsByIds(missingSet)
                for (row in newRows) {
                    feedRowCache.put(row.id, row)
                }
                // Synthesize fallback for any still-missing (race with MES insert)
                for (id in missingIds) {
                    if (feedRowCache.get(id) == null) {
                        val evt = displayed.first { it.id == id }
                        feedRowCache.put(id, memoryEventStore.synthesizeFeedRow(evt))
                    }
                }
            }

            // LRU bounds the cache — no manual eviction needed
            // Build ordered result from cache
            displayed.mapNotNull { evt -> feedRowCache.get(evt.id) }
        }
            .conflate()
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // -- Relay-metadata version (triggers resubscribe on kind-10002 arrival) ---

    /**
     * Coarse milestone signal. Bumps when relay-list coverage crosses 25%, 50%,
     * 75%, 90% of follows. Avoids the per-relay-list trickle that produced
     * 6+ resubscribes per cold start.
     */
    private val relayMetadataVersion: StateFlow<Int> = run {
        val ownPubkey = keyManager.getPublicKeyHex()
        if (ownPubkey == null) {
            MutableStateFlow(0).asStateFlow()
        } else {
            combine(
                memoryEventStore.allRelayListsFlow().map { it.size },
                memoryEventStore.followsFlow(ownPubkey).map { it.size },
            ) { listsCount, followsCount ->
                if (followsCount == 0) 0
                else {
                    val pct = (listsCount * 100) / followsCount
                    when {
                        pct >= 90 -> 4
                        pct >= 75 -> 3
                        pct >= 50 -> 2
                        pct >= 25 -> 1
                        else -> 0
                    }
                }
            }
                .distinctUntilChanged()
                .debounce(2_000L) // coalesce kind-10002 bursts HERE, not globally
                .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
        }
    }

    // -- Feed type -------------------------------------------------------------

    private val _feedType = MutableStateFlow<FeedType>(
        if (keyManager.getPublicKeyHex() != null) FeedType.Following else FeedType.Global,
    )
    val feedType: StateFlow<FeedType> = _feedType.asStateFlow()

    fun setFeedType(type: FeedType) {
        feedTrace { "setFeedType: ${_feedType.value} → $type" }
        _authUnavailableRelay.value = null
        _feedType.value = type
        if (_coldStartState.value == ColdStartState.LOADING) {
            _coldStartState.value = if (type is FeedType.Following)
                ColdStartState.READY_FOLLOWING else ColdStartState.READY_GLOBAL
        }
    }

    // -- Content filter --------------------------------------------------------

    fun setContentFilter(f: FeedContentFilter) {
        if (_contentFilter.value == f) return
        feedTrace { "setContentFilter: ${_contentFilter.value} → $f" }
        _contentFilter.value = f
        // Pure client-side projection — feedRows recomposes via its own
        // combine on _contentFilter. NO subscription restart. See CG-R1.
    }

    fun updateFilter(filter: FeedFilter) {
        _filter.value = filter
    }

    // -- Cold-start ------------------------------------------------------------

    enum class ColdStartState { LOADING, READY_FOLLOWING, READY_GLOBAL }

    private val _coldStartState = MutableStateFlow(ColdStartState.LOADING)
    val coldStartState: StateFlow<ColdStartState> = _coldStartState.asStateFlow()
    val splashDone: StateFlow<Boolean> = _coldStartState
        .map { it != ColdStartState.LOADING }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _hasFollows = MutableStateFlow(false)
    val hasFollows: StateFlow<Boolean> = _hasFollows.asStateFlow()
    private val _followsVersion = MutableStateFlow(0L)

    // -- User avatar -----------------------------------------------------------

    val userAvatarUrl: StateFlow<String?> = keyManager.getPublicKeyHex()?.let { pubkey ->
        userRepository.userFlow(pubkey)
            .map { it?.picture }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    } ?: MutableStateFlow(null)

    private val _wotSubjects = MutableStateFlow<Set<String>>(emptySet())
    val wotLookups: StateFlow<Map<String, WotLookup>> =
        combine(_wotSubjects, memoryEventStore.wotSignalFlow) { subjects, _ ->
            wotLookupSnapshot(subjects, memoryEventStore::wotFor)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val feedWotDisplayMode =
        relayPreferencesStore.feedWotDisplayModeFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, FeedWotDisplayMode.NUMBERS)

    // -- Profile lookup for repost original authors ----------------------------

    fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
        timelineCardData.profileFlow(pubkey, viewModelScope)

    // -- Per-event stats lookup (replyCount, reactionCount, etc.) -------------

    fun statsFlow(eventId: String): StateFlow<EventStats> =
        timelineCardData.statsFlow(eventId, viewModelScope)

    // -- Engagement contributor accessors (delegates to MES indexes) ------------

    fun zapDetailsForEvent(eventId: String): List<ZapDetail> =
        timelineCardData.zapDetailsForEvent(eventId)

    fun repostPubkeysForEvent(eventId: String): List<String> =
        timelineCardData.repostPubkeysForEvent(eventId)

    fun reactionsForEvent(eventId: String): List<ReactionInfo> =
        timelineCardData.reactionsForEvent(eventId)

    // -- User relay sets (kind-30002) ------------------------------------------

    val userSetsFlow: StateFlow<List<RelaySet>> =
        keyManager.getPublicKeyHex()?.let { pk ->
            memoryEventStore.getAllSetsFlow(pk)
                // Eagerly, NOT WhileSubscribed: the end-of-restore relay-set signal bump fires
                // while the UI subscriber may not yet be collecting; WhileSubscribed would drop the
                // upstream and lose the bump, leaving the slide-up empty until a later relay fetch
                // happens to coincide with a live subscriber. Eagerly keeps the upstream alive.
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
        } ?: MutableStateFlow(emptyList())

    // -- Carousel single-relay feeds = the user's kind-10012 favorite relays ---
    // Sourced LIVE from MES (not a separate pinned-relay store): favoriting a relay anywhere —
    // relay settings, the §05 detail star, discovery's Add — surfaces it in the feed slide-up
    // immediately, and un-favoriting removes it. The old local pinnedRelays store is retired.

    val pinnedRelays: StateFlow<List<FeedType.SingleRelay>> =
        keyManager.getPublicKeyHex()?.let { pk ->
            memoryEventStore.favoriteRelayConfigsFlow(pk)
                .map { favs -> favs.mapNotNull { it.url }.map { url -> FeedType.SingleRelay(url, feedRelayLabel(url)) } }
                // Eagerly (see userSetsFlow): never lapse, so the end-of-restore config-signal
                // bump isn't lost during cold-start.
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
        } ?: MutableStateFlow(emptyList())

    private fun feedRelayLabel(url: String): String =
        url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/")

    // -- Carousel navigation ---------------------------------------------------

    val feedTypeLabel: String get() = when (val t = _feedType.value) {
        is FeedType.Global    -> "Global"
        is FeedType.Following -> "Following"
        is FeedType.RelaySet  -> t.name
        is FeedType.SingleRelay -> t.displayLabel
    }

    /** True when there are queued new posts (used by nav bar dot indicator). */
    val hasNewTopPost: Boolean get() = showDot.value

    /** Clear the new-posts indicator (e.g. when user taps the feed tab).
     *  Sets _isAtTop = true immediately so live-tail events merge into
     *  _events during the animateScrollToItem(0) animation — prevents
     *  the blue dot from flickering back while the scroll is in flight. */
    fun clearNewTopPost() = setAtTop(true)

    // -- Warm-zone hydration ---------------------------------------------------

    private data class HydrationViewport(
        val first: Int,
        val last: Int,
        val cardWidthPx: Int,
    )

    private val _hydrationViewport = MutableStateFlow(HydrationViewport(0, 0, 0))

    init {
        viewModelScope.launch(Dispatchers.Default) {
            combine(feedRows, _hydrationViewport) { rows, viewport -> rows to viewport }
                .conflate()
                .collect { (rows, viewport) ->
                    if (rows.isEmpty() || viewport.cardWidthPx <= 0) return@collect
                    val first = viewport.first.coerceIn(0, rows.lastIndex)
                    val last = viewport.last.coerceAtLeast(first).coerceAtMost(rows.lastIndex)
                    val lookahead = if (_feedType.value is FeedType.Following) {
                        ASSET_WARM_LOOKAHEAD
                    } else {
                        ASSET_WARM_LOOKAHEAD_CHURNY
                    }
                    val start = (first - ASSET_WARM_ABOVE).coerceAtLeast(0)
                    val end = (last + 1 + lookahead).coerceAtMost(rows.size)
                    if (start >= end) return@collect

                    val visibleEnd = (last + 1).coerceAtMost(end)
                    val warmRows = buildList {
                        addAll(rows.subList(first, visibleEnd))
                        if (start < first) addAll(rows.subList(start, first))
                        if (visibleEnd < end) addAll(rows.subList(visibleEnd, end))
                    }
                    cardHydrator.warmUpcomingAssets(
                        events = warmRows,
                        cardWidthPx = viewport.cardWidthPx,
                        maxRows = ASSET_WARM_MAX_ROWS,
                        maxImagePrefetches = ASSET_WARM_IMAGE_CAP,
                        maxOgFetches = ASSET_WARM_OG_CAP,
                    )
                }
        }

        @OptIn(FlowPreview::class)
        viewModelScope.launch(Dispatchers.Default) {
            combine(feedRows, _hydrationViewport) { rows, viewport -> rows to viewport }
                .debounce(300L)  // Fling guard: only fires after 300ms of no viewport changes
                .collectLatest { (rows, viewport) ->
                    if (rows.isEmpty()) return@collectLatest
                    val first = viewport.first.coerceIn(0, rows.lastIndex)
                    val last = viewport.last.coerceAtLeast(first).coerceAtMost(rows.lastIndex)
                    val warmBelow = if (_feedType.value is FeedType.Following) WARM_ZONE_BELOW else WARM_ZONE_BELOW_CHURNY
                    val zoneStart = (first - WARM_ZONE_ABOVE).coerceAtLeast(0)
                    val zoneEnd = (first + warmBelow).coerceAtMost(rows.size)
                    if (zoneStart >= zoneEnd) return@collectLatest
                    val warmRows = rows.subList(zoneStart, zoneEnd)
                    val vpStart = (first - zoneStart).coerceAtLeast(0)
                    val actualVpEnd = (last - zoneStart + 1).coerceAtLeast(vpStart + VIEWPORT_SIZE)
                    val vpEnd = actualVpEnd.coerceAtMost(warmRows.size)
                    val lookahead = if (_feedType.value is FeedType.Following) ENGAGEMENT_LOOKAHEAD else ENGAGEMENT_LOOKAHEAD_CHURNY
                    val engEnd = (vpEnd + lookahead).coerceAtMost(warmRows.size)
                    val viewportIds = warmRows.subList(vpStart, engEnd).map { it.id }.toSet()
                    if (warmRows.isNotEmpty()) {
                        requestVisibleWotHydration(warmRows)
                        cardHydrator.hydrateVisibleCards(warmRows, viewportIds = viewportIds)
                    }
                }
        }
    }

    private fun requestVisibleWotHydration(rows: List<FeedRow>) {
        val subjects = wotSubjectsForFeedRows(rows, modelProvider = memoryEventStore::getEventModel)
        _wotSubjects.value = subjects
        wotHydrationCoalescer.requestHydration(subjects)
    }

    fun requestWotHydration(pubkeys: Collection<String>) {
        if (pubkeys.isEmpty()) return
        _wotSubjects.update { current -> current + pubkeys }
        wotHydrationCoalescer.requestHydration(pubkeys)
    }

    // ── Subscription lifecycle (mirrors Jumble NoteList useEffect) ────────────

    /**
     * Single resubscribe entry point. Called by the deps collector in init{}.
     *
     * Mirrors Jumble NoteList's useEffect on [subRequests, refreshCount, ...]:
     *   1. Close prior handle (= effect cleanup)
     *   2. Reset state ONLY on actual feed switch (= React component remount via key)
     *   3. Capture `since` from existing events (= jumble's `const since = events[0]?.created_at`)
     *   4. Subscribe; route batched events via handleBatch, live-tail via handleNew
     */
    private suspend fun setupSubscription(key: ResubKey, resetView: Boolean) {
        refreshTimeoutJob?.cancel()
        lastFeedType = key.type

        // Manage FEED_SUB tag: remove from previous, add to new.
        val newSingleRelayUrl = (key.type as? FeedType.SingleRelay)?.url?.let { normalizeRelayUrl(it) }
        val prev = feedSubPersistentUrl
        if (prev != null && prev != newSingleRelayUrl) {
            relayPool.removePurpose(prev, ConnectionPurpose.FEED_SUB)
        }
        if (newSingleRelayUrl != null && newSingleRelayUrl != prev) {
            relayPool.addPurpose(newSingleRelayUrl, ConnectionPurpose.FEED_SUB)
        }
        feedSubPersistentUrl = newSingleRelayUrl

        relayPool.activeSingleRelayFeedUrl = newSingleRelayUrl

        feedTrace { "setupSubscription: type=${key.type} ver=${key.ver} refresh=${key.refresh}" }

        // Close previous handle (= useEffect cleanup)
        val hadActiveHandle = currentHandle != null
        currentHandle?.close()
        currentHandle = null

        // Pre-load MES cached events for instant render (mirrors Jumble's
        // setStoredEvents(fromIndexedDB) and the original resubscribe() flow).
        lastActivitySweepKey = null
        val cachedEvents = loadCachedEvents(key.type, key.filter)
        if (resetView) {
            _events.value = cachedEvents
            _newEvents.value = emptyList()
            _liveArrivalIds.value = emptySet()
            setAtTop(true)
        } else {
            // Background metaVer resubscribe: widen relay coverage, keep scroll position
            _events.update { TimelineMerge.merge(it, cachedEvents) }
        }

        val subRequests = buildSubRequests(key.type, key.filter)
        if (subRequests.isEmpty()) {
            _isLoading.value = false
            feedTrace { "setupSubscription: no subRequests, idle" }
            return
        }

        _isLoading.value = (resetView || !hadActiveHandle) &&
            cachedEvents.isEmpty() &&
            _events.value.isEmpty()

        // Admission gate for relay batches arriving after subscription starts.
        // resetView=true (user-initiated switch): null → TimelineMerge.merge handles
        //   dedup; no since filter rejects events the cache may have missed.
        // resetView=false (metaVer resub): head-since from current _events — relay
        //   data newer than this merges on top, older is skipped (already displayed).
        // Clamped to now — defense against poisoned future-dated events in snapshot.
        val since: Long? = if (resetView) null else _events.value.firstOrNull()?.createdAt
            ?.coerceAtMost(System.currentTimeMillis() / 1000L)

        currentHandle = timelineService.subscribeTimeline(
            subRequests = subRequests,
            onEvents = { batch, eosed ->
                if (_isRefreshing.value && (batch.isNotEmpty() || eosed)) {
                    _isRefreshing.value = false
                    refreshTimeoutJob?.cancel()
                }
                handleBatch(batch, eosed, since)
            },
            onNew    = { event -> handleNew(event) },
        )
        feedTrace { "setupSubscription: started subs=${subRequests.size} since=$since cached=${cachedEvents.size} events=${_events.value.size}" }

        if (_isRefreshing.value) {
            refreshTimeoutJob = viewModelScope.launch {
                delay(8_000)
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Mirrors Jumble NoteList:onEvents:
     *   if (events.length > 0) {
     *     if (!since)  setEvents(events)              // bulk replace
     *     else         handleNewEvents(filtered)      // refresh path
     *   }
     *   if (eosed)    setInitialLoading(false)
     */
    private fun handleBatch(batch: List<NostrEvent>, eosed: Boolean, since: Long?) {
        feedTrace { "handleBatch: size=${batch.size} eosed=$eosed since=$since current=${_events.value.size}" }
        if (batch.isNotEmpty()) {
            if (since == null) {
                _events.update { current -> TimelineMerge.merge(current, batch) }
            } else {
                val newer = batch.filter { it.createdAt >= since }
                if (newer.isNotEmpty()) {
                    handleNewBatch(newer)
                }
            }
        }
        if (eosed) {
            _isLoading.value = false
        }
        if (_events.value.isNotEmpty()) {
            _isLoading.value = false
        }
    }

    /**
     * Mirrors Jumble NoteList:handleNewEvents for single live-tail events.
     *   - At top: merge into events (visible immediately)
     *   - Scrolled: buffer in newEvents (blue-dot)
     */
    private fun handleNew(event: NostrEvent) {
        if (_isAtTop.value) {
            _liveArrivalIds.update { it + event.id }
            _events.update { current -> TimelineMerge.merge(current, listOf(event)) }
        } else {
            _newEvents.update { current -> TimelineMerge.merge(current, listOf(event)) }
        }
    }

    /** Batch variant of handleNew for the since-filter path. */
    private fun handleNewBatch(newEvents: List<NostrEvent>) {
        if (_isAtTop.value) {
            _liveArrivalIds.update { it + newEvents.map { e -> e.id }.toSet() }
            _events.update { current -> TimelineMerge.merge(current, newEvents) }
        } else {
            _newEvents.update { current -> TimelineMerge.merge(current, newEvents) }
        }
    }

    // ── User actions ──────────────────────────────────────────────────────────

    fun onViewportChanged(idx: Int) {
        _hydrationViewport.value = HydrationViewport(idx, idx, 0)
    }

    fun onViewportChanged(first: Int, last: Int, cardWidthPx: Int) {
        _hydrationViewport.value = HydrationViewport(
            first = first.coerceAtLeast(0),
            last = last.coerceAtLeast(first),
            cardWidthPx = cardWidthPx.coerceAtLeast(0),
        )
    }

    /** Intent-based at-top setter — called from FeedScreen's gesture-driven
     *  snapshotFlow and from explicit user actions (Home tap, dot tap).
     *  Never called from idle layout changes (prepends). */
    fun setAtTop(atTop: Boolean) {
        val wasAtTop = _isAtTop.value
        _isAtTop.value = atTop
        if (atTop && !wasAtTop) flushPending()
    }

    fun onDotTapped() = setAtTop(true)

    private fun flushPending() {
        val pending = _newEvents.value
        if (pending.isEmpty()) return
        _liveArrivalIds.update { it + pending.map { e -> e.id }.toSet() }
        _events.update { current -> TimelineMerge.merge(current, pending) }
        _newEvents.value = emptyList()
    }

    fun loadMore() {
        if (_isLoadingMore.value) return
        val handle = currentHandle ?: return
        val until = _events.value.lastOrNull()?.createdAt ?: return
        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                val older = timelineService.fetchOlderTimeline(handle.timelineKey, until, 100)
                if (older.isNotEmpty()) {
                    _events.update { current -> TimelineMerge.merge(current, older, capTail = false) }
                }
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun refresh() {
        _refreshCounter.value = _refreshCounter.value + 1
    }

    private val _refreshCounter = MutableStateFlow(0)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private var refreshTimeoutJob: Job? = null
    private var lastRefreshAt = 0L

    /** Pull-to-refresh entry. Debounce collapses mashing; deliberate retries pass. */
    fun triggerRefresh() {
        val now = System.currentTimeMillis()
        if (_isRefreshing.value) return
        if (now - lastRefreshAt < REFRESH_DEBOUNCE_MS) return
        lastRefreshAt = now
        _isRefreshing.value = true
        _refreshCounter.value = _refreshCounter.value + 1
    }

    // -- Init: cold-start + feedType subscription ------------------------------

    init {
        feedTrace { "init: ownPubkey=${keyManager.getPublicKeyHex()?.take(8)}" }

        // Surface auth-unavailable for SingleRelay feeds
        viewModelScope.launch {
            relayPool.relayAuthUnavailable.collect { unavailableUrl ->
                val current = _feedType.value
                if (current is FeedType.SingleRelay &&
                    normalizeRelayUrl(current.url) == normalizeRelayUrl(unavailableUrl)) {
                    _authUnavailableRelay.value = unavailableUrl
                }
            }
        }

        val ownPubkey = keyManager.getPublicKeyHex()

        // Cold-start: figure out initial feed type FIRST (no resubscribe yet),
        // then the resubscribe collector fires once coldStartState leaves LOADING.
        viewModelScope.launch {
            if (ownPubkey != null) {
                // Fast path: snapshot already has follows — skip relay wait
                val snapshotFollows = memoryEventStore.getFollows(ownPubkey)
                if (snapshotFollows?.isNotEmpty() == true) {
                    _hasFollows.value = true
                    _feedType.value = FeedType.Following
                    _coldStartState.value = ColdStartState.READY_FOLLOWING
                    feedTrace { "cold-start: ${snapshotFollows.size} follows in snapshot -> Following" }
                } else {
                    // Slow path: wait for bootstrap's authoritative kind-3 attempt.
                    // Do not flash Global while follows are still unknown; keep the
                    // user's intent on Following and let the existing feed hydrate
                    // when the contact list arrives.
                    initGate.awaitFollows()
                    val follows = memoryEventStore.getFollows(ownPubkey)
                    if (follows == null) {
                        _feedType.value = FeedType.Following
                        _coldStartState.value = ColdStartState.READY_FOLLOWING
                        feedTrace { "cold-start: follows unresolved after bootstrap -> Following" }
                    } else if (follows.isEmpty()) {
                        _feedType.value = FeedType.Global
                        _coldStartState.value = ColdStartState.READY_GLOBAL
                        feedTrace { "cold-start: no follows -> Global" }
                    } else {
                        _hasFollows.value = true
                        // Wait briefly for own kind-10002 (best-effort, optional)
                        withTimeoutOrNull(2_000L) {
                            memoryEventStore.readWriteRelayConfigsFlow(ownPubkey)
                                .filter { it.isNotEmpty() }
                                .first()
                        }
                        _feedType.value = FeedType.Following
                        _coldStartState.value = ColdStartState.READY_FOLLOWING
                        feedTrace { "cold-start: ${follows.size} follows from relay -> Following" }
                    }
                }
            } else {
                _coldStartState.value = ColdStartState.READY_GLOBAL
            }
        }

        // Track follows reactively without changing the visible feed. If Following is
        // active, the subscription collector uses _followsVersion to resubscribe as
        // the contact list is resolved or edited. Feed-type switching remains driven
        // by startup default resolution or explicit user actions.
        if (ownPubkey != null) {
            viewModelScope.launch {
                memoryEventStore.followsFlow(ownPubkey).distinctUntilChanged().collect { follows ->
                    val hasFollows = follows.isNotEmpty()
                    _hasFollows.value = hasFollows
                    _followsVersion.value = _followsVersion.value + 1
                }
            }
        }

        // Subscription deps collector. contentFilter is intentionally NOT a
        // dep: Notes↔Conversations is a pure client-side projection in
        // feedRows via matchesContentFilter. Adding it would force a full
        // WS REQ/CLOSE round across all relays on every tab flip
        // (audit finding CG-R1). Pull-to-refresh (triggerRefresh) is the
        // explicit refresh mechanism.
        viewModelScope.launch {
            var prevKey: ResubKey? = null
            val feedSelection = combine(_feedType, _followsVersion) { type, followsVersion ->
                type to if (type is FeedType.Following) followsVersion else 0L
            }
            combine(
                _coldStartState,
                feedSelection,
                relayMetadataVersion,
                _refreshCounter,
                _filter,
            ) { state, selection, ver, refresh, filter ->
                val (type, followsVersion) = selection
                ResubKey(
                    state = state,
                    type = type,
                    followsVersion = followsVersion,
                    ver = ver,
                    refresh = refresh,
                    filter = filter,
                )
            }
                .filter { it.state != ColdStartState.LOADING }
                .distinctUntilChangedBy {
                    it.subscriptionIdentity
                }
                .collectLatest { key ->
                    val userInitiated = prevKey?.let {
                        key.type != it.type || key.filter != it.filter || key.refresh != it.refresh
                    } ?: true   // first emission = cold start = load at top
                    prevKey = key
                    setupSubscription(key, resetView = userInitiated)
                }
        }

        // One-shot: merge cached events when snapshot restore completes.
        viewModelScope.launch {
            memoryEventStore.snapshotRestoredFlow.filter { it > 0L }.first()
            val cached = loadCachedEvents(_feedType.value, _filter.value)
            if (cached.isNotEmpty() && _events.value.size < SNAPSHOT_MERGE_CEILING) {
                feedTrace { "snapshot restored: merging ${cached.size} cached events into ${_events.value.size} current" }
                _events.update { current -> TimelineMerge.merge(current, cached) }
            }
        }
    }

    private fun loadCachedEvents(type: FeedType, filter: FeedFilter): List<NostrEvent> {
        val kinds = filter.enabledKinds.toSet()
        if (kinds.isEmpty()) return emptyList()
        return when (type) {
            is FeedType.Following -> {
                val follows = keyManager.getPublicKeyHex()
                    ?.let { memoryEventStore.getFollows(it) }
                    ?: emptySet()
                if (follows.isEmpty()) emptyList()
                else memoryEventStore.eventsByAuthors(follows, kinds)
            }
            is FeedType.Global -> {
                val muteList = keyManager.getPublicKeyHex()
                    ?.let { memoryEventStore.getMuteList(it) }
                val hideSensitive = sensitiveContentMode.value == SensitiveContentMode.HIDE
                memoryEventStore.recentEventsWithDisplayableFloor(
                    kinds = kinds,
                    isDisplayable = { e -> !isMuted(e, muteList) && (!hideSensitive || !e.hasContentWarning) },
                )
            }
            is FeedType.SingleRelay -> memoryEventStore.eventsByRelay(type.url, kinds)
            is FeedType.RelaySet -> {
                val members = keyManager.getPublicKeyHex()
                    ?.let { memoryEventStore.getSetMembers(it, type.dTag) }
                    ?: emptySet()
                val urls = members.mapNotNull { normalizeRelayUrl(it) }
                if (urls.isEmpty()) emptyList()
                else {
                    urls.flatMap { memoryEventStore.eventsByRelay(it, kinds, 100) }
                        .distinctBy { it.id }
                        .sortedByDescending { it.createdAt }
                        .take(300)
                }
            }
        }
    }

    private fun buildSubRequests(type: FeedType, filter: FeedFilter): List<SubRequest> {
        val ownPubkey = keyManager.getPublicKeyHex()
        val blockedRelays = ownPubkey
            ?.let { memoryEventStore.getBlockedRelayUrls(it).toSet() }
            ?: emptySet()
        val readRelays = ownPubkey
            ?.let { memoryEventStore.getReadWriteRelayConfigs(it).map { c -> c.url } }
            ?: emptyList()
        // Narrow relays by the session feed filter where NIP-01 can express it
        // (kinds + since). FeedRows remains the source of truth because kind-1
        // media type and activity thresholds are local policy.
        val kinds = filter.enabledKinds
        if (kinds.isEmpty()) return emptyList()
        val filterSince = filter.sinceHours?.let { hours ->
            (System.currentTimeMillis() / 1000L) - hours * 60L * 60L
        }
        val config = OutboxRelayResolver.Config(
            kinds = kinds,
            limit = 300,
            since = filterSince,
        )
        return when (type) {
            is FeedType.Following -> {
                val follows = ownPubkey
                    ?.let { memoryEventStore.getFollows(it) }
                    ?: emptySet()
                if (follows.isEmpty()) return emptyList()
                outboxResolver.resolveFollowing(
                    authors = follows,
                    fallbackRelays = readRelays.ifEmpty { GLOBAL_RELAY_URLS },
                    blockedRelays = blockedRelays,
                    config = config,
                )
            }
            is FeedType.Global -> outboxResolver.resolveGlobal(
                readRelays = readRelays,
                fallbackRelays = GLOBAL_RELAY_URLS,
                blockedRelays = blockedRelays,
                config = config,
            )
            is FeedType.SingleRelay -> outboxResolver.resolveSingleRelay(
                url = type.url,
                config = config,
            )
            is FeedType.RelaySet -> {
                val members = ownPubkey
                    ?.let { memoryEventStore.getSetMembers(it, type.dTag) }
                    ?: emptySet()
                val setUrls = members.mapNotNull { normalizeRelayUrl(it) }
                    .filter { it !in blockedRelays }
                    .ifEmpty { readRelays.ifEmpty { GLOBAL_RELAY_URLS } }
                outboxResolver.resolveGlobal(
                    readRelays = setUrls,
                    fallbackRelays = GLOBAL_RELAY_URLS,
                    blockedRelays = blockedRelays,
                    config = config,
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        currentHandle?.close()
        currentHandle = null
        feedSubPersistentUrl?.let { relayPool.removePurpose(it, ConnectionPurpose.FEED_SUB) }
        feedSubPersistentUrl = null
        relayPool.activeSingleRelayFeedUrl = null
    }

    private fun matchesContentFilter(evt: NostrEvent, cf: FeedContentFilter): Boolean {
        // kind-6 AND kind-16 reposts carry a rootId (the reposted event) and so look
        // reply-like — they must count as roots for notes and be excluded from replies.
        val isRepostKind = evt.kind == 6 || evt.kind == 16
        return when (cf) {
            FeedContentFilter.NOTES_ONLY ->
                isRepostKind || (evt.replyToId == null && evt.rootId == null)
            FeedContentFilter.REPLIES_ONLY ->
                !isRepostKind && (evt.replyToId != null || evt.rootId != null)
        }
    }

    private fun matchesFeedFilterBeforeActivity(
        evt: NostrEvent,
        filter: FeedFilter,
        nowSec: Long,
    ): Boolean {
        if (evt.kind !in filter.enabledKinds) return false
        val model = if (evt.kind == 1 && filter.needsMediaFilter) {
            memoryEventStore.getEventModel(evt.id)
        } else {
            null
        }
        if (!matchesShowTypes(evt.kind, evt.content, model, filter)) return false
        val since = filter.sinceHours?.let { hours -> nowSec - hours * 60L * 60L }
        return since == null || evt.createdAt >= since
    }

    private fun matchesActivityThresholds(evt: NostrEvent, filter: FeedFilter): Boolean {
        if (!filter.hasActivityThresholds()) return true
        val stats = memoryEventStore.currentStatsSnapshot(evt.id)
        return stats.replyCount >= filter.minReplies &&
            stats.repostCount >= filter.minReposts &&
            stats.reactionCount >= filter.minReactions &&
            stats.zapTotalSats >= filter.minZapSats
    }

    private var lastActivitySweepKey: ActivitySweepKey? = null

    private fun requestActivityCandidateSweep(filter: FeedFilter, candidates: List<NostrEvent>) {
        if (!filter.hasActivityThresholds()) {
            lastActivitySweepKey = null
            return
        }
        val ids = candidates
            .asSequence()
            .take(ACTIVITY_SWEEP_CANDIDATE_LIMIT)
            .map { it.id }
            .toList()
        if (ids.isEmpty()) return

        val key = ActivitySweepKey(filter, ids)
        if (key == lastActivitySweepKey) return

        val rowsById = memoryEventStore.feedRowsByIds(ids.toSet()).associateBy { it.id }
        val eventsById = candidates.associateBy { it.id }
        val rows = ids.mapNotNull { id ->
            rowsById[id] ?: eventsById[id]?.let(memoryEventStore::synthesizeFeedRow)
        }
        if (rows.isEmpty()) return

        lastActivitySweepKey = key
        // Activity filters must not depend on viewport rendering: if a Popular
        // filter hides a row before it renders, the normal viewport-scoped
        // engagement hydration would never fetch that row's counts. Sweep a
        // bounded newest candidate window through the existing engagement
        // coalescer, while the filter predicate itself reads only MES's current
        // snapshots. The feed can fill in as stats arrive, without blocking UI
        // on hydration or deadlocking into an empty list.
        cardHydrator.hydrateEngagement(rows, first = 0, last = rows.lastIndex)
    }

    // ── Mute / Report actions ──────────────────────────────────────────────

    fun muteUser(pubkey: String): MuteResult = muteListRepository.muteUser(pubkey)

    fun reportEvent(eventId: String, authorPubkey: String, type: ReportType) =
        reportRepository.reportEvent(eventId, authorPubkey, type)

    private fun isMuted(evt: NostrEvent, muteList: MuteList?): Boolean {
        if (muteList == null) return false
        // Pubkey mute (public + private)
        if (evt.pubkey in muteList.pubkeys || evt.pubkey in muteList.privatePubkeys) return true
        // Event ID mute (public + private)
        if (evt.id in muteList.eventIds || evt.id in muteList.privateEventIds) return true
        // Word mute (public + private) — check against lowercased content.
        // Skip for kind-6 reposts: content is a JSON envelope (NIP-18), not user text.
        if (evt.kind != 6) {
            val lowerContent by lazy(LazyThreadSafetyMode.NONE) { evt.content.lowercase() }
            for (word in muteList.words) { if (lowerContent.contains(word)) return true }
            for (word in muteList.privateWords) { if (lowerContent.contains(word)) return true }
        }
        // Hashtag mute (public + private) — check t-tags on the event
        if (muteList.hashtags.isNotEmpty() || muteList.privateHashtags.isNotEmpty()) {
            for (tag in evt.tags) {
                if (tag.size >= 2 && tag[0] == "t") {
                    val ht = tag[1].lowercase()
                    if (ht in muteList.hashtags || ht in muteList.privateHashtags) return true
                }
            }
        }
        return false
    }

    private data class ResubKey(
        val state: ColdStartState,
        val type: FeedType,
        val followsVersion: Long,
        val ver: Int,
        val refresh: Int,
        val filter: FeedFilter,
    ) {
        val subscriptionIdentity: SubscriptionIdentity
            get() = SubscriptionIdentity(
                type = type,
                followsVersion = followsVersion,
                ver = ver,
                refresh = refresh,
                enabledKinds = filter.enabledKinds,
                sinceHours = filter.sinceHours,
            )
    }

    private data class SubscriptionIdentity(
        val type: FeedType,
        val followsVersion: Long,
        val ver: Int,
        val refresh: Int,
        val enabledKinds: List<Int>,
        val sinceHours: Int?,
    )

    private data class ActivitySweepKey(
        val filter: FeedFilter,
        val ids: List<String>,
    )

    private companion object {
        const val WARM_ZONE_ABOVE = 10
        const val WARM_ZONE_BELOW = 50
        /** Churny feeds (Global, SingleRelay) — narrower warm zone to reduce speculative fetches. */
        const val WARM_ZONE_BELOW_CHURNY = 30
        const val ASSET_WARM_ABOVE = 2
        const val ASSET_WARM_LOOKAHEAD = 12
        const val ASSET_WARM_LOOKAHEAD_CHURNY = 8
        const val ASSET_WARM_MAX_ROWS = 16
        const val ASSET_WARM_IMAGE_CAP = 4
        const val ASSET_WARM_OG_CAP = 4
        /** Approximate on-screen post count — engagement fetch scoped to this. */
        const val VIEWPORT_SIZE = 8
        const val ENGAGEMENT_LOOKAHEAD_CHURNY = 6
        const val ACTIVITY_SWEEP_CANDIDATE_LIMIT = 150
        const val FEED_DISPLAY_CAP = 500
        const val SNAPSHOT_MERGE_CEILING = 20
        /** Debounce window — collapses frantic mashing, not deliberate retries. */
        const val REFRESH_DEBOUNCE_MS = 1_500L
        /** Retain synthesized rows across slice swaps (pull-refresh, feed switch) so they hit
         *  instead of re-synthesizing. Covers the EVENTS_CAP window with headroom. */
        const val FEED_ROW_CACHE_SIZE = 1000
    }
}
