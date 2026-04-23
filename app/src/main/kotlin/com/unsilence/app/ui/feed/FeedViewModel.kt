package com.unsilence.app.ui.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.RelaySet
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.relay.ConnectionPurpose
import com.unsilence.app.data.relay.CoverageIntent
import com.unsilence.app.data.relay.CoverageStatus
import com.unsilence.app.data.relay.OutboxRouter
import com.unsilence.app.data.relay.RelayBrowseSession
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.cache.CoverageTracker
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.memory.FeedFilter as MemoryFeedFilter
import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.domain.model.ShowType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.unsilence.app.data.memory.UserEntity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

sealed class FeedType {
    data object Global    : FeedType()
    data object Following : FeedType()
    data class  RelaySet(val dTag: String, val name: String) : FeedType()
    data class  SingleRelay(val url: String, val label: String) : FeedType() {
        /** User-facing name — friendly aliases for known relay URLs. */
        val displayLabel: String get() = when {
            url.contains("antiprimal.net/hot") -> "Popular"
            else -> label
        }
    }

    companion object {
        /** Built-in Popular feed — always present in carousel. */
        val Popular = SingleRelay("wss://antiprimal.net/hot", "Popular")
    }
}

enum class FeedContentFilter(val value: Int) {
    NOTES_ONLY(1),
    REPLIES_ONLY(2),
}

