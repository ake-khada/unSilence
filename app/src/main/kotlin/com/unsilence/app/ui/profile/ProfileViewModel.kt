package com.unsilence.app.ui.profile

import com.unsilence.app.ui.shared.CardDataFlow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import com.unsilence.app.ui.shared.collectLatestWhileActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.EventStats
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.ReactionInfo
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.memory.ZapDetail
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.NostrFilter
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.SubRequest
import com.unsilence.app.data.relay.TimelineMerge
import com.unsilence.app.data.relay.TimelineService
import com.unsilence.app.data.relay.WotHydrationCoalescer
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.data.relay.FollowerCount
import com.unsilence.app.data.relay.reconciledFollowerCount
import com.unsilence.app.data.relay.wotLookupSnapshot
import com.unsilence.app.data.relay.wotSubjectsForFeedRows
import com.unsilence.app.data.relay.wotVerifiedFollowers
import com.unsilence.app.ui.feed.FeedContentFilter
import com.unsilence.app.ui.shared.TimelineCardData
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val keyManager: KeyManager,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val timelineService: TimelineService,
    private val relayPreferencesStore: com.unsilence.app.data.relay.RelayPreferencesStore,
    private val profilePipeline: com.unsilence.app.data.relay.ProfilePipeline,
    private val wotHydrationCoalescer: WotHydrationCoalescer,
    private val timelineCardData: TimelineCardData,
) : ViewModel() {

    val pubkeyHex: String? = keyManager.getPublicKeyHex()

    val npub: String? = pubkeyHex?.let { hex ->
        runCatching { hex.hexToByteArray().toNpub() }.getOrNull()
    }

    /** NIP-36 sensitive-content display mode (shared with feed). */
    val sensitiveContentMode: StateFlow<com.unsilence.app.data.memory.SensitiveContentMode> =
        relayPreferencesStore.sensitiveContentMode

    private val indexedFollowerCount = MutableStateFlow<Long?>(null)
    internal val followerCount: StateFlow<FollowerCount> = if (pubkeyHex == null) {
        MutableStateFlow(FollowerCount.Unknown)
    } else {
        combine(
            indexedFollowerCount,
            memoryEventStore.followersFlow(pubkeyHex),
            memoryEventStore.wotSignalFlow,
        ) { indexed, known, _ ->
            reconciledFollowerCount(
                indexedCount = indexed,
                knownFollowers = known.size,
                trustedFollowerEstimate = wotVerifiedFollowers(memoryEventStore.wotFor(pubkeyHex)),
            )
        }.distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FollowerCount.Unknown)
    }

    /** Live user metadata from MES (null until kind 0 arrives from relay). */
    val userFlow: Flow<UserEntity?> =
        if (pubkeyHex != null) memoryEventStore.userEntityFlow(pubkeyHex) else emptyFlow()

    // ── Timeline state (mirrors FeedViewModel pattern) ───────────────────────

    private val _events = MutableStateFlow<List<NostrEvent>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
    private val _isAtTop = MutableStateFlow(true)
    private val _contentFilter = MutableStateFlow(FeedContentFilter.NOTES_ONLY)
    private var currentHandle: TimelineService.TimelineHandle? = null
    private val screenActive = MutableStateFlow(false)
    @Volatile private var subscriptionGeneration = 0L

    fun setScreenActive(active: Boolean) {
        if (screenActive.value == active) return
        screenActive.value = active
        if (!active) {
            subscriptionGeneration++
            currentHandle?.close()
            currentHandle = null
        }
    }
    private val _wotSubjects = MutableStateFlow<Set<String>>(emptySet())

    val isLoadingPosts: StateFlow<Boolean> = _isLoading.asStateFlow()

    @OptIn(FlowPreview::class)
    val tabPostsFlow: StateFlow<List<FeedRow>> =
        combine(
            _events,
            _contentFilter,
            userFlow,
            memoryEventStore.feedSignalFlow,
            memoryEventStore.ownMuteListFlow(),
        ) { events, cf, profile, _, muteList ->
            if (events.isEmpty()) return@combine emptyList()
            val displayed = events.asSequence()
                .filterNot { memoryEventStore.isDeleted(it) }
                .filter {
                    isVisibleProfileTimelineEvent(
                        event = it,
                        filter = cf,
                        muteList = muteList,
                        eventProvider = memoryEventStore::getNostrEvent,
                    )
                }
                .take(FEED_DISPLAY_CAP)
                .toList()
            if (displayed.isEmpty()) return@combine emptyList()
            val ids = displayed.map { it.id }.toSet()
            val rowsById = memoryEventStore.feedRowsByIds(ids).associateBy { it.id }
            displayed.map { evt ->
                (rowsById[evt.id] ?: memoryEventStore.synthesizeFeedRow(evt))
                    .withProfileAuthorSnapshot(profile)
            }
        }
            .sample(FEED_SAMPLE_MS)
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val wotLookups: StateFlow<Map<String, WotLookup>> =
        combine(_wotSubjects, memoryEventStore.wotSignalFlow) { subjects, _ ->
            wotLookupSnapshot(subjects, memoryEventStore::wotFor)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val profileWotLookup: StateFlow<WotLookup> =
        memoryEventStore.wotSignalFlow
            .map { pubkeyHex?.let { memoryEventStore.wotFor(it) } ?: WotLookup.Pending }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WotLookup.Pending)

    val wotProvenance: StateFlow<ProfileWotProvenance> =
        combine(
            memoryEventStore.wotSignalFlow,
            memoryEventStore.profileSignalFlow,
            relayPreferencesStore.lastWotFetchAtFlow(),
        ) { _, _, lastFetchAt ->
            val provider = memoryEventStore.activeWotProvider()
            val profile = memoryEventStore.getUserEntity(provider.providerPubkey)
            ProfileWotProvenance(
                providerName = profile?.displayName?.takeIf { it.isNotBlank() }
                    ?: profile?.name?.takeIf { it.isNotBlank() }
                    ?: "Provider ${provider.providerPubkey.take(8)}…",
                relayHint = provider.relayHint,
                lastFetchAt = lastFetchAt,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ProfileWotProvenance("Provider", "", 0L),
        )

    val feedWotDisplayMode: StateFlow<FeedWotDisplayMode> =
        relayPreferencesStore.feedWotDisplayModeFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), FeedWotDisplayMode.NUMBERS)

    val relayCount: StateFlow<Int?> = pubkeyHex?.let { pubkey ->
        memoryEventStore.profileRelayCountFlow(pubkey)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    } ?: MutableStateFlow(null)

    // ── Profile tabs ─────────────────────────────────────────────────────

    val selectedTab = MutableStateFlow(savedStateHandle.get<ProfileTab>("profileTab") ?: ProfileTab.NOTES)

    fun selectTab(tab: ProfileTab) {
        selectedTab.value = tab
    }

    // Track last subscription group to optimize Notes↔Replies (filter-only swap)
    private var lastSubGroup: SubGroup? = null

    init {
        if (pubkeyHex != null) {
            _wotSubjects.value = setOf(pubkeyHex)
            wotHydrationCoalescer.requestProfileHydration(pubkeyHex)

            viewModelScope.launch {
                selectedTab.collect { tab ->
                    savedStateHandle["profileTab"] = tab
                    _contentFilter.value = if (tab == ProfileTab.REPLIES) {
                        FeedContentFilter.REPLIES_ONLY
                    } else FeedContentFilter.NOTES_ONLY
                }
            }
            viewModelScope.launch {
                selectedTab.map(::subGroupFor).distinctUntilChanged()
                    .collectLatestWhileActive(screenActive) { group ->
                        collectProfileSubscription(pubkeyHex, group)
                    }
            }

            // Fetch the integrity-checked follower count (MES-cached and pipeline-deduped).
            viewModelScope.launch(Dispatchers.IO) {
                profilePipeline.fetchFollowerCount(pubkeyHex)?.let { indexedFollowerCount.value = it }
            }
            viewModelScope.launch(Dispatchers.IO) {
                profilePipeline.fetchProfileRelayFacts(pubkeyHex)
            }

            // Eager pipeline: refs + engagement pre-fetched in batch.
            // AppBootstrapper already runs this for own profile at cold-start,
            // but ProfileViewModel may be created after bootstrap completes —
            // this ensures coverage on warm navigation to own profile tab.
            viewModelScope.launch(Dispatchers.IO) {
                profilePipeline.loadProfile(
                    pubkey = pubkeyHex,
                    isOwn = true,
                    anchorPolicy = com.unsilence.app.data.relay.AnchorPolicy.OWN,
                )
            }
        }
    }

    // ── Profile lookup for repost original authors ───────────────────────

    fun profileFlow(pubkey: String): CardDataFlow<UserEntity?> =
        timelineCardData.profileFlow(pubkey)

    // ── Per-event stats (matches FeedViewModel.statsFlow) ────────────────

    fun statsFlow(eventId: String): CardDataFlow<EventStats> =
        timelineCardData.statsFlow(eventId)

    fun zapDetailsForEvent(eventId: String): List<ZapDetail> =
        timelineCardData.zapDetailsForEvent(eventId)
    fun repostPubkeysForEvent(eventId: String): List<String> =
        timelineCardData.repostPubkeysForEvent(eventId)
    fun reactionsForEvent(eventId: String): List<ReactionInfo> =
        timelineCardData.reactionsForEvent(eventId)

    /** Exact following count when the owner's kind-3 is resolved; null while unknown. */
    val followingCount: StateFlow<Long?> = pubkeyHex?.let { pk ->
        memoryEventStore.followsSignalFlow
            .map { memoryEventStore.getFollows(pk)?.size?.toLong() }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    } ?: MutableStateFlow(null)

    /**
     * Reconcile the owner's replaceable contact list whenever the profile tab
     * is entered. The count remains a live MES projection; this only refreshes
     * its source from multiple relays so a restored stale snapshot cannot win.
     */
    suspend fun refreshFollowingCount() {
        val ownPubkey = pubkeyHex ?: return
        relayPool.refreshFollowList(ownPubkey)
    }

    // ── User actions ─────────────────────────────────────────────────────

    fun onViewportChanged(first: Int, last: Int, isScrolling: Boolean = false) {
        val atTop = first <= 0
        if (_isAtTop.value != atTop) _isAtTop.value = atTop
        requestVisibleWotHydration(first, last)
        // Recent engagement is eager; the screen's card-window hydrator covers
        // older posts as they enter the viewport.
    }

    private fun requestVisibleWotHydration(first: Int, last: Int) {
        val own = pubkeyHex
        val posts = tabPostsFlow.value
        val dataFirst = (first - PROFILE_EVENT_OFFSET).coerceAtLeast(0)
        val dataLast = (last - PROFILE_EVENT_OFFSET).coerceAtMost(posts.lastIndex)
        val subjects = buildSet {
            own?.let { add(it) }
            if (dataFirst <= dataLast) {
                addAll(
                    wotSubjectsForFeedRows(
                        posts.subList(dataFirst, dataLast + 1),
                        cachedModelProvider = memoryEventStore::getEventModel,
                    )
                )
            }
        }
        _wotSubjects.value = subjects
        wotHydrationCoalescer.requestHydration(subjects)
    }

    fun requestWotHydration(pubkeys: Collection<String>) {
        if (pubkeys.isEmpty()) return
        _wotSubjects.update { current -> current + pubkeys }
        wotHydrationCoalescer.requestHydration(pubkeys)
    }

    fun loadMore() {
        val handle = currentHandle ?: return
        val generation = subscriptionGeneration
        // _events is the contiguous mixed-kind timeline. Paginating from its tail
        // avoids re-requesting filtered-out replies between the last visible note
        // and the actual cache boundary.
        val until = _events.value.lastOrNull()?.createdAt ?: return
        viewModelScope.launch {
            val older = timelineService.fetchOlderTimeline(handle.timelineKey, until, 100)
            if (screenActive.value && subscriptionGeneration == generation && older.isNotEmpty()) {
                _events.update { current -> TimelineMerge.merge(current, older, capTail = false) }
            }
        }
    }

    // ── Tab → subscription logic ─────────────────────────────────────────

    private suspend fun collectProfileSubscription(pubkey: String, group: SubGroup) {
        val sameTimeline = lastSubGroup == group
        val tab = if (group == SubGroup.LONGFORM) ProfileTab.LONGFORM else ProfileTab.NOTES
        val generation = ++subscriptionGeneration
        fun acceptsEvents() = screenActive.value && subscriptionGeneration == generation

        val kinds = profileKindsForTab(tab)
        val writeRelays = memoryEventStore.writeRelaysFor(pubkey).ifEmpty { GLOBAL_RELAY_URLS }
        val limit = if (tab == ProfileTab.LONGFORM) 100 else 300
        // Cover/uncover keeps loaded pages. Only a user-selected group seeds a new timeline.
        if (!sameTimeline) {
            _events.value = withContext(Dispatchers.Default) {
                memoryEventStore.userEvents(pubkey, kinds.toSet(), 300)
            }
        }
        _isLoading.value = _events.value.isEmpty()
        lastSubGroup = group

        val handle = timelineService.subscribeTimeline(
            subRequests = listOf(SubRequest(
                urls = writeRelays,
                filter = NostrFilter(kinds = kinds, authors = listOf(pubkey), limit = limit),
            )),
            onEvents = { batch, eosed ->
                if (!acceptsEvents()) return@subscribeTimeline
                if (batch.isNotEmpty()) {
                    _events.update { TimelineMerge.merge(it, batch) }
                }
                if (_events.value.isNotEmpty() || eosed) _isLoading.value = false
            },
            onNew = { event ->
                if (acceptsEvents()) _events.update { TimelineMerge.merge(it, listOf(event)) }
            },
        )
        try {
            currentCoroutineContext().ensureActive()
            currentHandle = handle
            awaitCancellation()
        } finally {
            if (subscriptionGeneration == generation) subscriptionGeneration++
            handle.close()
            if (currentHandle === handle) currentHandle = null
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    override fun onCleared() {
        setScreenActive(false)
        currentHandle?.close()
        currentHandle = null
        super.onCleared()
    }

    private companion object {
        const val FEED_DISPLAY_CAP = 500
        const val FEED_SAMPLE_MS = 100L
        const val PROFILE_EVENT_OFFSET = 3

        /** Notes+Replies share note/repost/native-media kinds; Longform is [30023]. */
        enum class SubGroup { NOTES_REPLIES, LONGFORM }

        fun subGroupFor(tab: ProfileTab): SubGroup = when (tab) {
            ProfileTab.NOTES, ProfileTab.REPLIES -> SubGroup.NOTES_REPLIES
            ProfileTab.LONGFORM -> SubGroup.LONGFORM
        }

    }
}
