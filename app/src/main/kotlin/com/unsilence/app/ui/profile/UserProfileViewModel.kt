package com.unsilence.app.ui.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.EventStats
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.ReactionInfo
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.memory.ZapDetail
import com.unsilence.app.data.model.ReportType
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.data.relay.ImpersonationRisk
import com.unsilence.app.data.relay.ProtectedProfile
import com.unsilence.app.data.relay.detectImpersonationRisk
import com.unsilence.app.data.relay.isProtectedWotLookup
import com.unsilence.app.data.relay.protectedProfileFor
import com.unsilence.app.data.relay.toEventJson
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.NostrFilter
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.SubRequest
import com.unsilence.app.data.relay.TimelineMerge
import com.unsilence.app.data.relay.TimelineService
import com.unsilence.app.data.relay.WotHydrationCoalescer
import com.unsilence.app.data.relay.wotLookupSnapshot
import com.unsilence.app.data.relay.wotSubjectsForFeedRows
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.data.repository.MuteListRepository
import com.unsilence.app.data.repository.MuteResult
import com.unsilence.app.data.repository.ReportRepository
import com.unsilence.app.ui.feed.FeedContentFilter
import com.unsilence.app.ui.shared.TimelineCardData
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "UserProfileVM"

data class ProfileWotProvenance(
    val providerName: String,
    val relayHint: String,
    val lastFetchAt: Long,
)

