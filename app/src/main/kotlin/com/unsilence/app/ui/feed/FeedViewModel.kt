package com.unsilence.app.ui.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.data.memory.RelaySet
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.relay.CardHydrator
import com.unsilence.app.data.relay.ConnectionPurpose
import com.unsilence.app.data.relay.ENGAGEMENT_LOOKAHEAD
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.OutboxRelayResolver
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.relay.SubRequest
import com.unsilence.app.data.relay.TimelineMerge
import com.unsilence.app.data.relay.TimelineService
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.model.ReportType
import com.unsilence.app.data.repository.MuteListRepository
import com.unsilence.app.data.repository.MuteResult
import com.unsilence.app.data.repository.ReportRepository
import com.unsilence.app.data.repository.UserRepository
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

sealed class FeedType {
    data object Global    : FeedType()
    data object Following : FeedType()
    data class  RelaySet(val dTag: String, val name: String) : FeedType()
    data class  SingleRelay private constructor(val url: String, val label: String) : FeedType() {
        val displayLabel: String get() = when {
            url.contains("antiprimal.net/hot") -> "Popular"
            else -> label
        }
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
    private val relayPreferencesStore: RelayPreferencesStore,
    private val cardHydrator: CardHydrator,
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

    // ── feedRows derivation (incremental row cache) ────────────────────────────

    /** Bounded row cache — retains rows across slice swaps so pull-refresh hits instead of re-synth. */
    private val feedRowCache = androidx.collection.LruCache<String, FeedRow>(FEED_ROW_CACHE_SIZE)

    val sensitiveContentMode: StateFlow<SensitiveContentMode> =
        relayPreferencesStore.sensitiveContentModeFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, SensitiveContentMode.BLUR)

