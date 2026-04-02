package com.unsilence.app.ui.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.dao.FollowDao
import com.unsilence.app.data.db.dao.NostrRelaySetDao
import com.unsilence.app.data.db.dao.PinnedRelayDao
import com.unsilence.app.data.db.dao.RelayConfigDao
import com.unsilence.app.data.db.entity.PinnedRelayEntity
import com.unsilence.app.data.db.entity.NostrRelaySetEntity
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.relay.ConnectionPurpose
import com.unsilence.app.data.relay.CoverageIntent
import com.unsilence.app.data.relay.CoverageStatus
import com.unsilence.app.data.relay.OutboxRouter
import com.unsilence.app.data.relay.RelayBrowseSession
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.CoverageRepository
import com.unsilence.app.data.relay.CardHydrator
import com.unsilence.app.data.repository.EventRepository
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.domain.model.FeedFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.unsilence.app.data.db.entity.UserEntity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

sealed class FeedType {
    data object Global    : FeedType()
    data object Following : FeedType()
    data class  RelaySet(val dTag: String, val name: String) : FeedType()
    data class  SingleRelay(val url: String, val label: String) : FeedType()
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
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val relayPool: RelayPool,
    private val outboxRouter: OutboxRouter,
    private val browseSession: RelayBrowseSession,
    private val followDao: FollowDao,
    private val coverageRepository: CoverageRepository,
    private val cardHydrator: CardHydrator,
    private val keyManager: KeyManager,
    private val relayConfigDao: RelayConfigDao,
    private val nostrRelaySetDao: NostrRelaySetDao,
    private val pinnedRelayDao: PinnedRelayDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    private val _feedType = MutableStateFlow<FeedType>(FeedType.Global)
    val feedType: StateFlow<FeedType> = _feedType.asStateFlow()

    /** All relay sets (NIP-51 kind 30002) for the dropdown. */
    val userSetsFlow: StateFlow<List<NostrRelaySetEntity>> =
        keyManager.getPublicKeyHex()?.let { pk ->
            nostrRelaySetDao.getAllSets(pk)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        } ?: MutableStateFlow(emptyList())

    /** Favorite relays pinned to the feed picker — backed by Room for persistence. */
    val pinnedRelays: StateFlow<List<FeedType.SingleRelay>> =
        pinnedRelayDao.allFlow()
            .map { entities -> entities.map { FeedType.SingleRelay(it.relayUrl, it.label) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addPinnedRelay(url: String, label: String) {
        viewModelScope.launch {
            pinnedRelayDao.insert(PinnedRelayEntity(relayUrl = url, label = label))
        }
    }

    fun removePinnedRelay(url: String) {
        viewModelScope.launch {
            pinnedRelayDao.deleteByUrl(url)
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
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    } ?: MutableStateFlow(null)

    private val _displayLimit = MutableStateFlow(200)

    fun updateFilter(filter: FeedFilter) { _filter.value = filter }

    // ── Feed-state reducer ────────────────────────────────────────────────
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

    // ── Coalesced engagement fetch — max one call per 2 seconds ─────────
    private val engagementChannel = Channel<Set<String>>(Channel.CONFLATED)

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            engagementChannel.consumeAsFlow()
                .collect { ids ->
                    relayPool.fetchEngagementBatch(ids.toList().take(20))
                    delay(2000) // minimum 2s between engagement fetches
                }
        }
    }

    // created_at of the last item when loadMore() last fired; guards duplicate page fetches.
    private var lastOldestTimestamp = 0L

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    // ── Profile lookup for repost original authors ──────────────────────
    private val profileCache = ConcurrentHashMap<String, StateFlow<UserEntity?>>()

    /**
     * Fetch engagement only for currently visible items. Called from
     * FeedScreen via a debounced snapshotFlow on visible item keys.
     * Dedup now lives in RelayPool.engagementFetched (global, survives VM recreation).
     */
    fun fetchEngagementForVisible(visibleIds: Set<String>) {
        engagementChannel.trySend(visibleIds)
    }

    fun hydrateVisibleCards(visibleEvents: List<FeedRow>) {
        if (visibleEvents.isEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            cardHydrator.hydrateVisibleCards(visibleEvents)
        }
    }

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
        is FeedType.SingleRelay -> t.label
    }

    /** Reactively tracks whether follows exist — used by buildFeedList and the feed sheet. */
    private val _hasFollows = MutableStateFlow(false)
    val hasFollows: StateFlow<Boolean> = _hasFollows.asStateFlow()

    fun setFeedType(type: FeedType) { _feedType.value = type }

    /** Ordered list of available feeds for cycling. */
    private fun buildFeedList(): List<FeedType> {
        val list = mutableListOf<FeedType>()
        if (_hasFollows.value) list.add(FeedType.Following)
        list.add(FeedType.Global)
        for (relay in pinnedRelays.value) list.add(relay)
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
        val events = _activeReducer.value.state.value.visibleEvents
        val oldest = events.lastOrNull()?.createdAt ?: return
        Log.d("FeedViewModel", "loadMore: oldest=$oldest lastOldest=$lastOldestTimestamp events=${events.size} limit=${_displayLimit.value}")
        if (oldest == lastOldestTimestamp) return
        lastOldestTimestamp = oldest
        _displayLimit.value += 200
        _isLoadingMore.value = true
        relayPool.fetchOlderEvents(currentRelayUrls, oldest)
        Log.d("FeedViewModel", "loadMore: fired, new limit=${_displayLimit.value} relays=${currentRelayUrls.size}")
    }

