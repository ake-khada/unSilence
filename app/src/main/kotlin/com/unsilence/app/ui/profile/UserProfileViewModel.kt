package com.unsilence.app.ui.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.toEventJson
import com.unsilence.app.data.relay.ANTIPRIMAL_RELAY_URL
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.NostrFilter
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.SubRequest
import com.unsilence.app.data.relay.TimelineMerge
import com.unsilence.app.data.relay.TimelineService
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.ui.feed.FeedContentFilter
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

private const val TAG = "UserProfileVM"

@HiltViewModel
class UserProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
    private val relayPreferencesStore: com.unsilence.app.data.relay.RelayPreferencesStore,
    private val timelineService: TimelineService,
) : ViewModel() {

    private val _pubkeyHex = MutableStateFlow<String?>(null)
    val pubkeyHex: StateFlow<String?> = _pubkeyHex.asStateFlow()

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

    val isLoadingPosts: StateFlow<Boolean> = _isLoading.asStateFlow()

    @OptIn(FlowPreview::class)
    val tabPostsFlow: StateFlow<List<FeedRow>> =
        combine(_events, _contentFilter) { events, cf ->
            if (events.isEmpty()) return@combine emptyList()
            val displayed = events.asSequence()
                .filter { matchesContentFilter(it, cf) }
                .take(FEED_DISPLAY_CAP)
                .toList()
            if (displayed.isEmpty()) return@combine emptyList()
            val ids = displayed.map { it.id }.toSet()
            val rowsById = memoryEventStore.feedRowsByIds(ids).associateBy { it.id }
            displayed.map { evt -> rowsById[evt.id] ?: memoryEventStore.synthesizeFeedRow(evt) }
        }
            .sample(FEED_SAMPLE_MS)
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── Profile tabs ─────────────────────────────────────────────────────

    val selectedTab = MutableStateFlow(ProfileTab.NOTES)

    // Track last subscription group to optimize Notes↔Replies (filter-only swap)
    private var lastSubGroup: SubGroup? = null
    private var lastSubPubkey: String? = null

    // ── Profile lookup for repost original authors ───────────────────────

    private val profileCache = ConcurrentHashMap<String, StateFlow<UserEntity?>>()

    fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
        profileCache.getOrPut(pubkey) {
            memoryEventStore.userEntityFlow(pubkey)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }

    // ── Per-event stats (matches FeedViewModel.statsFlow) ────────────────
    private val statsCache = ConcurrentHashMap<String, StateFlow<com.unsilence.app.data.memory.EventStats>>()

    fun statsFlow(eventId: String): StateFlow<com.unsilence.app.data.memory.EventStats> =
        statsCache.getOrPut(eventId) {
            memoryEventStore.statsFlow(eventId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.unsilence.app.data.memory.EventStats.EMPTY)
        }

    /** Approximate follower count from NIP-45 COUNT via antiprimal.net. */
    val followerCount = MutableStateFlow<Long?>(null)
    /** Following count parsed from the user's kind-3 event p-tags. */
    val followingCount = MutableStateFlow<Long?>(null)

    private val myPubkey: String? = keyManager.getPublicKeyHex()

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

        // Fetch profile metadata via NIP-65 fanout
        viewModelScope.launch {
            userRepository.fetchProfilesWithFanout(listOf(pubkey))
        }

        // Fetch follower count via NIP-45 COUNT
        viewModelScope.launch(Dispatchers.IO) {
            val (cached, cachedAt) = memoryEventStore.getFollowerCount(pubkey)
            val oneDayAgo = System.currentTimeMillis() / 1000 - MemoryEventStore.FOLLOWER_COUNT_TTL_SECONDS

            if (cached != null && cachedAt != null && cachedAt > oneDayAgo) {
                followerCount.value = cached
                return@launch
            }
            relayPool.connectAndAwait(listOf(ANTIPRIMAL_RELAY_URL), timeoutMs = 3_000, forceEvict = true)
            val count = relayPool.sendCount(
                relayUrl = ANTIPRIMAL_RELAY_URL,
                filter = buildJsonObject {
                    put("kinds", buildJsonArray { add(JsonPrimitive(3)) })
                    put("#p", buildJsonArray { add(JsonPrimitive(pubkey)) })
                },
            )
            if (count != null) {
                followerCount.value = count
                memoryEventStore.cacheFollowerCount(pubkey, count)
            }
        }

        // Fetch following count
        viewModelScope.launch(Dispatchers.IO) {
            val count = relayPool.fetchFollowingCount(pubkey)
            if (count != null) followingCount.value = count
        }
    }

    // ── User actions ─────────────────────────────────────────────────────

    fun onViewportChanged(first: Int, last: Int) {
        val atTop = first <= 0
        if (_isAtTop.value != atTop) _isAtTop.value = atTop
    }

    fun loadMore(currentOldest: Long) {
        val handle = currentHandle ?: return
        val until = _events.value.lastOrNull()?.createdAt ?: return
        viewModelScope.launch {
            val older = timelineService.loadMoreTimeline(handle.timelineKey, until, 100)
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

    private fun matchesContentFilter(evt: NostrEvent, cf: FeedContentFilter): Boolean =
        when (cf) {
            FeedContentFilter.NOTES_ONLY ->
                evt.kind == 6 || (evt.replyToId == null && evt.rootId == null)
            FeedContentFilter.REPLIES_ONLY ->
                evt.kind != 6 && (evt.replyToId != null || evt.rootId != null)
        }

    override fun onCleared() {
        currentHandle?.close()
        currentHandle = null
        memoryEventStore.viewedPubkey = null
        super.onCleared()
    }

    private companion object {
        const val FEED_DISPLAY_CAP = 500
        const val FEED_SAMPLE_MS = 100L

        enum class SubGroup { NOTES_REPLIES, LONGFORM }

        fun subGroupFor(tab: ProfileTab): SubGroup = when (tab) {
            ProfileTab.NOTES, ProfileTab.REPLIES -> SubGroup.NOTES_REPLIES
            ProfileTab.LONGFORM -> SubGroup.LONGFORM
        }

        fun kindsForTab(tab: ProfileTab): List<Int> = when (tab) {
            ProfileTab.NOTES, ProfileTab.REPLIES -> listOf(1, 6)
            ProfileTab.LONGFORM -> listOf(30023)
        }
    }
}
