package com.unsilence.app.ui.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.RelaySet
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.relay.CardHydrator
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.OutboxRelayResolver
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.relay.SubRequest
import com.unsilence.app.data.relay.TimelineConsumer
import com.unsilence.app.data.relay.TimelineService
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
import java.util.Collections
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
    val rawEventCount: StateFlow<Int> = consumer.events
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

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
        // Resubscribe collector picks up the change
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

    private fun <V> lruCache(cap: Int): MutableMap<String, V> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, V>(cap + 16, 0.75f, true) {
                override fun removeEldestEntry(e: MutableMap.MutableEntry<String, V>?) = size > cap
            }
        )

    private val profileCache = lruCache<StateFlow<UserEntity?>>(300)

    fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
        profileCache.getOrPut(pubkey) {
            userRepository.userFlow(pubkey)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }

    // -- Per-event stats lookup (replyCount, reactionCount, etc.) -------------
    //
    // Each visible card observes its own statsFlow so engagement counts update
    // reactively without going through TimelineConsumer.feedRows. A kind-7
    // reaction on event A only recomposes the card for event A; other cards
    // see their statsFlow filter the bump via distinctUntilChanged.
    private val statsCache = lruCache<StateFlow<com.unsilence.app.data.memory.EventStats>>(500)

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
    fun clearNewTopPost() { consumer.onDotTapped() }

    // -- Warm-zone hydration ---------------------------------------------------
    //
    // Architecture: the feed loads the most-recent ~300 notes (chronological,
    // append-mode growth). For the user to perceive the feed as instant, the
    // WARM ZONE around the viewport — 10 above + 30 below the first visible
    // row — must have its skeleton data (profiles, quoted notes, OG previews,
    // image dimensions) ready BEFORE rendering. Otherwise cards pop in with
    // late avatars, dimension shifts, and missing quotes.
    //
    // We debounce viewport changes by 300ms so a fast scroll doesn't fire
    // hydration for every intermediate position. CardHydrator de-duplicates
    // requests internally, so re-firing for an overlapping zone is cheap.

    private val _viewportFirstVisible = MutableStateFlow(0)

    init {
        // CRITICAL: launch on Default — viewModelScope defaults to
        // Main.immediate, and `cardHydrator.hydrateVisibleCards` does NOT
        // wrap its body in withContext(IO/Default). It calls into RelayPool,
        // ProfileResolver, ImageDimensionCache.resolveAll, all of which
        // block briefly on lookup work and emit Log.d lines on the calling
        // thread. Running this on Main was the dominant cause of the
        // 30-76 frame skips after every batch arrival in field logs.
        @OptIn(FlowPreview::class)
        viewModelScope.launch(Dispatchers.Default) {
            combine(consumer.events, _viewportFirstVisible) { events, first -> events to first }
                .debounce(300L)
                .collectLatest { (events, first) ->
                    if (events.isEmpty()) return@collectLatest
                    val zoneStart = (first - WARM_ZONE_ABOVE).coerceAtLeast(0)
                    val zoneEnd = (first + WARM_ZONE_BELOW).coerceAtMost(events.size)
                    if (zoneStart >= zoneEnd) return@collectLatest
                    val warmEvents = events.subList(zoneStart, zoneEnd)
                    val rows = memoryEventStore.feedRowsByIds(warmEvents.map { it.id }.toSet())
                    if (rows.isNotEmpty()) cardHydrator.hydrateVisibleCards(rows)
                }
        }
    }

    // -- User actions delegated to consumer ------------------------------------

    fun onViewportChanged(idx: Int) {
        _viewportFirstVisible.value = idx
        consumer.onViewportChanged(idx)
    }
    fun onDotTapped() = consumer.onDotTapped()
    fun loadMore() = consumer.loadMore()
    fun refresh() {
        _refreshCounter.value = _refreshCounter.value + 1
    }

    private companion object {
        const val WARM_ZONE_ABOVE = 10
        const val WARM_ZONE_BELOW = 30
    }

    private val _refreshCounter = MutableStateFlow(0)
    private var lastRefreshCounter = 0
    private var lastSubRequests: List<SubRequest> = emptyList()

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

        // Resubscribe collector — fires when feedType, relay-metadata-version,
        // refreshCounter, or filter changes. Gated on coldStartState != LOADING.
        // Debounce is on relayMetadataVersion only (kind-10002 burst coalescing)
        // so user actions (feed switch, refresh, filter) fire immediately.
        viewModelScope.launch {
            @Suppress("UNCHECKED_CAST")
            combine(
                _coldStartState,
                _feedType,
                relayMetadataVersion,
                _refreshCounter,
                _filter,
                _contentFilter,
            ) { arr ->
                ResubKey(
                    state         = arr[0] as ColdStartState,
                    type          = arr[1] as FeedType,
                    ver           = arr[2] as Int,
                    refresh       = arr[3] as Int,
                    filter        = arr[4] as com.unsilence.app.domain.model.FeedFilter,
                    contentFilter = arr[5] as FeedContentFilter,
                )
            }
                .filter { it.state != ColdStartState.LOADING }
                .distinctUntilChangedBy {
                    listOf(it.type, it.ver, it.refresh, it.filter, it.contentFilter)
                }
                .collectLatest { key ->
                    val forceRefresh = key.refresh != lastRefreshCounter
                    lastRefreshCounter = key.refresh
                    Log.d(TAG, "resubscribe trigger: type=${key.type} metaVer=${key.ver} cf=${key.contentFilter} force=$forceRefresh")
                    resubscribe(key.type, key.contentFilter, forceRefresh)
                }
        }

        // One-shot: merge cached events when snapshot restore completes.
        // Fires once — snapshot events merge into whatever the consumer already
        // has from relay subscriptions, without disrupting active subs.
        viewModelScope.launch {
            memoryEventStore.snapshotRestoredFlow.filter { it > 0L }.first()
            val cached = loadCachedEvents(_feedType.value)
            if (cached.isNotEmpty()) {
                Log.d(TAG, "snapshot restored: merging ${cached.size} cached events")
                consumer.addCachedEvents(cached)
            }
        }
    }

    private suspend fun resubscribe(type: FeedType, contentFilter: FeedContentFilter = FeedContentFilter.NOTES_ONLY, forceRefresh: Boolean = false) {
        val subRequests = buildSubRequests(type, contentFilter)
        if (!forceRefresh && subRequests == lastSubRequests) {
            Log.d(TAG, "resubscribe: SubRequests unchanged, skipping")
            return
        }
        lastSubRequests = subRequests
        val cachedEvents = loadCachedEvents(type)
        consumer.subscribe(subRequests, cachedEvents)
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

    private fun buildSubRequests(type: FeedType, contentFilter: FeedContentFilter = FeedContentFilter.NOTES_ONLY): List<SubRequest> {
        val ownPubkey = keyManager.getPublicKeyHex()
        val blockedRelays = ownPubkey
            ?.let { memoryEventStore.getBlockedRelayUrls(it).toSet() }
            ?: emptySet()
        val readRelays = ownPubkey
            ?.let { memoryEventStore.getReadWriteRelayConfigs(it).map { c -> c.url } }
            ?: emptyList()
        val kinds = if (contentFilter == FeedContentFilter.REPLIES_ONLY) {
            listOf(1)
        } else {
            listOf(1, 6, 20, 21, 30023)
        }
        val config = OutboxRelayResolver.Config(
            kinds = kinds,
            limit = 300,
            onlyReplies = contentFilter == FeedContentFilter.REPLIES_ONLY,
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

    private data class ResubKey(
        val state: ColdStartState,
        val type: FeedType,
        val ver: Int,
        val refresh: Int,
        val filter: com.unsilence.app.domain.model.FeedFilter,
        val contentFilter: FeedContentFilter,
    )
}
