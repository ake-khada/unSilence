package com.unsilence.app.ui.profile

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.blossom.BlossomImageUploader
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
import com.unsilence.app.data.relay.wotLookupSnapshot
import com.unsilence.app.data.relay.wotSubjectsForFeedRows
import com.unsilence.app.data.repository.EditableProfileMetadata
import com.unsilence.app.data.repository.ProfileMetadataPublisher
import com.unsilence.app.data.repository.ProfilePublishResult
import com.unsilence.app.ui.feed.FeedContentFilter
import com.unsilence.app.ui.shared.TimelineCardData
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

private const val TAG = "ProfileVM"

sealed interface ProfileSaveState {
    data object Idle : ProfileSaveState
    data object Saving : ProfileSaveState
    data object Saved : ProfileSaveState
    data class Failed(val message: String) : ProfileSaveState
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val profileMetadataPublisher: ProfileMetadataPublisher,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val timelineService: TimelineService,
    private val relayPreferencesStore: com.unsilence.app.data.relay.RelayPreferencesStore,
    private val profilePipeline: com.unsilence.app.data.relay.ProfilePipeline,
    private val wotHydrationCoalescer: WotHydrationCoalescer,
    private val blossomImageUploader: BlossomImageUploader,
    private val timelineCardData: TimelineCardData,
) : ViewModel() {

    val pubkeyHex: String? = keyManager.getPublicKeyHex()

    val npub: String? = pubkeyHex?.let { hex ->
        runCatching { hex.hexToByteArray().toNpub() }.getOrNull()
    }

    /** NIP-36 sensitive-content display mode (shared with feed). */
    val sensitiveContentMode: StateFlow<com.unsilence.app.data.memory.SensitiveContentMode> =
        relayPreferencesStore.sensitiveContentModeFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly,
                com.unsilence.app.data.memory.SensitiveContentMode.BLUR)

    private val _uploadingAvatar = MutableStateFlow(false)
    val uploadingAvatar: StateFlow<Boolean> = _uploadingAvatar.asStateFlow()

    private val _uploadingBanner = MutableStateFlow(false)
    val uploadingBanner: StateFlow<Boolean> = _uploadingBanner.asStateFlow()

    private val _profileSaveState = MutableStateFlow<ProfileSaveState>(ProfileSaveState.Idle)
    val profileSaveState: StateFlow<ProfileSaveState> = _profileSaveState.asStateFlow()
    private var profileSaveJob: Job? = null
    private val profileSaveGeneration = AtomicLong(0L)

    /** Init coroutines write this field, so it must be initialized before any init block. */
    val followerCount = MutableStateFlow<Long?>(null)

    fun uploadProfileImage(
        uri: Uri,
        onUrl: (String) -> Unit,
        onError: (String) -> Unit,
        isBanner: Boolean,
    ) {
        val loading = if (isBanner) _uploadingBanner else _uploadingAvatar
        viewModelScope.launch(Dispatchers.IO) {
            loading.value = true
            try {
                val maxDim = if (isBanner) 1600 else 512
                val url = blossomImageUploader.upload(uri, maxDimension = maxDim)
                launch(Dispatchers.Main) { onUrl(url) }
            } catch (e: Exception) {
                Log.w(TAG, "Profile image upload failed", e)
                launch(Dispatchers.Main) { onError(e.message ?: "Upload failed") }
            } finally {
                loading.value = false
            }
        }
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
            .stateIn(viewModelScope, SharingStarted.Eagerly, FeedWotDisplayMode.NUMBERS)

    val relayCount: StateFlow<Int?> = pubkeyHex?.let { pubkey ->
        memoryEventStore.profileRelayCountFlow(pubkey)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    } ?: MutableStateFlow(null)

    // ── Profile tabs ─────────────────────────────────────────────────────

    val selectedTab = MutableStateFlow(ProfileTab.NOTES)

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
                selectedTab.collectLatest { tab ->
                    resubscribeForTab(pubkeyHex, tab)
                }
            }

            // Fetch the integrity-checked follower count (MES-cached and pipeline-deduped).
            viewModelScope.launch(Dispatchers.IO) {
                profilePipeline.fetchFollowerCount(pubkeyHex)?.let { followerCount.value = it }
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

    /** Live following count from MES follows index. */
    val followingCount: StateFlow<Int> = pubkeyHex?.let { pk ->
        memoryEventStore.followsFlow(pk).map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    } ?: MutableStateFlow(0)

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

    fun loadMore() {
        val handle = currentHandle ?: return
        // _events is the contiguous mixed-kind timeline. Paginating from its tail
        // avoids re-requesting filtered-out replies between the last visible note
        // and the actual cache boundary.
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
        if (lastSubGroup == group) return
        lastSubGroup = group

        // Close previous handle
        currentHandle?.close()
        currentHandle = null

        val kinds = profileKindsForTab(tab)
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

    // ── Save profile ─────────────────────────────────────────────────────

    internal fun saveProfile(
        original: EditableProfileMetadata,
        edited: EditableProfileMetadata,
    ) {
        if (!_profileSaveState.compareAndSet(ProfileSaveState.Idle, ProfileSaveState.Saving)) return
        val generation = profileSaveGeneration.incrementAndGet()
        val ownPubkey = pubkeyHex
        if (ownPubkey == null) {
            _profileSaveState.value = profileSaveStateFor(ProfilePublishResult.AccountUnavailable)
            return
        }

        profileSaveJob = viewModelScope.launch(Dispatchers.IO) {
            val result = profileMetadataPublisher.publish(ownPubkey, original, edited)
            // SigningManager deliberately converts Amber cancellation to null.
            // The generation fence prevents that late result from resurrecting
            // a failed state after the user has already cancelled and left.
            if (profileSaveGeneration.get() == generation) {
                _profileSaveState.value = profileSaveStateFor(result)
            }
        }
    }

    fun consumeProfileSaveResult() {
        _profileSaveState.update { state ->
            if (state == ProfileSaveState.Saving) state else ProfileSaveState.Idle
        }
    }

    fun cancelProfileSave() {
        profileSaveGeneration.incrementAndGet()
        profileSaveJob?.cancel()
        profileSaveJob = null
        _profileSaveState.value = ProfileSaveState.Idle
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    override fun onCleared() {
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

internal fun profileSaveStateFor(result: ProfilePublishResult): ProfileSaveState = when (result) {
    ProfilePublishResult.Success -> ProfileSaveState.Saved
    ProfilePublishResult.AccountUnavailable ->
        ProfileSaveState.Failed("No signing account is available.")
    ProfilePublishResult.FreshnessUnavailable ->
        ProfileSaveState.Failed("Could not refresh your latest profile from relays. Nothing was changed.")
    ProfilePublishResult.ProfileUnavailable ->
        ProfileSaveState.Failed("Your profile has not loaded yet. Connect and try again.")
    ProfilePublishResult.InvalidExistingProfile ->
        ProfileSaveState.Failed("Your existing profile could not be read safely. Nothing was changed.")
    ProfilePublishResult.SigningFailed ->
        ProfileSaveState.Failed("Profile signing was cancelled or failed.")
    ProfilePublishResult.ChangedWhileSigning ->
        ProfileSaveState.Failed("Your profile changed while signing. Review it and try again.")
    ProfilePublishResult.NoRelayAccepted ->
        ProfileSaveState.Failed("No relay accepted the profile update. Check your connection and try again.")
    ProfilePublishResult.SupersededAfterAcceptance ->
        ProfileSaveState.Failed("A newer profile update arrived. Reload and try again.")
}