data class FeedUiState(
    val loading: Boolean = true,
    val coverageStatus: CoverageStatus = CoverageStatus.NEVER_FETCHED,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FeedViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val relayPool: RelayPool,
    private val outboxRouter: OutboxRouter,
    private val browseSession: RelayBrowseSession,
    private val coverageTracker: CoverageTracker,
    private val keyManager: KeyManager,
    private val relayPreferencesStore: RelayPreferencesStore,
    private val memoryEventStore: MemoryEventStore,
    private val feedWindowLoader: FeedWindowLoader,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    private val _feedType = MutableStateFlow<FeedType>(FeedType.Global)
    val feedType: StateFlow<FeedType> = _feedType.asStateFlow()

    enum class ColdStartState { LOADING, READY_FOLLOWING, READY_GLOBAL }

    private val _coldStartState = MutableStateFlow(ColdStartState.LOADING)
    val coldStartState: StateFlow<ColdStartState> = _coldStartState.asStateFlow()

    /** Backward-compat alias — AppNavigation consumes this to gate bar visibility. */
    val splashDone: StateFlow<Boolean> = _coldStartState
        .map { it != ColdStartState.LOADING }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** All relay sets (NIP-51 kind 30002) for the dropdown. */
    val userSetsFlow: StateFlow<List<RelaySet>> =
        keyManager.getPublicKeyHex()?.let { pk ->
            memoryEventStore.getAllSetsFlow(pk)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        } ?: MutableStateFlow(emptyList())

    /** Favorite relays pinned to the feed picker — backed by DataStore for persistence. */
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

    private val _filter = MutableStateFlow(FeedFilter())
    val filterFlow: StateFlow<FeedFilter> = _filter.asStateFlow()

    private val _contentFilter = MutableStateFlow(FeedContentFilter.NOTES_ONLY)
    val contentFilter: StateFlow<FeedContentFilter> = _contentFilter.asStateFlow()

    fun setContentFilter(f: FeedContentFilter) { _contentFilter.value = f }

    /** Signed-in user's avatar URL, for nav icons. */
    val userAvatarUrl: StateFlow<String?> = keyManager.getPublicKeyHex()?.let { pubkey ->
        userRepository.userFlow(pubkey)
            .map { it?.picture }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    } ?: MutableStateFlow(null)

    private val _displayLimit = MutableStateFlow(FeedWindowConfig.WINDOW_SIZE)

    fun updateFilter(filter: FeedFilter) { _filter.value = filter }

    // ── Feed-state reducer ────────────────────────────────────────────────
    private var lastResetFeedKey: String? = null
    private val _activeReducer = MutableStateFlow(FeedStateReducer("global"))

    /** Automatic propagation: swap reducer → flatMapLatest picks up new state. */
    val reducerState: StateFlow<ReducerState> = _activeReducer
        .flatMapLatest { it.state }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReducerState())

    fun onScrollPositionChanged(firstVisibleIndex: Int, firstVisibleOffset: Int) {
        _activeReducer.value.onScrollPositionChanged(firstVisibleIndex, firstVisibleOffset)
    }

    fun onDotTapped() {
        _activeReducer.value.onDotTapped()
    }

    /** True when there are queued new posts (used by nav bar dot indicator). */
    val hasNewTopPost: Boolean get() = reducerState.value.showDot

    /** Clear the new-posts indicator (e.g. when user taps the feed tab). */
    fun clearNewTopPost() { onDotTapped() }

    // created_at of the last item when loadMore() last fired; guards duplicate page fetches.
    private var lastOldestTimestamp = 0L
    private var lastLoadMoreTime = 0L

    // Log dedup: only log feed emissions when size or boundary IDs change
    private var lastLoggedEmissionSig: Triple<Int, String?, String?> = Triple(0, null, null)

    // ── Per-feed saved state (scroll position + displayLimit) ────────────
    private data class SavedFeedState(
        val displayLimit: Int = 50,
        val lastOldestTimestamp: Long = 0L,
        val scrollIndex: Int = 0,
        val scrollOffset: Int = 0,
    )

    private val savedFeedStates = mutableMapOf<String, SavedFeedState>()
    private var currentFeedKey = ""

    // Scroll position — written by FeedScreen, read when saving feed state
    private val _savedScrollIndex = MutableStateFlow(0)
    private val _savedScrollOffset = MutableStateFlow(0)

    fun saveScrollPosition(index: Int, offset: Int) {
        _savedScrollIndex.value = index
        _savedScrollOffset.value = offset
    }

    // Scroll restore — set on feed switch when saved state exists
    private val _restoreScrollIndex = MutableStateFlow(0)
    private val _restoreScrollOffset = MutableStateFlow(0)
    private val _restoreGeneration = MutableStateFlow(0)
    val restoreScrollIndex: StateFlow<Int> = _restoreScrollIndex.asStateFlow()
    val restoreScrollOffset: StateFlow<Int> = _restoreScrollOffset.asStateFlow()
    val restoreGeneration: StateFlow<Int> = _restoreGeneration.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    // ── Profile lookup for repost original authors ──────────────────────
    private val profileCache = ConcurrentHashMap<String, StateFlow<UserEntity?>>()

    /**
     * Returns a cached StateFlow for the given pubkey's profile.
     * Used by LazyColumn items to resolve original author info on kind-6 reposts.
     * WhileSubscribed(5000) keeps the flow alive briefly when items scroll off-screen.
     */
    fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
        profileCache.getOrPut(pubkey) {
            userRepository.userFlow(pubkey)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }

    val feedTypeLabel: String get() = when (val t = _feedType.value) {
        is FeedType.Global    -> "Global"
        is FeedType.Following -> "Following"
        is FeedType.RelaySet    -> t.name
        is FeedType.SingleRelay -> t.displayLabel
    }

    /** Reactively tracks whether follows exist — used by buildFeedList and the feed sheet. */
    private val _hasFollows = MutableStateFlow(false)
    val hasFollows: StateFlow<Boolean> = _hasFollows.asStateFlow()

    /** Set to false when FeedScreen leaves composition (navigating to thread/profile/etc.) */
    private val _feedVisible = MutableStateFlow(true)

    fun setFeedVisible(visible: Boolean) {
        _feedVisible.value = visible
    }

    fun setFeedType(type: FeedType) {
        _feedType.value = type
        // Dismiss splash on manual feed switch (e.g. user taps Global during loading)
        if (_coldStartState.value == ColdStartState.LOADING) {
            _coldStartState.value = if (type is FeedType.Following)
                ColdStartState.READY_FOLLOWING else ColdStartState.READY_GLOBAL
        }
    }

    /** Ordered list of available feeds for cycling. */
    private fun buildFeedList(): List<FeedType> {
        val list = mutableListOf<FeedType>()
        if (_hasFollows.value) list.add(FeedType.Following)
        list.add(FeedType.Global)
        list.add(FeedType.Popular)
        for (relay in pinnedRelays.value) {
            if (relay.url == FeedType.Popular.url) continue  // already included as built-in
            list.add(relay)
        }
        for (set in (userSetsFlow.value)) {
            list.add(FeedType.RelaySet(set.dTag, set.title ?: set.dTag))
        }
        return list
    }

    private fun feedTypeMatches(a: FeedType, b: FeedType): Boolean = when {
        a is FeedType.Global && b is FeedType.Global -> true
        a is FeedType.Following && b is FeedType.Following -> true
        a is FeedType.RelaySet && b is FeedType.RelaySet -> a.dTag == b.dTag
        a is FeedType.SingleRelay && b is FeedType.SingleRelay -> a.url == b.url
        else -> false
    }

    fun nextFeed() {
        val list = buildFeedList()
        if (list.size <= 1) return
        val idx = list.indexOfFirst { feedTypeMatches(it, _feedType.value) }.coerceAtLeast(0)
        _feedType.value = list[(idx + 1) % list.size]
    }

    fun previousFeed() {
        val list = buildFeedList()
        if (list.size <= 1) return
        val idx = list.indexOfFirst { feedTypeMatches(it, _feedType.value) }.coerceAtLeast(0)
        _feedType.value = list[(idx - 1 + list.size) % list.size]
    }

    /** Trigger a re-fetch by toggling the feed type back to itself. */
    fun refresh() {
        val current = _feedType.value
        // Force a re-emission by setting to a different value and back
        _feedType.value = when (current) {
            is FeedType.Global -> FeedType.Following
            else -> FeedType.Global
        }
        _feedType.value = current
    }

    /**
     * Fetch events older than the current oldest item (pagination).
     * No-op if the oldest timestamp hasn't changed since the last fetch — avoids
     * hammering a relay that returned nothing or whose results haven't landed yet.
     * When Room does emit new older events the oldest timestamp shifts, which
     * naturally allows the next scroll trigger to fire a fresh fetch.
     */
    // Relay URLs currently used by the active feed — kept in sync with flatMapLatest.
    private var currentRelayUrls: List<String> = emptyList()

    fun loadMore() {
        val now = System.currentTimeMillis()
        if (now - lastLoadMoreTime < 1000) return  // 1s cooldown — immune to Flow resets
        if (_isLoadingMore.value) return  // coalesce redundant calls during fling

        // Pagination cursor is reducer-owned. Read it directly — no list walk.
        val oldest = _activeReducer.value.state.value.oldestCreatedAt
        if (oldest == Long.MAX_VALUE) return  // no events yet

        if (oldest == lastOldestTimestamp) return
        lastLoadMoreTime = now
        _isLoadingMore.value = true  // For spinner UI

        val type = _feedType.value
        // Don't advance lastOldestTimestamp or grow _displayLimit until
        // we know how many events arrived. Prevents 350→650 jolt on empty results
        // and allows retry when loadMore returns 0.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d("FeedViewModel", "loadMore (window): cursor=$oldest feedType=$type")
                val result = feedWindowLoader.loadMore(type, oldest)
                if (result.eventIds.isNotEmpty()) {
                    lastOldestTimestamp = oldest
                    _displayLimit.value = (_displayLimit.value + result.eventIds.size).coerceAtMost(3000)
                    feedWindowLoader.startEngagementRefresh(type, result.eventIds)
                    Log.d("FeedViewModel", "loadMore (window): +${result.eventIds.size} events, displayLimit=${_displayLimit.value}")
                } else {
                    Log.d("FeedViewModel", "loadMore (window): 0 events, end of history")
                }
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    /** Read kind-10002 read relays from MES, falling back to hardcoded defaults. */
    private fun resolveGlobalUrls(): List<String> {
        val ownPubkey = keyManager.getPublicKeyHex() ?: return GLOBAL_RELAY_URLS
        val readRelays = memoryEventStore.getReadWriteRelayConfigs(ownPubkey)
            .filter { it.marker == null || it.marker == "read" }
            .mapNotNull { normalizeRelayUrl(it.url) }
        return readRelays.ifEmpty { GLOBAL_RELAY_URLS }
    }

    init {
        // Deterministic cold-start: wait for kind-3 (follows) up to 10s, then
        // kind-10002 (relay lists) up to 5s. Warm resume resolves instantly from
        // snapshot — followsFlow emits in <300ms when MES already has data.
        val ownPubkey = keyManager.getPublicKeyHex()
        Log.d("FeedVM", "init: ownPubkey=${ownPubkey?.take(8)}…")
        if (ownPubkey != null) {
            viewModelScope.launch {
                val follows = withTimeoutOrNull(10_000L) {
                    memoryEventStore.followsFlow(ownPubkey)
                        .filter { it.isNotEmpty() }
                        .first()
                }

                if (follows == null) {
                    _feedType.value = FeedType.Global
                    _coldStartState.value = ColdStartState.READY_GLOBAL
                    Log.d("FeedVM", "Cold-start: no follows after 10s → Global")
                    return@launch
                }

                _hasFollows.value = true

                // Follows arrived — wait for own relay list (kind-10002) up to 5s.
                // Even on timeout, proceed with Following (partial state is fine).
                withTimeoutOrNull(5_000L) {
                    memoryEventStore.readWriteRelayConfigsFlow(ownPubkey)
                        .filter { it.isNotEmpty() }
                        .first()
                }

                _feedType.value = FeedType.Following
                _coldStartState.value = ColdStartState.READY_FOLLOWING
                Log.d("FeedVM", "Cold-start: ${follows.size} follows → Following")
            }

            // Track follows reactively for _hasFollows (feed list building).
            // No auto-switch — cold-start is deterministic.
            viewModelScope.launch {
                memoryEventStore.followsFlow(ownPubkey).map { it.size }.collect { count ->
                    _hasFollows.value = count > 0
                }
            }
        } else {
            _coldStartState.value = ColdStartState.READY_GLOBAL
        }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Initial relay connection with isHomeFeed=true so feed subscriptions
            // are sent. Bootstrap may update kind-10002 later, which flatMapLatest
            // picks up on next feed type emission.
            val initialUrls = resolveGlobalUrls()
            for (url in initialUrls) {
                normalizeRelayUrl(url)?.let { relayPool.addPurpose(it, ConnectionPurpose.PERSISTENT) }
            }
            relayPool.connect(initialUrls, isHomeFeed = true)

            combine(_feedType, _filter, _contentFilter) { type, filter, cf -> Triple(type, filter, cf) }
                .flatMapLatest { (type, filter, cf) ->
                    // Compute new feedKey first for save/restore
                    val feedPrefix = when (type) {
                        is FeedType.Global -> "global"
                        is FeedType.Following -> "following"
                        is FeedType.RelaySet -> "relayset-${type.dTag}"
                        is FeedType.SingleRelay -> "relay-${type.url}"
                    }
                    val newKey = "$feedPrefix-${cf.name}"

                    // Save current feed state before switching
                    if (currentFeedKey.isNotEmpty()) {
                        savedFeedStates[currentFeedKey] = SavedFeedState(
                            displayLimit = _displayLimit.value,
                            lastOldestTimestamp = lastOldestTimestamp,
                            scrollIndex = _savedScrollIndex.value,
                            scrollOffset = _savedScrollOffset.value,
                        )
                        if (savedFeedStates.size > 10) {
                            savedFeedStates.keys.first().let { savedFeedStates.remove(it) }
                        }
                    }
                    currentFeedKey = newKey

                    // Restore saved state or start fresh
                    val saved = savedFeedStates[newKey]
                    if (saved != null) {
                        _displayLimit.value = saved.displayLimit
                        lastOldestTimestamp = saved.lastOldestTimestamp
                        _restoreScrollIndex.value = saved.scrollIndex
                        _restoreScrollOffset.value = saved.scrollOffset
                        _restoreGeneration.value++
                    } else {
                        lastOldestTimestamp = 0L
                        _displayLimit.value = FeedWindowConfig.WINDOW_SIZE
                    }
                    lastLoadMoreTime = 0L
                    _isLoadingMore.value = false
                    // Only reset controller on actual feed switch, not on every Room re-emission
                    if (newKey != lastResetFeedKey) {
                        lastResetFeedKey = newKey
                        feedWindowLoader.stopEngagementRefresh()
                        viewModelScope.launch(Dispatchers.IO) {
                            val result = feedWindowLoader.loadWindow(type, cursor = null)
                            feedWindowLoader.startEngagementRefresh(type, result.eventIds)
                        }
                    }

                    val isBrowse = type is FeedType.SingleRelay || type is FeedType.RelaySet

                    // Set loading BEFORE swapping reducer to prevent empty-state flash.
                    // Without this, Crossfade sees COMPLETE + empty events → "No posts yet."
                    _uiState.value = FeedUiState(loading = true, coverageStatus = CoverageStatus.LOADING)

                    // Create a new reducer for this feed key — flatMapLatest on
                    // _activeReducer auto-propagates the new reducer's state.
                    _activeReducer.value = FeedStateReducer(newKey)

                    // Browse feeds skip home coverage — they stay LOADING until
                    // events arrive from the browse relay or the timeout fires.
                    if (!isBrowse) {
                        val intent = CoverageIntent.HomeFeed()
                        val status = coverageTracker.ensureCoverage(intent)
                        _uiState.value = FeedUiState(loading = status != CoverageStatus.COMPLETE, coverageStatus = status)
                    }

                    // Timeout: if still LOADING after 10s, mark failed and update UI
                    viewModelScope.launch {
                        delay(10_000)
                        if (_uiState.value.coverageStatus == CoverageStatus.LOADING) {
                            if (!isBrowse) {
                                val intent = CoverageIntent.HomeFeed()
                                coverageTracker.markFailed(
                                    intent.scopeType, intent.scopeKey, intent.relaySetId
                                )
                            }
                            _uiState.update { it.copy(loading = false, coverageStatus = CoverageStatus.FAILED) }
                        }
                    }

                    val cfValue = cf.value
                    val pubkey = keyManager.getPublicKeyHex() ?: ""

                    val feedFlow = when (type) {
                        is FeedType.Global    -> {
                            browseSession.stop()
                            val globalUrls = resolveGlobalUrls()
                            currentRelayUrls = globalUrls
                            for (url in globalUrls) {
                                normalizeRelayUrl(url)?.let { relayPool.addPurpose(it, ConnectionPurpose.PERSISTENT) }
                            }
                            relayPool.connect(globalUrls, isHomeFeed = true)
                            Log.d("FeedVM", "A4_VERIFY: Global feed → MES (pk=${pubkey.take(8)})")
                            _displayLimit.flatMapLatest { limit ->
                                val memFilter = MemoryFeedFilter(
                                    kinds = filter.enabledKinds.toSet(),
                                    contentFilter = cfValue,
                                    relayUrls = globalUrls.toSet(),
                                )
                                memoryEventStore.feedFlow(memFilter, limit)
                            }
                        }
                        is FeedType.Following -> {
                            browseSession.stop()
                            currentRelayUrls = emptyList()
                            outboxRouter.start()
                            Log.d("FeedVM", "A4_VERIFY: Following feed → MES (pk=${pubkey.take(8)})")
                            combine(_displayLimit, memoryEventStore.followsFlow(pubkey)) { limit, follows ->
                                limit to follows
                            }.flatMapLatest { (limit, follows) ->
                                val memFilter = MemoryFeedFilter(
                                    kinds = filter.enabledKinds.toSet(),
                                    followedPubkeys = follows,
                                    contentFilter = cfValue,
                                )
                                memoryEventStore.feedFlow(memFilter, limit)
                            }
                        }
                        is FeedType.RelaySet  -> {
                            val ownerPk = keyManager.getPublicKeyHex() ?: ""
                            val members = memoryEventStore.getSetMembers(ownerPk, type.dTag)
                            val setUrls = members.mapNotNull { normalizeRelayUrl(it) }
                                .ifEmpty { resolveGlobalUrls() }
                            currentRelayUrls = setUrls
                            browseSession.start(setUrls)
                            Log.d("FeedVM", "A5_T1: RelaySet feed → MemoryEventStore (${setUrls.size} relays)")
                            _displayLimit.flatMapLatest { limit ->
                                val memFilter = MemoryFeedFilter(
                                    kinds = filter.enabledKinds.toSet(),
                                    contentFilter = cfValue,
                                    relayUrls = setUrls.toSet(),
                                )
                                memoryEventStore.feedFlow(memFilter, limit)
                            }
                        }
                        is FeedType.SingleRelay -> {
                            val singleUrl = listOfNotNull(normalizeRelayUrl(type.url))
                            currentRelayUrls = singleUrl
                            browseSession.start(singleUrl)
                            Log.d("FeedVM", "A5_T1: SingleRelay feed → MemoryEventStore (${type.url})")
                            _displayLimit.flatMapLatest { limit ->
                                val memFilter = MemoryFeedFilter(
                                    kinds = filter.enabledKinds.toSet(),
                                    contentFilter = cfValue,
                                    relayUrls = singleUrl.toSet(),
                                )
                                memoryEventStore.feedFlow(memFilter, limit)
                            }
                        }
                    }

                    // Post-query presentation filters for MemoryEventStore feeds.
                    // Structural filters (kind, pubkey, contentFilter, relayUrls) are
                    // applied inside the walk so limit counts accepted rows.
                    // Presentation filters (sinceHours, engagement minimums) stay here.
                    // All feed types now use MES (A.5.1 T1: SingleRelay + RelaySet migrated).
                    val isMemoryFeed = true
                    val filtered = if (isMemoryFeed) {
                        val sinceTs = filter.sinceHours?.let {
                            System.currentTimeMillis() / 1000L - it * 3600L
                        } ?: 0L
                        feedFlow.map { rows ->
                            rows.filter { row ->
                                val passTime = row.createdAt >= sinceTs
                                val passEngagement = row.replyCount >= filter.minReplies &&
                                    row.repostCount >= filter.minReposts &&
                                    row.reactionCount >= filter.minReactions &&
                                    row.zapTotalSats >= filter.minZapSats
                                passTime && passEngagement
                            }
                        }
                    } else feedFlow

                    // Post-query media type filter: Text/Images/Video within kind 1
                    val finalFlow = if (filter.needsMediaFilter) filtered.map { rows -> applyMediaFilter(rows, filter.showTypes) }
                    else filtered

                    combine(finalFlow, _feedVisible) { rows, visible -> rows to visible }
                }
                // Drop intermediate emissions when the collector is busy (scroll scenarios).
                // Without conflate(), rapid Room re-queries queue up and force Compose
                // to recompose for each intermediate state — causing micro-stutters.
                .conflate()
                // Skip duplicate Room emissions: any write to users/event_stats/event_relays
                // triggers re-query even when this feed's data hasn't changed.
                .distinctUntilChanged()
                .collectLatest { (rows, visible) ->
                    if (!visible) return@collectLatest
                    _isLoadingMore.value = false
                    val sig = Triple(rows.size, rows.firstOrNull()?.id, rows.lastOrNull()?.id)
                    if (sig != lastLoggedEmissionSig) {
                        Log.d("FeedVM", "Feed emission: size=${rows.size} feedKey=$currentFeedKey")
                        lastLoggedEmissionSig = sig
                    }
                    _activeReducer.value.onNewEvents(rows)

                    // Browse feeds: keep LOADING until events arrive (timeout handles failure).
                    // Home feeds: re-check coverage status from DB on each emission.
                    val currentType = _feedType.value
                    val currentIsBrowse = currentType is FeedType.SingleRelay || currentType is FeedType.RelaySet
                    if (currentIsBrowse) {
                        if (rows.isNotEmpty()) {
                            _uiState.value = FeedUiState(loading = false, coverageStatus = CoverageStatus.COMPLETE)
                        }
                        // If empty, keep LOADING — 10s timeout will mark FAILED
                    } else {
                        val intent = CoverageIntent.HomeFeed()
                        val status = coverageTracker.getStatus(
                            intent.scopeType, intent.scopeKey, intent.relaySetId
                        )
                        _uiState.value = FeedUiState(loading = false, coverageStatus = status)
                    }
                }
        }
    }

    companion object {
        // Lightweight regexes matching NoteCard patterns for post-query media filtering
        private val IMAGE_REGEX = Regex(
            """https?://\S+\.(?:jpg|jpeg|png|gif|webp)(?:\?\S*)?|https?://(?:image\.nostr\.build|i\.nostr\.build|nostr\.build|blossom\.primal\.net)/\S+""",
            RegexOption.IGNORE_CASE,
        )
        private val VIDEO_REGEX = Regex(
            """https?://\S+\.(?:mp4|mov|webm|m3u8|m4v|avi)(?:\?\S*)?""",
            RegexOption.IGNORE_CASE,
        )
        private val IMETA_IMAGE_REGEX = Regex(""""image/""", RegexOption.IGNORE_CASE)
        private val IMETA_VIDEO_REGEX = Regex(""""video/""", RegexOption.IGNORE_CASE)

        private fun hasImage(row: FeedRow): Boolean =
            IMAGE_REGEX.containsMatchIn(row.content) ||
            IMETA_IMAGE_REGEX.containsMatchIn(row.tags)

        private fun hasVideo(row: FeedRow): Boolean =
            VIDEO_REGEX.containsMatchIn(row.content) ||
            IMETA_VIDEO_REGEX.containsMatchIn(row.tags)

        fun applyMediaFilter(rows: List<FeedRow>, types: Set<ShowType>): List<FeedRow> =
            rows.filter { row ->
                when (row.kind) {
                    1 -> {
                        val img = hasImage(row)
                        val vid = hasVideo(row)
                        (ShowType.TEXT in types && !img && !vid) ||
                        (ShowType.IMAGES in types && img) ||
                        (ShowType.VIDEO in types && vid)
                    }
                    // kind 20/21/6/30023 already filtered by SQL kinds
                    else -> true
                }
            }
    }
}
