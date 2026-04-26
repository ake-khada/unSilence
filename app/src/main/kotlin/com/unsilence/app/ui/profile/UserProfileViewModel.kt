package com.unsilence.app.ui.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.toEventJson
import com.unsilence.app.data.relay.ANTIPRIMAL_RELAY_URL
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.OgFetcher
import com.unsilence.app.data.relay.ProfileResolver
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.ui.feed.FeedWindow
import com.unsilence.app.ui.feed.FeedWindowLoader
import com.unsilence.app.ui.feed.VideoThumbnailCache
import com.unsilence.app.ui.feed.WindowKey
import com.unsilence.app.ui.feed.WindowSnapshot
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val feedWindowLoader: FeedWindowLoader,
    private val profileResolver: ProfileResolver,
    private val ogFetcher: OgFetcher,
    private val videoThumbnailCache: VideoThumbnailCache,
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

    // ── FeedWindow management ────────────────────────────────────────────
    @Volatile private var activeWindow: FeedWindow? = null
    private val windowCache = mutableMapOf<ProfileTab, FeedWindow>()
    private val _activeProfileKey = MutableStateFlow<WindowKey.Profile?>(null)

    // ── Profile tabs ──────────────────────────────────────────────────
    val selectedTab = MutableStateFlow(ProfileTab.NOTES)

    /** Live posts from the active window, newest-first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val tabPostsFlow: StateFlow<List<FeedRow>> = _activeProfileKey
        .flatMapLatest { key ->
            if (key != null) activeWindow?.snapshot?.map { it.rows } ?: flowOf(emptyList())
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Profile lookup for repost original authors ─────────────────────
    private val profileCache = ConcurrentHashMap<String, StateFlow<UserEntity?>>()

    fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
        profileCache.getOrPut(pubkey) {
            memoryEventStore.userEntityFlow(pubkey)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val isLoadingPosts: StateFlow<Boolean> = _activeProfileKey
        .flatMapLatest { key ->
            if (key != null) activeWindow?.snapshot?.map { it.isLoadingInitial } ?: flowOf(true)
            else flowOf(true)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

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
        // Combine pubkey + tab → window key. Drives window lifecycle.
        viewModelScope.launch {
            @OptIn(ExperimentalCoroutinesApi::class)
            combine(_pubkeyHex.filterNotNull(), selectedTab) { pk, tab ->
                WindowKey.Profile(pk, tab)
            }
            .collectLatest { key ->
                swapToWindow(key)
            }
        }
    }

    fun loadProfile(pubkey: String) {
        if (_pubkeyHex.value == pubkey) return
        // Release cached windows for previous profile
        windowCache.values.forEach { it.release() }
        windowCache.clear()
        _pubkeyHex.value = pubkey
        memoryEventStore.viewedPubkey = pubkey
        selectedTab.value = ProfileTab.NOTES
        followerCount.value = null
        followingCount.value = null

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

    fun onViewportChanged(first: Int, last: Int) {
        activeWindow?.onViewportChanged(first, last)
    }

    /** Keep signature — ignore param, delegate to window. */
    fun loadMore(currentOldest: Long) {
        activeWindow?.loadMore()
    }

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

    private fun getWriteRelayUrls(pubkey: String): List<String> =
        memoryEventStore.getRelayList(pubkey)?.write ?: GLOBAL_RELAY_URLS

    private fun swapToWindow(key: WindowKey.Profile) {
        activeWindow?.deactivate()

        // Check cache for a loaded window matching this tab+pubkey
        val cached = windowCache[key.tab]
        if (cached != null && cached.key == key && cached.hasLoaded) {
            activeWindow = cached
            cached.activate()
            _activeProfileKey.value = key
            return
        }

        // Cold: create fresh, cache by tab
        val window = FeedWindow(
            key = key,
            mes = memoryEventStore,
            loader = feedWindowLoader,
            keyManager = keyManager,
            parentScope = viewModelScope,
            profileResolver = profileResolver,
            relayPool = relayPool,
            ogFetcher = ogFetcher,
            videoThumbnailCache = videoThumbnailCache,
        )
        windowCache[key.tab]?.release()
        windowCache[key.tab] = window
        activeWindow = window
        window.activate()
        _activeProfileKey.value = key
    }

    override fun onCleared() {
        super.onCleared()
        windowCache.values.forEach { it.release() }
        memoryEventStore.viewedPubkey = null
    }
}
