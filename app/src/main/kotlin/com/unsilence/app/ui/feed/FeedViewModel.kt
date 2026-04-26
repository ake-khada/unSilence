package com.unsilence.app.ui.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.RelaySet
import com.unsilence.app.data.memory.UserEntity
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
import com.unsilence.app.domain.model.FeedFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    fun updateFilter(filter: FeedFilter) { _filter.value = filter }

    // ── FeedWindow 2-slot cache ─────────────────────────────────────────
    @Volatile private var activeWindow: FeedWindow? = null
    @Volatile private var prevWindow: FeedWindow? = null
    private val _activeKey = MutableStateFlow<WindowKey.Home?>(null)
    private val _refreshTrigger = MutableStateFlow(0)

    /** Live snapshot from the active window — drives all screen state. */
    private val snapshot: StateFlow<WindowSnapshot> = _activeKey
        .flatMapLatest { key ->
            if (key != null) activeWindow?.snapshot ?: flowOf(WindowSnapshot())
            else flowOf(WindowSnapshot())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WindowSnapshot())

    /** Backward-compat: FeedScreen reads ReducerState shape. */
    val reducerState: StateFlow<ReducerState> = snapshot
        .map { snap ->
            ReducerState(
                visibleEvents = snap.rows,
                unreadCount = snap.pendingCount,
                showDot = snap.showDot,
                oldestCreatedAt = snap.oldestCreatedAt,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReducerState())

    val isLoadingMore: StateFlow<Boolean> = snapshot
        .map { it.isLoadingMore }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun onScrollPositionChanged(firstVisibleIndex: Int, firstVisibleOffset: Int) {
        activeWindow?.onScrollChanged(firstVisibleIndex, firstVisibleOffset)
    }

    /** No-op forwarder — screen calls both; delegates to onScrollPositionChanged. */
    fun saveScrollPosition(index: Int, offset: Int) = onScrollPositionChanged(index, offset)

    fun onDotTapped() { activeWindow?.flushPending() }

    /** True when there are queued new posts (used by nav bar dot indicator). */
    val hasNewTopPost: Boolean get() = reducerState.value.showDot

    /** Clear the new-posts indicator (e.g. when user taps the feed tab). */
    fun clearNewTopPost() { onDotTapped() }

    // Degenerate scroll restore — FeedWindow manages its own state
    private val _restoreScrollIndex = MutableStateFlow(0)
    private val _restoreScrollOffset = MutableStateFlow(0)
    private val _restoreGeneration = MutableStateFlow(0)
    val restoreScrollIndex: StateFlow<Int> = _restoreScrollIndex.asStateFlow()
    val restoreScrollOffset: StateFlow<Int> = _restoreScrollOffset.asStateFlow()
    val restoreGeneration: StateFlow<Int> = _restoreGeneration.asStateFlow()

    fun loadMore() { activeWindow?.loadMore() }

    // ── Profile lookup for repost original authors ──────────────────────
    private val profileCache = ConcurrentHashMap<String, StateFlow<UserEntity?>>()

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

    /** Force re-fetch by incrementing refresh trigger — combine re-emits same key. */
    fun refresh() { _refreshTrigger.value++ }

    /** Read kind-10002 read relays from MES, falling back to hardcoded defaults. */
    private fun resolveGlobalUrls(): List<String> {
        val ownPubkey = keyManager.getPublicKeyHex() ?: return GLOBAL_RELAY_URLS
        val readRelays = memoryEventStore.getReadWriteRelayConfigs(ownPubkey)
            .filter { it.marker == null || it.marker == "read" }
            .mapNotNull { normalizeRelayUrl(it.url) }
        return readRelays.ifEmpty { GLOBAL_RELAY_URLS }
    }

    // ── Window lifecycle ─────────────────────────────────────────────────

    private fun swapToWindow(key: WindowKey.Home) {
        val prev = activeWindow
        prev?.deactivate()

        // Check if prevWindow matches this key — hot swap
        val cached = prevWindow
        if (cached != null && cached.key == key && cached.hasLoaded) {
            prevWindow = prev
            activeWindow = cached
            cached.activate()
            _activeKey.value = key
            return
        }

        // Cold path: release prev, create fresh
        prevWindow?.release()
        prevWindow = prev
        val window = FeedWindow(
            key = key,
            mes = memoryEventStore,
            loader = feedWindowLoader,
            keyManager = keyManager,
            parentScope = viewModelScope,
        )
        activeWindow = window
        window.activate()
        _activeKey.value = key
    }

    private suspend fun connectRelaysForFeedType(type: FeedType) {
        when (type) {
            is FeedType.Global -> {
                browseSession.stop()
                val globalUrls = resolveGlobalUrls()
                for (url in globalUrls) {
                    normalizeRelayUrl(url)?.let { relayPool.addPurpose(it, ConnectionPurpose.PERSISTENT) }
                }
                relayPool.connect(globalUrls, isHomeFeed = true)
            }
            is FeedType.Following -> {
                browseSession.stop()
                outboxRouter.start()
            }
            is FeedType.RelaySet -> {
                val ownerPk = keyManager.getPublicKeyHex() ?: ""
                val members = memoryEventStore.getSetMembers(ownerPk, type.dTag)
                val setUrls = members.mapNotNull { normalizeRelayUrl(it) }
                    .ifEmpty { resolveGlobalUrls() }
                browseSession.start(setUrls)
            }
            is FeedType.SingleRelay -> {
                val singleUrl = listOfNotNull(normalizeRelayUrl(type.url))
                browseSession.start(singleUrl)
            }
        }
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
            viewModelScope.launch {
                memoryEventStore.followsFlow(ownPubkey).map { it.size }.collect { count ->
                    _hasFollows.value = count > 0
                }
            }
        } else {
            _coldStartState.value = ColdStartState.READY_GLOBAL
        }

        // ── Window lifecycle driven by feed+filter combine ──────────────
        viewModelScope.launch(Dispatchers.IO) {
            // Initial relay connection
            val initialUrls = resolveGlobalUrls()
            for (url in initialUrls) {
                normalizeRelayUrl(url)?.let { relayPool.addPurpose(it, ConnectionPurpose.PERSISTENT) }
            }
            relayPool.connect(initialUrls, isHomeFeed = true)

            combine(_feedType, _filter, _contentFilter, _refreshTrigger) { type, filter, cf, _ ->
                WindowKey.Home(type, cf, filter)
            }
            .collectLatest { key ->
                val isBrowse = key.feedType is FeedType.SingleRelay || key.feedType is FeedType.RelaySet

                _uiState.value = FeedUiState(loading = true, coverageStatus = CoverageStatus.LOADING)

                connectRelaysForFeedType(key.feedType)
                swapToWindow(key)

                if (!isBrowse) {
                    val intent = CoverageIntent.HomeFeed()
                    coverageTracker.ensureCoverage(intent)
                }

                // Timeout: if still LOADING after 10s, mark failed
                val timeoutJob = viewModelScope.launch {
                    delay(10_000)
                    if (_uiState.value.loading) {
                        if (!isBrowse) {
                            val intent = CoverageIntent.HomeFeed()
                            coverageTracker.markFailed(
                                intent.scopeType, intent.scopeKey, intent.relaySetId
                            )
                        }
                        _uiState.update { it.copy(loading = false, coverageStatus = CoverageStatus.FAILED) }
                    }
                }

                // Watch snapshot — update uiState when window finishes loading
                try {
                    snapshot.collect { snap ->
                        if (snap.isLoadingInitial) return@collect
                        timeoutJob.cancel()
                        if (isBrowse) {
                            _uiState.value = FeedUiState(
                                loading = false,
                                coverageStatus = if (snap.rows.isNotEmpty()) CoverageStatus.COMPLETE
                                    else CoverageStatus.FAILED,
                            )
                        } else {
                            val intent = CoverageIntent.HomeFeed()
                            val status = coverageTracker.getStatus(
                                intent.scopeType, intent.scopeKey, intent.relaySetId,
                            )
                            _uiState.value = FeedUiState(loading = false, coverageStatus = status)
                        }
                    }
                } finally {
                    timeoutJob.cancel()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeWindow?.release()
        prevWindow?.release()
    }
}
