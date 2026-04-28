package com.unsilence.app.ui.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.RelaySet
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.OutboxRelayResolver
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.relay.SubRequest
import com.unsilence.app.data.relay.TimelineConsumer
import com.unsilence.app.data.relay.TimelineService
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
 *   - Subscription state: delegated to TimelineConsumer (feedRows,
 *     showDot, pendingCount, isLoading, isLoadingMore)
 *   - Actions: setFeedType, setContentFilter, addPinnedRelay,
 *     removePinnedRelay, nextFeed, previousFeed, updateFilter,
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
) : ViewModel() {

    // -- TimelineConsumer (feed state) -----------------------------------------

    private val consumer = TimelineConsumer(
        timelineService = timelineService,
        memoryEventStore = memoryEventStore,
        ownerScope = viewModelScope,
    )

    val feedRows = consumer.feedRows
    val showDot = consumer.showDot
    val pendingCount = consumer.pendingCount
    val isLoading = consumer.isLoading
    val isLoadingMore = consumer.isLoadingMore

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

    private val _contentFilter = MutableStateFlow(FeedContentFilter.NOTES_ONLY)
    val contentFilter: StateFlow<FeedContentFilter> = _contentFilter.asStateFlow()

    fun setContentFilter(f: FeedContentFilter) {
        if (_contentFilter.value == f) return
        _contentFilter.value = f
        consumer.setContentFilter(f)
    }

    // -- Filter (kinds, dates, etc -- for filter sheet) ------------------------

    private val _filter = MutableStateFlow(com.unsilence.app.domain.model.FeedFilter())
    val filterFlow: StateFlow<com.unsilence.app.domain.model.FeedFilter> = _filter.asStateFlow()

    fun updateFilter(filter: com.unsilence.app.domain.model.FeedFilter) {
        _filter.value = filter
        viewModelScope.launch { resubscribe(_feedType.value) }
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

    private fun feedTypeMatches(a: FeedType, b: FeedType): Boolean = when {
        a is FeedType.Global && b is FeedType.Global -> true
        a is FeedType.Following && b is FeedType.Following -> true
        a is FeedType.RelaySet && b is FeedType.RelaySet -> a.dTag == b.dTag
        a is FeedType.SingleRelay && b is FeedType.SingleRelay -> a.url == b.url
        else -> false
    }

    private fun buildFeedList(): List<FeedType> {
        val list = mutableListOf<FeedType>()
        if (_hasFollows.value) list.add(FeedType.Following)
        list.add(FeedType.Global)
        list.add(FeedType.Popular)
        for (relay in pinnedRelays.value) {
            if (relay.url == FeedType.Popular.url) continue
            list.add(relay)
        }
        for (set in userSetsFlow.value) {
            list.add(FeedType.RelaySet(set.dTag, set.title ?: set.dTag))
        }
        return list
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

    /** True when there are queued new posts (used by nav bar dot indicator). */
    val hasNewTopPost: Boolean get() = showDot.value

    /** Clear the new-posts indicator (e.g. when user taps the feed tab). */
    fun clearNewTopPost() { consumer.onDotTapped() }

    // -- User actions delegated to consumer ------------------------------------

    fun onViewportChanged(idx: Int) = consumer.onViewportChanged(idx)
    fun onDotTapped() = consumer.onDotTapped()
    fun loadMore() = consumer.loadMore()
    fun refresh() {
        viewModelScope.launch { resubscribe(_feedType.value) }
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

        // Resubscribe collector — fires when feedType OR relay-metadata-version
        // changes. Gated on coldStartState != LOADING. 750ms debounce coalesces
        // the burst of follow kind-10002 inserts during Phase 2.
        viewModelScope.launch {
            combine(
                _coldStartState,
                _feedType,
                relayMetadataVersion,
            ) { state, type, ver -> Triple(state, type, ver) }
                .filter { it.first != ColdStartState.LOADING }
                .debounce(2_000L)
                .distinctUntilChangedBy { (_, type, ver) -> type to ver }
                .collectLatest { (_, type, ver) ->
                    Log.d(TAG, "resubscribe trigger: type=$type metaVer=$ver")
                    resubscribe(type)
                }
        }
    }

    private suspend fun resubscribe(type: FeedType) {
        val subRequests = buildSubRequests(type)
        consumer.subscribe(subRequests)
    }

    private fun buildSubRequests(type: FeedType): List<SubRequest> {
        val ownPubkey = keyManager.getPublicKeyHex()
        val blockedRelays = ownPubkey
            ?.let { memoryEventStore.getBlockedRelayUrls(it).toSet() }
            ?: emptySet()
        val readRelays = ownPubkey
            ?.let { memoryEventStore.getReadWriteRelayConfigs(it).map { c -> c.url } }
            ?: emptyList()
        val config = OutboxRelayResolver.Config(
            kinds = listOf(1, 6, 20, 21, 30023),
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
        consumer.close()
        super.onCleared()
    }
}
