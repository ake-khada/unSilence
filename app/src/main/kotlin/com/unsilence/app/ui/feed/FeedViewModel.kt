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
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

private const val TAG = "FeedVM"

sealed class FeedType {
    data object Global    : FeedType()
    data object Following : FeedType()
    data class  RelaySet(val dTag: String, val name: String) : FeedType()
    data class  SingleRelay(val url: String, val label: String) : FeedType() {
        val displayLabel: String get() = when {
            url.contains("antiprimal.net/hot") -> "Popular"
            else -> label
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
 *   - Actions: setFeedType, setContentFilter, addPinnedRelay,
 *     removePinnedRelay, updateFilter,
 *     onViewportChanged, onDotTapped, loadMore, refresh
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

    fun clearLiveArrival(id: String) {
        _liveArrivalIds.update { it - id }
    }

    private var currentHandle: TimelineService.TimelineHandle? = null
    private var lastFeedType: FeedType? = null

    // -- Content filter (must be before feedRows which references it) ----------

    private val _contentFilter = MutableStateFlow(FeedContentFilter.NOTES_ONLY)
    val contentFilter: StateFlow<FeedContentFilter> = _contentFilter.asStateFlow()

    // ── feedRows derivation (incremental row cache) ────────────────────────────

    /** Per-feed-type row cache. Only IDs not in cache trigger feedRowsByIds. */
    private val feedRowCache = ConcurrentHashMap<String, FeedRow>()

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
                feedRowCache.clear()
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
                feedRowCache.clear()
                return@combine emptyList()
            }

            // Determine which IDs need fresh rows (not in cache)
            val displayedIds = displayed.map { it.id }.toSet()

            // Protect displayed events from LRU eviction — events in the
            // active timeline must stay in MES for EventModel resolution.
            memoryEventStore.markTouched(displayedIds)

            val missingIds = displayedIds.filter { !feedRowCache.containsKey(it) }
            val hitCount = displayedIds.size - missingIds.size
            Log.d(TAG, "feedRowCache hit=${hitCount} miss=${missingIds.size}")

            // Fetch only missing rows from MES
            if (missingIds.isNotEmpty()) {
                val missingSet = missingIds.toSet()
                val newRows = memoryEventStore.feedRowsByIds(missingSet)
                for (row in newRows) {
                    feedRowCache[row.id] = row
                }
                // Synthesize fallback for any still-missing (race with MES insert)
                for (id in missingIds) {
                    if (!feedRowCache.containsKey(id)) {
                        val evt = displayed.first { it.id == id }
                        feedRowCache[id] = memoryEventStore.synthesizeFeedRow(evt)
                    }
                }
            }

            // Evict cache entries no longer displayed
            val evictKeys = feedRowCache.keys.filter { it !in displayedIds }
            for (key in evictKeys) feedRowCache.remove(key)