    /** Read kind-10002 read relays from Room, falling back to hardcoded defaults. */
    private suspend fun resolveGlobalUrls(): List<String> {
        val readRelays = relayConfigDao.getAllReadWriteRelays()
            .filter { it.marker == null || it.marker == "read" }
            .mapNotNull { normalizeRelayUrl(it.relayUrl) }
        return readRelays.ifEmpty { GLOBAL_RELAY_URLS }
    }

    init {
        // Reactively track follows — auto-switch to Following on first follow
        viewModelScope.launch {
            followDao.countFlow().collect { count ->
                val had = _hasFollows.value
                _hasFollows.value = count > 0
                if (!had && count > 0 && _feedType.value is FeedType.Global) {
                    _feedType.value = FeedType.Following
                }
            }
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
                    // Reset all state on feed switch so the new feed starts clean.
                    lastOldestTimestamp = 0L
                    _displayLimit.value = 50
                    _isLoadingMore.value = false
                    cardHydrator.clearCache()

                    // Set loading BEFORE swapping reducer to prevent empty-state flash.
                    // Without this, Crossfade sees COMPLETE + empty events → "No posts yet."
                    _uiState.value = FeedUiState(loading = true, coverageStatus = CoverageStatus.LOADING)

                    // Create a new reducer for this feed key — flatMapLatest on
                    // _activeReducer auto-propagates the new reducer's state.
                    val feedPrefix = when (type) {
                        is FeedType.Global -> "global"
                        is FeedType.Following -> "following"
                        is FeedType.RelaySet -> "relayset-${type.dTag}"
                        is FeedType.SingleRelay -> "relay-${type.url}"
                    }
                    val newKey = "$feedPrefix-${cf.name}"
                    _activeReducer.value = FeedStateReducer(newKey)

                    // Check coverage before deciding whether to fetch
                    val intent = CoverageIntent.HomeFeed()
                    val status = coverageRepository.ensureCoverage(intent)
                    _uiState.value = FeedUiState(loading = status != CoverageStatus.COMPLETE, coverageStatus = status)

                    // Timeout: if still LOADING after 10s, persist FAILED and update UI
                    viewModelScope.launch {
                        delay(10_000)
                        if (_uiState.value.coverageStatus == CoverageStatus.LOADING) {
                            coverageRepository.markFailed(
                                intent.scopeType, intent.scopeKey, intent.relaySetId
                            )
                            _uiState.update { it.copy(loading = false, coverageStatus = CoverageStatus.FAILED) }
                        }
                    }

                    val cfValue = cf.value
                    when (type) {
                        is FeedType.Global    -> {
                            browseSession.stop()
                            val globalUrls = resolveGlobalUrls()
                            currentRelayUrls = globalUrls
                            for (url in globalUrls) {
                                normalizeRelayUrl(url)?.let { relayPool.addPurpose(it, ConnectionPurpose.PERSISTENT) }
                            }
                            relayPool.connect(globalUrls, isHomeFeed = true)
                            _displayLimit.flatMapLatest { limit ->
                                eventRepository.feedFlow(globalUrls, filter, limit, contentFilter = cfValue)
                            }
                        }
                        is FeedType.Following -> {
                            browseSession.stop()
                            currentRelayUrls = emptyList()
                            outboxRouter.start()
                            _displayLimit.flatMapLatest { limit ->
                                eventRepository.followingFeedFlow(filter, limit, contentFilter = cfValue)
                            }
                        }
                        is FeedType.RelaySet  -> {
                            val ownerPk = keyManager.getPublicKeyHex() ?: ""
                            val members = nostrRelaySetDao.getSetMembersSnapshot(type.dTag, ownerPk)
                            val setUrls = members.mapNotNull { normalizeRelayUrl(it.relayUrl) }
                                .ifEmpty { resolveGlobalUrls() }
                            currentRelayUrls = setUrls
                            browseSession.start(setUrls)
                            _displayLimit.flatMapLatest { limit ->
                                eventRepository.feedFlow(setUrls, filter, limit, contentFilter = cfValue)
                            }
                        }
                        is FeedType.SingleRelay -> {
                            val singleUrl = listOfNotNull(normalizeRelayUrl(type.url))
                            currentRelayUrls = singleUrl
                            browseSession.start(singleUrl)
                            _displayLimit.flatMapLatest { limit ->
                                eventRepository.feedFlow(singleUrl, filter, limit, contentFilter = cfValue)
                            }
                        }
                    }
                }
                .collectLatest { rows ->
                    _isLoadingMore.value = false
                    _activeReducer.value.onNewEvents(rows)

                    // Eagerly hydrate the first page so avatars appear immediately.
                    // Delayed 500ms so Compose renders cached Room data first.
                    if (rows.isNotEmpty()) {
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            delay(500)
                            cardHydrator.hydrateVisibleCards(rows.take(10))
                        }
                    }

                    // Re-check coverage status from DB on each emission
                    val intent = CoverageIntent.HomeFeed()
                    val status = coverageRepository.getStatus(
                        intent.scopeType, intent.scopeKey, intent.relaySetId
                    )
                    _uiState.value = FeedUiState(
                        loading = false,
                        coverageStatus = status,
                    )
                }
        }
    }
}