    val feedRows: StateFlow<List<FeedRow>> =
        combine(
            _events,
            _contentFilter,
            memoryEventStore.ownMuteListFlow(),
            relayPreferencesStore.sensitiveContentModeFlow(),
        ) { events, cf, muteList, scm ->
            if (events.isEmpty()) {
                feedRowCache.evictAll()
                return@combine emptyList()
            }
            val hideSensitive = scm == SensitiveContentMode.HIDE
            val displayed = events.asSequence()
                .filter { matchesContentFilter(it, cf) }
                .filter { !isMuted(it, muteList) }
                .filter { !hideSensitive || !it.hasContentWarning }
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
            Log.d(TAG, "feedRowCache hit=${hitCount} miss=${missingIds.size}")

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

    private val _feedType = MutableStateFlow<FeedType>(FeedType.Global)
    val feedType: StateFlow<FeedType> = _feedType.asStateFlow()

    fun setFeedType(type: FeedType) {
        Log.w(TAG, "setFeedType: ${_feedType.value} → $type")
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
        Log.w(TAG, "setContentFilter: ${_contentFilter.value} → $f")
        _contentFilter.value = f
        // Pure client-side projection — feedRows recomposes via its own
        // combine on _contentFilter. NO subscription restart. See CG-R1.
    }

    // -- Filter (kinds, dates, etc -- for filter sheet) ------------------------

    private val _filter = MutableStateFlow(com.unsilence.app.domain.model.FeedFilter())
    val filterFlow: StateFlow<com.unsilence.app.domain.model.FeedFilter> = _filter.asStateFlow()

    fun updateFilter(filter: com.unsilence.app.domain.model.FeedFilter) {
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

    // -- User avatar -----------------------------------------------------------

    val userAvatarUrl: StateFlow<String?> = keyManager.getPublicKeyHex()?.let { pubkey ->
        userRepository.userFlow(pubkey)
            .map { it?.picture }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    } ?: MutableStateFlow(null)

    // -- Profile lookup for repost original authors ----------------------------

    private val profileCache = androidx.collection.LruCache<String, StateFlow<UserEntity?>>(500)

    fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
        synchronized(profileCache) {
            profileCache.get(pubkey) ?: userRepository.userFlow(pubkey)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
                .also { profileCache.put(pubkey, it) }
        }

    // -- Per-event stats lookup (replyCount, reactionCount, etc.) -------------

    private val statsCache = androidx.collection.LruCache<String, StateFlow<com.unsilence.app.data.memory.EventStats>>(500)

    fun statsFlow(eventId: String): StateFlow<com.unsilence.app.data.memory.EventStats> =
        synchronized(statsCache) {
            statsCache.get(eventId) ?: memoryEventStore.statsFlow(eventId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), memoryEventStore.currentStatsSnapshot(eventId))
                .also { statsCache.put(eventId, it) }
        }

    // -- Engagement contributor accessors (delegates to MES indexes) ------------

    fun zapDetailsForEvent(eventId: String): List<com.unsilence.app.data.memory.ZapDetail> =
        memoryEventStore.zapDetailsForEvent(eventId)

    fun repostPubkeysForEvent(eventId: String): List<String> =
        memoryEventStore.repostPubkeysForEvent(eventId)

    fun reactionsForEvent(eventId: String): List<com.unsilence.app.data.memory.ReactionInfo> =
        memoryEventStore.reactionsForEvent(eventId)

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

    private val _viewportFirstVisible = MutableStateFlow(0)

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch(Dispatchers.Default) {
            combine(events, _viewportFirstVisible) { events, first -> events to first }
                .debounce(300L)  // Fling guard: only fires after 300ms of no viewport changes
                .collectLatest { (events, first) ->
                    if (events.isEmpty()) return@collectLatest
                    val warmBelow = if (_feedType.value is FeedType.Following) WARM_ZONE_BELOW else WARM_ZONE_BELOW_CHURNY
                    val zoneStart = (first - WARM_ZONE_ABOVE).coerceAtLeast(0)
                    val zoneEnd = (first + warmBelow).coerceAtMost(events.size)
                    if (zoneStart >= zoneEnd) return@collectLatest
                    val warmEvents = events.subList(zoneStart, zoneEnd)
                    val vpStart = (first - zoneStart).coerceAtLeast(0)
                    val vpEnd = (vpStart + VIEWPORT_SIZE).coerceAtMost(warmEvents.size)
                    val lookahead = if (_feedType.value is FeedType.Following) ENGAGEMENT_LOOKAHEAD else ENGAGEMENT_LOOKAHEAD_CHURNY
                    val engEnd = (vpEnd + lookahead).coerceAtMost(warmEvents.size)
                    val viewportIds = warmEvents.subList(vpStart, engEnd).map { it.id }.toSet()
                    // Reuse rows the feedRows pipeline already derived (feedRowCache
                    // backs feedRows) instead of a redundant MES feedRowsByIds scan.
                    // Warm-zone events filtered out of the display list (mute /
                    // contentFilter / sensitive) miss the cache — synthesize those so
                    // hydration coverage is unchanged. The hydrator only reads
                    // immutable event fields (id/kind/content/createdAt/rootId),
                    // never author/stat columns, so row staleness is irrelevant.
                    val rows = warmEvents.map { evt ->
                        feedRowCache.get(evt.id) ?: memoryEventStore.synthesizeFeedRow(evt)
                    }
                    if (rows.isNotEmpty()) {
                        val feedRelay = (_feedType.value as? FeedType.SingleRelay)?.url
                        cardHydrator.hydrateVisibleCards(rows, feedRelay = feedRelay, viewportIds = viewportIds)
                    }
                }
        }
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

        Log.w(TAG, "setupSubscription: type=${key.type} ver=${key.ver} refresh=${key.refresh}")

        // Close previous handle (= useEffect cleanup)
        currentHandle?.close()
        currentHandle = null

        // Pre-load MES cached events for instant render (mirrors Jumble's
        // setStoredEvents(fromIndexedDB) and the original resubscribe() flow).
        val cachedEvents = loadCachedEvents(key.type)
        if (resetView) {
            _events.value = cachedEvents
            _newEvents.value = emptyList()
            _liveArrivalIds.value = emptySet()
            setAtTop(true)
        } else {
            // Background metaVer resubscribe: widen relay coverage, keep scroll position
            _events.update { TimelineMerge.merge(it, cachedEvents) }
        }

        // Always fetch all feed kinds; Notes↔Conversations is client-side via feedRows.
        val subRequests = buildSubRequests(key.type)
        if (subRequests.isEmpty()) {
            _isLoading.value = false
            Log.d(TAG, "setupSubscription: no subRequests, idle")
            return
        }

        _isLoading.value = resetView && cachedEvents.isEmpty()

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
        Log.w(TAG, "setupSubscription: started subs=${subRequests.size} since=$since cached=${cachedEvents.size} events=${_events.value.size}")

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
        Log.w(TAG, "handleBatch: size=${batch.size} eosed=$eosed since=$since current=${_events.value.size}")
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
        _viewportFirstVisible.value = idx
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
        Log.d(TAG, "init: ownPubkey=${keyManager.getPublicKeyHex()?.take(8)}")

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
                    Log.d(TAG, "cold-start: ${snapshotFollows.size} follows in snapshot -> Following")
                } else {
                    // Slow path: wait briefly for relay-fetched follows
                    val follows = withTimeoutOrNull(3_000L) {
                        memoryEventStore.followsFlow(ownPubkey)
                            .filter { it.isNotEmpty() }
                            .first()
                    }
                    if (follows == null || follows.isEmpty()) {
                        _feedType.value = FeedType.Global
                        _coldStartState.value = ColdStartState.READY_GLOBAL
                        Log.d(TAG, "cold-start: no follows -> Global")
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
                        Log.d(TAG, "cold-start: ${follows.size} follows from relay -> Following")
                    }
                }
            } else {
                _coldStartState.value = ColdStartState.READY_GLOBAL
            }
        }

        // Track follows count reactively (orthogonal to cold-start)
        if (ownPubkey != null) {
            viewModelScope.launch {
                memoryEventStore.followsFlow(ownPubkey).map { it.size }.collect { count ->
                    _hasFollows.value = count > 0
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
            combine(
                _coldStartState,
                _feedType,
                relayMetadataVersion,
                _refreshCounter,
                _filter,
            ) { state, type, ver, refresh, filter ->
                ResubKey(
                    state = state,
                    type = type,
                    ver = ver,
                    refresh = refresh,
                    filter = filter,
                )
            }
                .filter { it.state != ColdStartState.LOADING }
                .distinctUntilChangedBy {
                    Triple(it.type, it.ver to it.refresh, it.filter)
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
            val cached = loadCachedEvents(_feedType.value)
            if (cached.isNotEmpty() && _events.value.size < SNAPSHOT_MERGE_CEILING) {
                Log.w(TAG, "snapshot restored: merging ${cached.size} cached events into ${_events.value.size} current")
                _events.update { current -> TimelineMerge.merge(current, cached) }
            }
        }
    }

    private fun loadCachedEvents(type: FeedType): List<NostrEvent> {
        val kinds = setOf(1, 6, 16, 20, 21, 30023)
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

    private fun buildSubRequests(type: FeedType): List<SubRequest> {
        val ownPubkey = keyManager.getPublicKeyHex()
        val blockedRelays = ownPubkey
            ?.let { memoryEventStore.getBlockedRelayUrls(it).toSet() }
            ?: emptySet()
        val readRelays = ownPubkey
            ?.let { memoryEventStore.getReadWriteRelayConfigs(it).map { c -> c.url } }
            ?: emptyList()
        // Always REQ the full feed kind set. Notes↔Conversations is a
        // client-side projection in feedRows via matchesContentFilter
        // (audit finding CG-R1). The bandwidth cost of fetching reposts/
        // articles/imeta-pictures the Conversations tab doesn't display
        // is bounded by the 300-event limit.
        val kinds = listOf(1, 6, 16, 20, 21, 30023)
        val config = OutboxRelayResolver.Config(
            kinds = kinds,
            limit = 300,
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
        val ver: Int,
        val refresh: Int,
        val filter: com.unsilence.app.domain.model.FeedFilter,
    )

    private companion object {
        const val WARM_ZONE_ABOVE = 10
        const val WARM_ZONE_BELOW = 50
        /** Churny feeds (Global, SingleRelay) — narrower warm zone to reduce speculative fetches. */
        const val WARM_ZONE_BELOW_CHURNY = 30
        /** Approximate on-screen post count — engagement fetch scoped to this. */
        const val VIEWPORT_SIZE = 8
        const val ENGAGEMENT_LOOKAHEAD_CHURNY = 6
        const val FEED_DISPLAY_CAP = 500
        const val SNAPSHOT_MERGE_CEILING = 20
        /** Debounce window — collapses frantic mashing, not deliberate retries. */
        const val REFRESH_DEBOUNCE_MS = 1_500L
        /** Retain synthesized rows across slice swaps (pull-refresh, feed switch) so they hit
         *  instead of re-synthesizing. Covers the EVENTS_CAP window with headroom. */
        const val FEED_ROW_CACHE_SIZE = 1000
    }
}