            // Build ordered result from cache
            displayed.mapNotNull { evt -> feedRowCache[evt.id] }
        }
            .sample(FEED_SAMPLE_MS)
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
        _feedType.value = type
        if (_coldStartState.value == ColdStartState.LOADING) {
            _coldStartState.value = if (type is FeedType.Following)
                ColdStartState.READY_FOLLOWING else ColdStartState.READY_GLOBAL
        }
    }

    // -- Content filter --------------------------------------------------------

    fun setContentFilter(f: FeedContentFilter) {
        if (_contentFilter.value == f) return
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

    private val profileCache = ConcurrentHashMap<String, StateFlow<UserEntity?>>()

    fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
        profileCache.getOrPut(pubkey) {
            userRepository.userFlow(pubkey)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }

    // -- Per-event stats lookup (replyCount, reactionCount, etc.) -------------

    private val statsCache = ConcurrentHashMap<String, StateFlow<com.unsilence.app.data.memory.EventStats>>()

    fun statsFlow(eventId: String): StateFlow<com.unsilence.app.data.memory.EventStats> =
        statsCache.getOrPut(eventId) {
            memoryEventStore.statsFlow(eventId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.unsilence.app.data.memory.EventStats.EMPTY)
        }

    // -- User relay sets (kind-30002) ------------------------------------------

    val userSetsFlow: StateFlow<List<RelaySet>> =
        keyManager.getPublicKeyHex()?.let { pk ->
            memoryEventStore.getAllSetsFlow(pk)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        } ?: MutableStateFlow(emptyList())

    // -- Pinned relays (carousel) ----------------------------------------------

    val pinnedRelays: StateFlow<List<FeedType.SingleRelay>> =
        keyManager.getPublicKeyHex()?.let { pk ->
            relayPreferencesStore.pinnedRelaysFlow(pk)
                .map { list -> list.map { FeedType.SingleRelay(it.url, it.displayLabel ?: it.url) } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        } ?: MutableStateFlow(emptyList())

    fun addPinnedRelay(url: String, label: String) {
        val pk = keyManager.getPublicKeyHex() ?: return
        viewModelScope.launch {
            relayPreferencesStore.upsertPinnedRelay(pk, url, label)
        }
    }

    fun removePinnedRelay(url: String) {
        val pk = keyManager.getPublicKeyHex() ?: return
        viewModelScope.launch {
            relayPreferencesStore.deletePinnedRelay(pk, url)
        }
    }

    // -- Carousel navigation ---------------------------------------------------

    val feedTypeLabel: String get() = when (val t = _feedType.value) {
        is FeedType.Global    -> "Global"
        is FeedType.Following -> "Following"
        is FeedType.RelaySet  -> t.name
        is FeedType.SingleRelay -> t.displayLabel
    }

    /** True when there are queued new posts (used by nav bar dot indicator). */
    val hasNewTopPost: Boolean get() = showDot.value

    /** Clear the new-posts indicator (e.g. when user taps the feed tab). */
    fun clearNewTopPost() { onDotTapped() }

    // -- Warm-zone hydration ---------------------------------------------------

    private val _viewportFirstVisible = MutableStateFlow(0)

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch(Dispatchers.Default) {
            combine(events, _viewportFirstVisible) { events, first -> events to first }
                .debounce(300L)
                .collectLatest { (events, first) ->
                    if (events.isEmpty()) return@collectLatest
                    val zoneStart = (first - WARM_ZONE_ABOVE).coerceAtLeast(0)
                    val zoneEnd = (first + WARM_ZONE_BELOW).coerceAtMost(events.size)
                    if (zoneStart >= zoneEnd) return@collectLatest
                    val warmEvents = events.subList(zoneStart, zoneEnd)
                    val rows = memoryEventStore.feedRowsByIds(warmEvents.map { it.id }.toSet())
                    if (rows.isNotEmpty()) {
                        val feedRelay = (_feedType.value as? FeedType.SingleRelay)?.url
                        cardHydrator.hydrateVisibleCards(rows, feedRelay = feedRelay)
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
    private suspend fun setupSubscription(key: ResubKey) {
        refreshTimeoutJob?.cancel()
        lastFeedType = key.type
        relayPool.activeSingleRelayFeedUrl =
            (key.type as? FeedType.SingleRelay)?.url?.let { normalizeRelayUrl(it) }

        Log.d(TAG, "setupSubscription: type=${key.type} ver=${key.ver} refresh=${key.refresh}")

        // Close previous handle (= useEffect cleanup)
        currentHandle?.close()
        currentHandle = null

        // Pre-load MES cached events for instant render (mirrors Jumble's
        // setStoredEvents(fromIndexedDB) and the original resubscribe() flow).
        // Every resubscribe — feed switch, refresh, metaVer — reloads from MES.
        val cachedEvents = loadCachedEvents(key.type)
        _events.value = cachedEvents
        _newEvents.value = emptyList()
        _liveArrivalIds.value = emptySet()

        // Always fetch all feed kinds; Notes↔Conversations is client-side via feedRows.
        val subRequests = buildSubRequests(key.type)
        if (subRequests.isEmpty()) {
            _isLoading.value = false
            Log.d(TAG, "setupSubscription: no subRequests, idle")
            return
        }

        _isLoading.value = cachedEvents.isEmpty()

        // since from cached events head — relay data newer than this merges
        // on top; null → bulk replace on first onEvents call.
        // Clamped to now — defense against poisoned future-dated events in snapshot.
        val since: Long? = cachedEvents.firstOrNull()?.createdAt
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
        Log.d(TAG, "setupSubscription: started subs=${subRequests.size} since=$since cached=${cachedEvents.size} events=${_events.value.size}")

        if (_isRefreshing.value) {
            refreshTimeoutJob = viewModelScope.launch {
                delay(4_000)
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
        if (batch.isNotEmpty()) {
            if (since == null) {
                // First load — merge into current (which may be empty or
                // populated by a prior relay batch). The direct-assign path
                // `_events.value = sort(batch)` replaced whatever the previous
                // relay batch deposited, and when current was non-empty it
                // bypassed dedup — producing duplicate cards. merge() handles
                // dedup, sort, and cap uniformly.
                _events.update { current -> TimelineMerge.merge(current, batch) }
            } else {
                // Refresh / metaVer path — only newer events, route through live-tail handler.
                val newer = batch.filter { it.createdAt >= since }
                if (newer.isNotEmpty()) {
                    handleNewBatch(newer)
                }
            }
        }
        if (eosed) {
            _isLoading.value = false
        }
        // Also clear loading when we have events even without full EOSE
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
        val atTop = idx <= 0
        if (_isAtTop.value == atTop) return
        _isAtTop.value = atTop
        if (atTop) flushPending()
    }

    fun onDotTapped() = flushPending()

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
                val older = timelineService.loadMoreTimeline(handle.timelineKey, until, 100)
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

    /** Pull-to-refresh gesture entry point. */
    fun triggerRefresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        _refreshCounter.value = _refreshCounter.value + 1
    }

    // -- Init: cold-start + feedType subscription ------------------------------

    init {
        Log.d(TAG, "init: ownPubkey=${keyManager.getPublicKeyHex()?.take(8)}")

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
                    setupSubscription(key)
                }
        }

        // One-shot: merge cached events when snapshot restore completes.
        viewModelScope.launch {
            memoryEventStore.snapshotRestoredFlow.filter { it > 0L }.first()
            val cached = loadCachedEvents(_feedType.value)
            if (cached.isNotEmpty() && _events.value.size < SNAPSHOT_MERGE_CEILING) {
                Log.d(TAG, "snapshot restored: merging ${cached.size} cached events")
                _events.update { current -> TimelineMerge.merge(current, cached) }
            }
        }
    }

    private fun loadCachedEvents(type: FeedType): List<NostrEvent> {
        val kinds = setOf(1, 6, 20, 21, 30023)
        return when (type) {
            is FeedType.Following -> {
                val follows = keyManager.getPublicKeyHex()
                    ?.let { memoryEventStore.getFollows(it) }
                    ?: emptySet()
                if (follows.isEmpty()) emptyList()
                else memoryEventStore.eventsByAuthors(follows, kinds)
            }
            is FeedType.Global -> memoryEventStore.recentEvents(kinds)
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
        val kinds = listOf(1, 6, 20, 21, 30023)
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
        relayPool.activeSingleRelayFeedUrl = null
    }

    private fun matchesContentFilter(evt: NostrEvent, cf: FeedContentFilter): Boolean =
        when (cf) {
            FeedContentFilter.NOTES_ONLY ->
                evt.kind == 6 || (evt.replyToId == null && evt.rootId == null)
            FeedContentFilter.REPLIES_ONLY ->
                evt.kind != 6 && (evt.replyToId != null || evt.rootId != null)
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
        // Word mute (public + private) — check against lowercased content
        val lowerContent by lazy(LazyThreadSafetyMode.NONE) { evt.content.lowercase() }
        for (word in muteList.words) { if (lowerContent.contains(word)) return true }
        for (word in muteList.privateWords) { if (lowerContent.contains(word)) return true }
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
        const val WARM_ZONE_BELOW = 30
        const val FEED_DISPLAY_CAP = 500
        const val FEED_SAMPLE_MS = 100L
        const val SNAPSHOT_MERGE_CEILING = 20
    }
}