@HiltViewModel
class UserProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
    private val relayPreferencesStore: com.unsilence.app.data.relay.RelayPreferencesStore,
    private val timelineService: TimelineService,
    private val profilePipeline: com.unsilence.app.data.relay.ProfilePipeline,
    private val wotHydrationCoalescer: WotHydrationCoalescer,
    private val timelineCardData: TimelineCardData,
    private val muteListRepository: MuteListRepository,
    private val reportRepository: ReportRepository,
) : ViewModel() {

    private val _pubkeyHex = MutableStateFlow<String?>(null)
    val pubkeyHex: StateFlow<String?> = _pubkeyHex.asStateFlow()
    private val myPubkey: String? = keyManager.getPublicKeyHex()

    val npub: String?
        get() = _pubkeyHex.value?.let { hex ->
            runCatching { hex.hexToByteArray().toNpub() }.getOrNull()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val userFlow: Flow<UserEntity?> = _pubkeyHex
        .filterNotNull()
        .flatMapLatest { memoryEventStore.userEntityFlow(it) }

    // ── Timeline state (mirrors FeedViewModel pattern) ───────────────────────

    private val _events = MutableStateFlow<List<NostrEvent>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
    private val _isAtTop = MutableStateFlow(true)
    private val _contentFilter = MutableStateFlow(FeedContentFilter.NOTES_ONLY)
    private var currentHandle: TimelineService.TimelineHandle? = null
    private val _wotSubjects = MutableStateFlow<Set<String>>(emptySet())

    val isLoadingPosts: StateFlow<Boolean> = _isLoading.asStateFlow()

    @OptIn(FlowPreview::class)
    val tabPostsFlow: StateFlow<List<FeedRow>> =
        combine(_events, _contentFilter, userFlow, memoryEventStore.feedSignalFlow) { events, cf, profile, _ ->
            if (events.isEmpty()) return@combine emptyList()
            val displayed = events.asSequence()
                .filterNot { memoryEventStore.isDeleted(it) }
                .filter { matchesContentFilter(it, cf) }
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

    /** NIP-36 sensitive-content display mode (shared with feed). */
    val sensitiveContentMode: StateFlow<com.unsilence.app.data.memory.SensitiveContentMode> =
        relayPreferencesStore.sensitiveContentModeFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly,
                com.unsilence.app.data.memory.SensitiveContentMode.BLUR)

    val wotLookups: StateFlow<Map<String, WotLookup>> =
        combine(_wotSubjects, memoryEventStore.wotSignalFlow) { subjects, _ ->
            wotLookupSnapshot(subjects, memoryEventStore::wotFor)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val profileWotLookup: StateFlow<WotLookup> =
        combine(_pubkeyHex, memoryEventStore.wotSignalFlow) { target, _ ->
            target?.let { memoryEventStore.wotFor(it) } ?: WotLookup.Pending
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WotLookup.Pending)

    val impersonationRisk: StateFlow<ImpersonationRisk?> =
        combine(
            userFlow,
            profileWotLookup,
            memoryEventStore.wotSignalFlow,
            memoryEventStore.profileSignalFlow,
        ) { user, lookup, _, _ ->
            user?.let {
                detectImpersonationRisk(
                    candidate = it,
                    lookup = lookup,
                    protectedProfiles = buildProtectedProfiles(),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
            .stateIn(viewModelScope, SharingStarted.Eagerly, FeedWotDisplayMode.NUMBERS)

    // ── Profile tabs ─────────────────────────────────────────────────────

    val selectedTab = MutableStateFlow(ProfileTab.NOTES)

    // Track last subscription group to optimize Notes↔Replies (filter-only swap)
    private var lastSubGroup: SubGroup? = null
    private var lastSubPubkey: String? = null

    // ── Profile lookup for repost original authors ───────────────────────

    fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
        timelineCardData.profileFlow(pubkey, viewModelScope)

    // ── Per-event stats (matches FeedViewModel.statsFlow) ────────────────

    fun statsFlow(eventId: String): StateFlow<EventStats> =
        timelineCardData.statsFlow(eventId, viewModelScope)

    fun zapDetailsForEvent(eventId: String): List<ZapDetail> =
        timelineCardData.zapDetailsForEvent(eventId)
    fun repostPubkeysForEvent(eventId: String): List<String> =
        timelineCardData.repostPubkeysForEvent(eventId)
    fun reactionsForEvent(eventId: String): List<ReactionInfo> =
        timelineCardData.reactionsForEvent(eventId)

    /** Approximate follower count from NIP-45 COUNT via antiprimal.net. */
    val followerCount = MutableStateFlow<Long?>(null)
    /** Following count parsed from the user's kind-3 event p-tags. */
    val followingCount = MutableStateFlow<Long?>(null)

    val isOwnProfile: StateFlow<Boolean> = _pubkeyHex
        .map { target -> target != null && target == myPubkey }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isMuted: StateFlow<Boolean> = combine(
        _pubkeyHex,
        memoryEventStore.ownMuteListFlow(),
    ) { target, muteList ->
        target != null && muteList.mutesPubkey(target)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether the logged-in user follows the viewed pubkey. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val isFollowing: Flow<Boolean> = if (myPubkey != null) {
        _pubkeyHex
            .filterNotNull()
            .flatMapLatest { target ->
                memoryEventStore.followsFlow(myPubkey).map { target in it }
            }
    } else {
        MutableStateFlow(false)
    }

    val followLoading = MutableStateFlow(false)

    init {
        // Combine pubkey + tab → resubscribe. Drives timeline lifecycle.
        viewModelScope.launch {
            @OptIn(ExperimentalCoroutinesApi::class)
            combine(_pubkeyHex.filterNotNull(), selectedTab) { pk, tab -> pk to tab }
                .collectLatest { (pk, tab) ->
                    resubscribeForTab(pk, tab)
                }
        }
    }

    fun loadProfile(pubkey: String) {
        if (_pubkeyHex.value == pubkey) return
        // Reset subscription tracking for new profile
        currentHandle?.close()
        currentHandle = null
        lastSubGroup = null
        lastSubPubkey = null
        _pubkeyHex.value = pubkey
        memoryEventStore.viewedPubkey = pubkey
        selectedTab.value = ProfileTab.NOTES
        followerCount.value = null
        followingCount.value = null
        _events.value = emptyList()
        _contentFilter.value = FeedContentFilter.NOTES_ONLY
        _wotSubjects.value = setOf(pubkey)
        wotHydrationCoalescer.requestProfileHydration(pubkey)

        // Fetch profile metadata via NIP-65 fanout
        viewModelScope.launch {
            userRepository.fetchProfilesWithFanout(listOf(pubkey))
        }

        // Fetch follower count via NIP-45 COUNT (MES-cached, deduped in ProfilePipeline)
        viewModelScope.launch(Dispatchers.IO) {
            profilePipeline.fetchFollowerCount(pubkey)?.let { followerCount.value = it }
        }

        // Fetch following count
        viewModelScope.launch(Dispatchers.IO) {
            val count = relayPool.fetchFollowingCount(pubkey)
            if (count != null) followingCount.value = count
        }

        // Eager pipeline: refs + engagement pre-fetched in batch.
        // viewedPubkey is set above (line 181) BEFORE this call —
        // the pipeline's eviction anchor relies on it being set so that
        // events arriving during fetch are protected from mid-fetch eviction.
        val isOwn = pubkey == myPubkey
        viewModelScope.launch(Dispatchers.IO) {
            profilePipeline.loadProfile(
                pubkey = pubkey,
                isOwn = isOwn,
                anchorPolicy = if (isOwn) com.unsilence.app.data.relay.AnchorPolicy.OWN
                    else com.unsilence.app.data.relay.AnchorPolicy.VIEWED,
            )
        }
    }

    // ── User actions ─────────────────────────────────────────────────────

    fun muteUser(): MuteResult? {
        val targetPubkey = _pubkeyHex.value ?: return null
        if (targetPubkey == myPubkey) return null
        return muteListRepository.muteUser(targetPubkey)
    }

    fun unmuteUser(): MuteResult? {
        val targetPubkey = _pubkeyHex.value ?: return null
        if (targetPubkey == myPubkey) return null
        return muteListRepository.unmuteUser(targetPubkey)
    }

    fun reportProfile(type: ReportType) {
        val targetPubkey = _pubkeyHex.value ?: return
        if (targetPubkey == myPubkey) return
        reportRepository.reportProfile(targetPubkey, type)
    }

    fun onViewportChanged(first: Int, last: Int, isScrolling: Boolean = false) {
        val atTop = first <= 0
        if (_isAtTop.value != atTop) _isAtTop.value = atTop
        requestVisibleWotHydration(first, last)
        // Engagement pre-fetched by ProfilePipeline — no viewport-driven hydration needed.
    }

    private fun requestVisibleWotHydration(first: Int, last: Int) {
        val viewed = _pubkeyHex.value
        val posts = tabPostsFlow.value
        val dataFirst = (first - PROFILE_EVENT_OFFSET).coerceAtLeast(0)
        val dataLast = (last - PROFILE_EVENT_OFFSET).coerceAtMost(posts.lastIndex)
        val subjects = buildSet {
            viewed?.let { add(it) }
            if (dataFirst <= dataLast) {
                addAll(
                    wotSubjectsForFeedRows(
                        posts.subList(dataFirst, dataLast + 1),
                        modelProvider = memoryEventStore::getEventModel,
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

    private fun buildProtectedProfiles(): List<ProtectedProfile> {
        val own = myPubkey ?: return emptyList()
        val protectedPubkeys = LinkedHashSet<String>()
        protectedPubkeys.addAll(memoryEventStore.getFollows(own).orEmpty())
        memoryEventStore.getWotAssertions().values
            .filter { isProtectedWotLookup(WotLookup.Scored(it)) }
            .forEach { protectedPubkeys.add(it.subjectPubkey) }
        return protectedPubkeys.mapNotNull { pubkey ->
            memoryEventStore.getUserEntity(pubkey)?.let(::protectedProfileFor)
        }
    }

    fun loadMore(currentOldest: Long) {
        val handle = currentHandle ?: return
        val until = _events.value.lastOrNull()?.createdAt ?: return
        viewModelScope.launch {
            val older = timelineService.fetchOlderTimeline(handle.timelineKey, until, 100)
            if (older.isNotEmpty()) {
                _events.update { current -> TimelineMerge.merge(current, older, capTail = false) }
            }
        }
    }

    // ── Tab → subscription logic ─────────────────────────────────────────

    private fun resubscribeForTab(pubkey: String, tab: ProfileTab) {
        // Set content filter at render boundary
        val contentFilter = when (tab) {
            ProfileTab.NOTES, ProfileTab.LONGFORM -> FeedContentFilter.NOTES_ONLY
            ProfileTab.REPLIES -> FeedContentFilter.REPLIES_ONLY
        }
        _contentFilter.value = contentFilter

        // Notes↔Replies share the same kinds — skip resubscribe, just filter
        val group = subGroupFor(tab)
        if (lastSubPubkey == pubkey && lastSubGroup == group) return
        lastSubPubkey = pubkey
        lastSubGroup = group

        // Close previous handle
        currentHandle?.close()
        currentHandle = null

        val kinds = kindsForTab(tab)
        val cached = memoryEventStore.userEvents(pubkey, kinds.toSet(), 300)
        val writeRelays = memoryEventStore.writeRelaysFor(pubkey)
            .ifEmpty { GLOBAL_RELAY_URLS }
        val limit = if (tab == ProfileTab.LONGFORM) 100 else 300

        // Pre-seed with MES-cached events for instant tab switching;
        // relay subscription merges on top as batches arrive.
        _events.value = cached
        _isLoading.value = cached.isEmpty()

        val subRequests = listOf(SubRequest(
            urls = writeRelays,
            filter = NostrFilter(
                kinds = kinds,
                authors = listOf(pubkey),
                limit = limit,
            ),
        ))

        viewModelScope.launch {
            val handle = timelineService.subscribeTimeline(
                subRequests = subRequests,
                onEvents = { batch, eosed ->
                    if (batch.isNotEmpty()) {
                        // Always route through merge — handles dedup, sort, and
                        // cap uniformly whether _events is empty or populated.
                        _events.update { current -> TimelineMerge.merge(current, batch) }
                    }
                    if (_events.value.isNotEmpty()) _isLoading.value = false
                    if (eosed) _isLoading.value = false
                },
                onNew = { event ->
                    _events.update { current -> TimelineMerge.merge(current, listOf(event)) }
                },
            )
            currentHandle = handle
        }
    }

    // ── Follow / Unfollow ────────────────────────────────────────────────

    fun toggleFollow() {
        val targetPubkey = _pubkeyHex.value ?: return
        if (myPubkey == null) return
        if (followLoading.value) return
        followLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentFollows = memoryEventStore.getFollows(myPubkey)?.toList() ?: emptyList()
                val nowFollowing = targetPubkey in currentFollows
                val newFollowList = if (nowFollowing) {
                    currentFollows.filter { it != targetPubkey }
                } else {
                    currentFollows + targetPubkey
                }

                val nowSeconds = System.currentTimeMillis() / 1000L
                val tags = newFollowList.map { arrayOf("p", it) }.toTypedArray()
                val template = EventTemplate<Event>(
                    createdAt = nowSeconds,
                    kind      = 3,
                    tags      = tags,
                    content   = "",
                )
                val signed = signingManager.sign(template) ?: return@launch

                // Optimistic local mutation FIRST
                if (nowFollowing) {
                    memoryEventStore.removeFollow(myPubkey, targetPubkey)
                } else {
                    memoryEventStore.addFollow(myPubkey, targetPubkey)
                }

                val writeUrls = getWriteRelayUrls(myPubkey)
                val indexerUrls = relayPreferencesStore.indexerRelayUrlsSnapshot()
                val targetUrls = (writeUrls + indexerUrls).distinct()
                try {
                    relayPool.publishToRelays(toEventJson(signed), targetUrls)
                } catch (e: Exception) {
                    // Rollback optimistic mutation on publish failure
                    if (nowFollowing) {
                        memoryEventStore.addFollow(myPubkey, targetPubkey)
                    } else {
                        memoryEventStore.removeFollow(myPubkey, targetPubkey)
                    }
                    Log.w(TAG, "Follow publish failed, rolled back", e)
                }
            } finally {
                followLoading.value = false
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun getWriteRelayUrls(pubkey: String): List<String> =
        memoryEventStore.getRelayList(pubkey)?.write ?: GLOBAL_RELAY_URLS

    private fun matchesContentFilter(evt: NostrEvent, cf: FeedContentFilter): Boolean {
        // kind-6 AND kind-16 reposts carry a rootId (the reposted event) → roots, not replies.
        val isRepostKind = evt.kind == 6 || evt.kind == 16
        return when (cf) {
            FeedContentFilter.NOTES_ONLY ->
                isRepostKind || (evt.replyToId == null && evt.rootId == null)
            FeedContentFilter.REPLIES_ONLY ->
                !isRepostKind && (evt.replyToId != null || evt.rootId != null)
        }
    }

    private fun MuteList?.mutesPubkey(pubkey: String): Boolean =
        this != null && (pubkey in pubkeys || pubkey in privatePubkeys)

    override fun onCleared() {
        currentHandle?.close()
        currentHandle = null
        memoryEventStore.viewedPubkey = null
        super.onCleared()
    }

    private companion object {
        const val FEED_DISPLAY_CAP = 500
        const val FEED_SAMPLE_MS = 100L
        const val PROFILE_EVENT_OFFSET = 3

        enum class SubGroup { NOTES_REPLIES, LONGFORM }

        fun subGroupFor(tab: ProfileTab): SubGroup = when (tab) {
            ProfileTab.NOTES, ProfileTab.REPLIES -> SubGroup.NOTES_REPLIES
            ProfileTab.LONGFORM -> SubGroup.LONGFORM
        }

        fun kindsForTab(tab: ProfileTab): List<Int> = when (tab) {
            ProfileTab.NOTES, ProfileTab.REPLIES -> listOf(1, 6, 16, 20, 21, 22, 1068)
            ProfileTab.LONGFORM -> listOf(30023)
        }
    }
}
