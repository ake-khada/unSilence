package com.unsilence.app.ui.profile

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
import com.unsilence.app.data.relay.NostrFilter
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.SubRequest
import com.unsilence.app.data.relay.TimelineConsumer
import com.unsilence.app.data.relay.TimelineService
import com.unsilence.app.ui.feed.FeedContentFilter
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val timelineService: TimelineService,
    private val relayPreferencesStore: com.unsilence.app.data.relay.RelayPreferencesStore,
) : ViewModel() {

    val pubkeyHex: String? = keyManager.getPublicKeyHex()

    val npub: String? = pubkeyHex?.let { hex ->
        runCatching { hex.hexToByteArray().toNpub() }.getOrNull()
    }

    /** Live user metadata from MES (null until kind 0 arrives from relay). */
    val userFlow: Flow<UserEntity?> =
        if (pubkeyHex != null) memoryEventStore.userEntityFlow(pubkeyHex) else emptyFlow()

    // ── TimelineConsumer ─────────────────────────────────────────────────

    private val consumer = TimelineConsumer(
        timelineService = timelineService,
        memoryEventStore = memoryEventStore,
        ownerScope = viewModelScope,
    )

    val tabPostsFlow: StateFlow<List<FeedRow>> = consumer.feedRows
    val isLoadingPosts: StateFlow<Boolean> = consumer.isLoading

    // ── Profile tabs ─────────────────────────────────────────────────────

    val selectedTab = MutableStateFlow(ProfileTab.NOTES)

    fun selectTab(tab: ProfileTab) {
        selectedTab.value = tab
    }

    // Track last subscription group to optimize Notes↔Replies (filter-only swap)
    private var lastSubGroup: SubGroup? = null

    init {
        if (pubkeyHex != null) {
            viewModelScope.launch {
                selectedTab.collectLatest { tab ->
                    resubscribeForTab(pubkeyHex, tab)
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

    /** Live following count from MES follows index. */
    val followingCount: StateFlow<Int> = pubkeyHex?.let { pk ->
        memoryEventStore.followsFlow(pk).map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    } ?: MutableStateFlow(0)

    /** Approximate follower count from NIP-45 COUNT, cached in MES. */
    val followerCount = MutableStateFlow<Long?>(null)

    // ── User actions ─────────────────────────────────────────────────────

    fun onViewportChanged(first: Int, last: Int) {
        consumer.onViewportChanged(first)
    }

    fun loadMore(currentOldest: Long) {
        consumer.loadMore()
    }

    // ── Tab → subscription logic ─────────────────────────────────────────

    private fun resubscribeForTab(pubkey: String, tab: ProfileTab) {
        // Set content filter at render boundary
        val contentFilter = when (tab) {
            ProfileTab.NOTES, ProfileTab.LONGFORM -> FeedContentFilter.NOTES_ONLY
            ProfileTab.REPLIES -> FeedContentFilter.REPLIES_ONLY
        }
        consumer.setContentFilter(contentFilter)

        // Notes↔Replies share the same kinds — skip resubscribe, just filter
        val group = subGroupFor(tab)
        if (lastSubGroup == group) return
        lastSubGroup = group

        val kinds = kindsForTab(tab)
        val cached = memoryEventStore.userEvents(pubkey, kinds.toSet(), 300)
        val writeRelays = memoryEventStore.writeRelaysFor(pubkey)
            .ifEmpty { GLOBAL_RELAY_URLS }
        val limit = if (tab == ProfileTab.LONGFORM) 100 else 300

        consumer.subscribe(
            subRequests = listOf(SubRequest(
                urls = writeRelays,
                filter = NostrFilter(
                    kinds = kinds,
                    authors = listOf(pubkey),
                    limit = limit,
                ),
            )),
            initialCachedEvents = cached,
        )
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
            relayPool.publish(toEventJson(signed))

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

    override fun onCleared() {
        consumer.close()
        super.onCleared()
    }

    private companion object {
        /** Subscription group: Notes+Replies share kinds [1,6], Longform is [30023]. */
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
