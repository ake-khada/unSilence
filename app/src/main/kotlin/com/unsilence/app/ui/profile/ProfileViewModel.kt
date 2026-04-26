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
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
    private val userRepository: UserRepository,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val feedWindowLoader: FeedWindowLoader,
    private val relayPreferencesStore: com.unsilence.app.data.relay.RelayPreferencesStore,
    private val profileResolver: ProfileResolver,
    private val ogFetcher: OgFetcher,
    private val videoThumbnailCache: VideoThumbnailCache,
) : ViewModel() {

    val pubkeyHex: String? = keyManager.getPublicKeyHex()

    val npub: String? = pubkeyHex?.let { hex ->
        runCatching { hex.hexToByteArray().toNpub() }.getOrNull()
    }

    /** Live user metadata from MES (null until kind 0 arrives from relay). */
    val userFlow: Flow<UserEntity?> =
        if (pubkeyHex != null) memoryEventStore.userEntityFlow(pubkeyHex) else emptyFlow()

    // ── FeedWindow management ────────────────────────────────────────────
    @Volatile private var activeWindow: FeedWindow? = null
    private val windowCache = mutableMapOf<ProfileTab, FeedWindow>()
    private val _activeProfileKey = MutableStateFlow<WindowKey.Profile?>(null)

    // ── Profile tabs ──────────────────────────────────────────────────
    val selectedTab = MutableStateFlow(ProfileTab.NOTES)

    fun selectTab(tab: ProfileTab) {
        selectedTab.value = tab
        // Window swap happens via the collector in init
    }

    /** Live posts from the active window, newest-first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val tabPostsFlow: StateFlow<List<FeedRow>> = _activeProfileKey
        .flatMapLatest { key ->
            if (key != null) activeWindow?.snapshot?.map { it.rows } ?: flowOf(emptyList())
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Profile lookup for repost original authors ──────────────────────
    private val profileCache = ConcurrentHashMap<String, StateFlow<UserEntity?>>()

    fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
        profileCache.getOrPut(pubkey) {
            memoryEventStore.userEntityFlow(pubkey)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }

    /** Live following count from MES follows index. */
    val followingCount: StateFlow<Int> = pubkeyHex?.let { pk ->
        memoryEventStore.followsFlow(pk).map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    } ?: MutableStateFlow(0)

    /** Approximate follower count from NIP-45 COUNT, cached in MES. */
    val followerCount = MutableStateFlow<Long?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val isLoadingPosts: StateFlow<Boolean> = _activeProfileKey
        .flatMapLatest { key ->
            if (key != null) activeWindow?.snapshot?.map { it.isLoadingInitial } ?: flowOf(true)
            else flowOf(true)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    init {
        if (pubkeyHex != null) {
            // Tab-driven window lifecycle
            viewModelScope.launch {
                selectedTab.collectLatest { tab ->
                    val key = WindowKey.Profile(pubkeyHex, tab)
                    swapToWindow(key)
                }
            }

            // Fetch follower count via NIP-45 (cache in MES)
            viewModelScope.launch(Dispatchers.IO) {
                val (cached, cachedAt) = memoryEventStore.getFollowerCount(pubkeyHex)
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
                        put("#p", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
                    },
                )
                if (count != null) {
                    followerCount.value = count
                    memoryEventStore.cacheFollowerCount(pubkeyHex, count)
                }
            }
        }
    }

    fun onViewportChanged(first: Int, last: Int) {
        activeWindow?.onViewportChanged(first, last)
    }

    /** Keep signature — ignore param, delegate to window. */
    fun loadMore(currentOldest: Long) {
        activeWindow?.loadMore()
    }

    private fun swapToWindow(key: WindowKey.Profile) {
        activeWindow?.deactivate()

        // Check cache for a loaded window matching this tab
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

    /**
     * Builds and publishes a kind 0 (metadata) event from the provided fields.
     * Blank fields are omitted from the JSON payload.
     * [onDone] is called on the main thread once publishing completes.
     */
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
            relayPool.publish(toEventJson(signed))

            val writeUrls = pubkeyHex?.let { getWriteRelayUrls(it) }.orEmpty()
            val indexerUrls = relayPreferencesStore.indexerRelayUrlsSnapshot()
            val targetUrls = (writeUrls + indexerUrls).distinct()
            relayPool.publishToRelays(toEventJson(signed), targetUrls)

            launch(Dispatchers.Main) { onDone() }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun getWriteRelayUrls(pubkey: String): List<String> =
        memoryEventStore.getRelayList(pubkey)?.write ?: emptyList()

    override fun onCleared() {
        super.onCleared()
        windowCache.values.forEach { it.release() }
    }
}
