package com.unsilence.app.ui.profile

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.blossom.BlossomClient
import com.unsilence.app.data.blossom.BlossomServersStore
import com.unsilence.app.data.blossom.ImageCompressor
import com.unsilence.app.data.memory.EventStats
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.ReactionInfo
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.memory.ZapDetail
import com.unsilence.app.data.relay.toEventJson
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
import com.unsilence.app.ui.feed.FeedContentFilter
import com.unsilence.app.ui.shared.TimelineCardData
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

private const val TAG = "ProfileVM"

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val timelineService: TimelineService,
    private val relayPreferencesStore: com.unsilence.app.data.relay.RelayPreferencesStore,
    private val profilePipeline: com.unsilence.app.data.relay.ProfilePipeline,
    private val wotHydrationCoalescer: WotHydrationCoalescer,
    private val blossomClient: BlossomClient,
    private val imageCompressor: ImageCompressor,
    private val blossomServersStore: BlossomServersStore,
    private val contentResolver: ContentResolver,
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

    init {
        viewModelScope.launch { blossomServersStore.initialize() }
    }

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
                val rawBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Could not read image")
                val maxDim = if (isBanner) 1600 else 512
                val compressed = imageCompressor.compressImage(rawBytes, maxDim, 85)
                val server = blossomServersStore.selectedServer.value
                val blob = blossomClient.upload(compressed, "image/jpeg", server).getOrThrow()
                launch(Dispatchers.Main) { onUrl(blob.url) }
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

            // Fetch follower count via NIP-45 (MES-cached, deduped in ProfilePipeline)
            viewModelScope.launch(Dispatchers.IO) {
                profilePipeline.fetchFollowerCount(pubkeyHex)?.let { followerCount.value = it }
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

    /** Approximate follower count from NIP-45 COUNT, cached in MES. */
    val followerCount = MutableStateFlow<Long?>(null)

    // ── User actions ─────────────────────────────────────────────────────

    fun onViewportChanged(first: Int, last: Int, isScrolling: Boolean = false) {
        val atTop = first <= 0
        if (_isAtTop.value != atTop) _isAtTop.value = atTop
        requestVisibleWotHydration(first, last)
        // Engagement pre-fetched by ProfilePipeline — no viewport-driven hydration needed.
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
        if (lastSubGroup == group) return
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

    // ── Save profile ─────────────────────────────────────────────────────

    fun saveProfile(
        name: String,
        displayName: String,
        about: String,
        picture: String,
        banner: String,
        nip05: String,
        lud16: String,
        website: String,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val contentJson = buildJsonObject {
                if (name.isNotBlank())        put("name",         name.trim())
                if (displayName.isNotBlank()) put("display_name", displayName.trim())
                if (about.isNotBlank())       put("about",        about.trim())
                if (picture.isNotBlank())     put("picture",      picture.trim())
                if (banner.isNotBlank())      put("banner",       banner.trim())
                if (nip05.isNotBlank())       put("nip05",        nip05.trim())
                if (lud16.isNotBlank())       put("lud16",        lud16.trim())
                if (website.isNotBlank())     put("website",      website.trim())
            }.toString()

            val template = EventTemplate<Event>(
                createdAt = System.currentTimeMillis() / 1000L,
                kind      = 0,
                tags      = emptyArray(),
                content   = contentJson,
            )

            val signed = signingManager.sign(template) ?: return@launch

            // Optimistic local update — kind-0 is replaceable (newest wins), so insert
            // the just-signed event so the profile reflects IMMEDIATELY. Previously the
            // UI only updated via a relay echo of the broadcast; with the targeted
            // publish (H20c) we update locally instead of depending on an echo.
            val nowMs = System.currentTimeMillis()
            memoryEventStore.insert(
                NostrEvent(
                    id = signed.id,
                    pubkey = signed.pubKey,
                    kind = 0,
                    content = signed.content,
                    createdAt = signed.createdAt,
                    tags = emptyList(),
                    tagsJson = "[]",
                    sig = signed.sig,
                    relayUrl = "local",
                    replyToId = null,
                    rootId = null,
                    hasContentWarning = false,
                    contentWarningReason = null,
                    firstSeenAt = nowMs,
                    relaysSeen = ConcurrentHashMap.newKeySet<String>().apply { add("local") },
                ),
            )

            // kind-0 goes ONLY to own write + indexer relays (targeted) — not a raw
            // broadcast to every open socket (H20c).
            val writeUrls = pubkeyHex?.let { getWriteRelayUrls(it) }.orEmpty()
            val indexerUrls = relayPreferencesStore.indexerRelayUrlsSnapshot()
            val targetUrls = (writeUrls + indexerUrls).distinct()
            relayPool.publishToRelays(toEventJson(signed), targetUrls)

            launch(Dispatchers.Main) { onDone() }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun getWriteRelayUrls(pubkey: String): List<String> =
        memoryEventStore.getRelayList(pubkey)?.write ?: emptyList()

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

    override fun onCleared() {
        currentHandle?.close()
        currentHandle = null
        super.onCleared()
    }

    private companion object {
        const val FEED_DISPLAY_CAP = 500
        const val FEED_SAMPLE_MS = 100L
        const val PROFILE_EVENT_OFFSET = 3

        /** Subscription group: Notes+Replies share kinds [1,6,16], Longform is [30023]. */
        enum class SubGroup { NOTES_REPLIES, LONGFORM }

        fun subGroupFor(tab: ProfileTab): SubGroup = when (tab) {
            ProfileTab.NOTES, ProfileTab.REPLIES -> SubGroup.NOTES_REPLIES
            ProfileTab.LONGFORM -> SubGroup.LONGFORM
        }

        fun kindsForTab(tab: ProfileTab): List<Int> = when (tab) {
            ProfileTab.NOTES, ProfileTab.REPLIES -> listOf(1, 6, 16, 1068)
            ProfileTab.LONGFORM -> listOf(30023)
        }
    }
}
