package com.unsilence.app.ui.feed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.dao.FollowDao
import com.unsilence.app.data.db.dao.NostrRelaySetDao
import com.unsilence.app.data.db.dao.RelayConfigDao
import com.unsilence.app.data.db.entity.NostrRelaySetEntity
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.relay.CardHydrator
import com.unsilence.app.data.relay.CoverageIntent
import com.unsilence.app.data.relay.CoverageStatus
import com.unsilence.app.data.relay.OutboxRouter
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.CoverageRepository
import com.unsilence.app.data.repository.EventRepository
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.domain.model.FeedFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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

data class FeedUiState(
    val events: List<FeedRow> = emptyList(),
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
    private val followDao: FollowDao,
    private val coverageRepository: CoverageRepository,
    private val cardHydrator: CardHydrator,
    private val keyManager: KeyManager,
    private val relayConfigDao: RelayConfigDao,
    private val nostrRelaySetDao: NostrRelaySetDao,
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

    /** Favorite relays pinned to the feed picker. */
    private val _pinnedRelays = MutableStateFlow<List<FeedType.SingleRelay>>(emptyList())
    val pinnedRelays: StateFlow<List<FeedType.SingleRelay>> = _pinnedRelays.asStateFlow()

    fun addPinnedRelay(url: String, label: String) {
        val entry = FeedType.SingleRelay(url, label)
        _pinnedRelays.value = (_pinnedRelays.value + entry).distinctBy { it.url }
    }

    fun removePinnedRelay(url: String) {
        _pinnedRelays.value = _pinnedRelays.value.filter { it.url != url }
    }

    private val _filter = MutableStateFlow(FeedFilter())
    val filterFlow: StateFlow<FeedFilter> = _filter.asStateFlow()

    /** Signed-in user's avatar URL, for nav icons. */
    val userAvatarUrl: StateFlow<String?> = keyManager.getPublicKeyHex()?.let { pubkey ->
        userRepository.userFlow(pubkey)
            .map { it?.picture }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    } ?: MutableStateFlow(null)

    private val _displayLimit = MutableStateFlow(200)

    fun updateFilter(filter: FeedFilter) { _filter.value = filter }

    /**
     * True when the top-of-feed item has a newer created_at than the previous emission.
     * Used to drive the new-posts dot and to trigger a snap-to-top when the user
     * is already at index 0. Cleared by FeedScreen via clearNewTopPost().
     */
    var hasNewTopPost by mutableStateOf(false)
        private set

    fun clearNewTopPost() { hasNewTopPost = false }

    // created_at of the first item in the last emission; 0 until the first load.
    private var newestTimestamp = 0L

    // created_at of the last item when loadMore() last fired; guards duplicate page fetches.
    private var lastOldestTimestamp = 0L

    // ── Profile lookup for repost original authors ──────────────────────
    private val profileCache = ConcurrentHashMap<String, StateFlow<UserEntity?>>()

    /**
     * Fetch engagement only for currently visible items. Called from
     * FeedScreen via a debounced snapshotFlow on visible item keys.
     * Dedup now lives in RelayPool.engagementFetched (global, survives VM recreation).
     */
    fun fetchEngagementForVisible(visibleIds: Set<String>) {
        relayPool.fetchEngagementBatch(visibleIds.toList().take(20))
    }

    fun hydrateVisibleCards(visibleEvents: List<FeedRow>) {
        android.util.Log.d("FeedVM", "hydrateVisibleCards from snapshotFlow: ${visibleEvents.size} events, kinds=${visibleEvents.map { it.kind }.distinct()}")
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

    fun setFeedType(type: FeedType) { _feedType.value = type }

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
        val oldest = _uiState.value.events.lastOrNull()?.createdAt ?: return
        if (oldest == lastOldestTimestamp) return
        lastOldestTimestamp = oldest
        _displayLimit.value += 200
        relayPool.fetchOlderEvents(currentRelayUrls, oldest)
    }

    /** Read kind-10002 read relays from Room, falling back to hardcoded defaults. */
    private suspend fun resolveGlobalUrls(): List<String> {
        val readRelays = relayConfigDao.getAllReadWriteRelays()
            .filter { it.marker == null || it.marker == "read" }
            .map { it.relayUrl }
        return readRelays.ifEmpty { GLOBAL_RELAY_URLS }
    }

    init {
        viewModelScope.launch {
            val hasFollows = followDao.count() > 0
            if (hasFollows) _feedType.value = FeedType.Following

            // Initial relay connection with isHomeFeed=true so feed subscriptions
            // are sent. Bootstrap may update kind-10002 later, which flatMapLatest
            // picks up on next feed type emission.
            val initialUrls = resolveGlobalUrls()
            relayPool.connect(initialUrls, isHomeFeed = true)

            combine(_feedType, _filter) { type, filter -> type to filter }
                .flatMapLatest { (type, filter) ->
                    // Reset all state on feed switch so the new feed starts clean.
                    newestTimestamp     = 0L
                    hasNewTopPost       = false
                    lastOldestTimestamp = 0L
                    _displayLimit.value = 200
                    cardHydrator.clearCache()

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

                    when (type) {
                        is FeedType.Global    -> {
                            // Re-read kind-10002 each time Global feed is selected —
                            // bootstrap may have refreshed the relay list since init.
                            val globalUrls = resolveGlobalUrls()
                            currentRelayUrls = globalUrls
                            // Register global subs BEFORE connect so they're in
                            // persistentSubs when subscribeAfterConnect replays them.
                            relayPool.startGlobalFeed(globalUrls)
                            relayPool.connect(globalUrls, isHomeFeed = true)
                            _displayLimit.flatMapLatest { limit ->
                                eventRepository.feedFlow(globalUrls, filter, limit)
                            }
                        }
                        is FeedType.Following -> {
                            relayPool.stopGlobalFeed()
                            currentRelayUrls = emptyList()
                            outboxRouter.start()
                            _displayLimit.flatMapLatest { limit ->
                                eventRepository.followingFeedFlow(filter, limit)
                            }
                        }
                        is FeedType.RelaySet  -> {
                            val ownerPk = keyManager.getPublicKeyHex() ?: ""
                            val members = nostrRelaySetDao.getSetMembersSnapshot(type.dTag, ownerPk)
                            val setUrls = members.map { it.relayUrl }.ifEmpty { resolveGlobalUrls() }
                            currentRelayUrls = setUrls
                            relayPool.startGlobalFeed(setUrls)
                            relayPool.connect(setUrls)
                            _displayLimit.flatMapLatest { limit ->
                                eventRepository.feedFlow(setUrls, filter, limit)
                            }
                        }
                        is FeedType.SingleRelay -> {
                            val singleUrl = listOf(type.url)
                            currentRelayUrls = singleUrl
                            relayPool.startGlobalFeed(singleUrl)
                            relayPool.connect(singleUrl)
                            _displayLimit.flatMapLatest { limit ->
                                eventRepository.feedFlow(singleUrl, filter, limit)
                            }
                        }
                    }
                }
                .collectLatest { rows ->
                    val incomingNewest = rows.firstOrNull()?.createdAt ?: 0L

                    // Only flag a new top post after the initial load; the initial
                    // populate is not a "new post arrived" event.
                    if (newestTimestamp > 0 && incomingNewest > newestTimestamp) {
                        hasNewTopPost = true
                    }
                    newestTimestamp = incomingNewest

                    // Re-check coverage status from DB on each emission
                    val intent = CoverageIntent.HomeFeed()
                    val status = coverageRepository.getStatus(
                        intent.scopeType, intent.scopeKey, intent.relaySetId
                    )
                    _uiState.value = FeedUiState(
                        events = rows,
                        loading = false,
                        coverageStatus = status,
                    )

                    // Hydration is now handled exclusively by the snapshotFlow-based
                    // visible-card observer in FeedScreen. The old collectLatest hydration
                    // was causing duplicate work — both paths were hydrating the same first
                    // 20 rows, triggering redundant relay REQs and profile fetches.
                }
        }
    }
}
