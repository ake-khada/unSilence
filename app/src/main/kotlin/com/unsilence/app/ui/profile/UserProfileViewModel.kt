package com.unsilence.app.ui.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.dao.RelayConfigDao
import com.unsilence.app.data.db.dao.RelayListDao
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.CardHydrator
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.UserRepository
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
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
    private val relayListDao: RelayListDao,
    private val relayConfigDao: RelayConfigDao,
    private val cardHydrator: CardHydrator,
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

    // ── Growing query window for pagination ────────────────────────────
    private val _displayLimit = MutableStateFlow(200)

    @OptIn(ExperimentalCoroutinesApi::class)
    val postsFlow: Flow<List<FeedRow>> =
        combine(_pubkeyHex.filterNotNull(), _displayLimit) { pk, limit -> pk to limit }
            .flatMapLatest { (pk, limit) -> memoryEventStore.userFeedFlow(pk, limit = limit) }

    // ── Profile tabs ──────────────────────────────────────────────────
    val selectedTab = MutableStateFlow(ProfileTab.NOTES)

    @OptIn(ExperimentalCoroutinesApi::class)
    val tabPostsFlow: Flow<List<FeedRow>> =
        combine(_pubkeyHex.filterNotNull(), selectedTab) { pk, tab -> pk to tab }
            .flatMapLatest { (pk, tab) ->
                when (tab) {
                    ProfileTab.NOTES    -> memoryEventStore.userFeedFlow(pk, contentFilter = 1)
                    ProfileTab.REPLIES  -> memoryEventStore.userFeedFlow(pk, contentFilter = 2)
                    ProfileTab.LONGFORM -> memoryEventStore.userFeedFlow(pk, kinds = setOf(30023))
                }
            }

    // ── Pagination state ───────────────────────────────────────────────
    private var oldestTimestamp = Long.MAX_VALUE
    private var fetching = false
    private var outboxRelayUrls: List<String> = emptyList()

    // ── Profile lookup for repost original authors ─────────────────────
    private val profileCache = ConcurrentHashMap<String, StateFlow<UserEntity?>>()

    fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
        profileCache.getOrPut(pubkey) {
            memoryEventStore.userEntityFlow(pubkey)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }

    val isLoadingPosts = MutableStateFlow(true)

    /** Approximate follower count from NIP-45 COUNT via antiprimal.net. */
    val followerCount = MutableStateFlow<Long?>(null)
    /** Following count parsed from the user's kind-3 event p-tags. */
    val followingCount = MutableStateFlow<Long?>(null)

    private val engagementFetchedIds = mutableSetOf<String>()
    private var lastHydratedBatchIds = emptySet<String>()

    init {
        // Unified card hydration + engagement fetch as posts arrive
        viewModelScope.launch {
            postsFlow.collectLatest { rows ->
                isLoadingPosts.value = false

                // Only re-hydrate when the top-20 event IDs actually change (new events
                // entering the result set). Room re-emits on ANY write to the joined tables
                // (users, event_stats, events) even for data-only changes — without this
                // guard, every hydration write triggers a re-emission that re-runs hydration.
                val batch = rows.take(20)
                val batchIds = batch.map { it.id }.toSet()
                if (batchIds != lastHydratedBatchIds) {
                    lastHydratedBatchIds = batchIds
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        cardHydrator.hydrateVisibleCards(batch)
                    }
                }

                // Capped engagement fetch — one batch of 20 max, debounced
                val newEventIds = rows
                    .filter { it.kind != 6 }
                    .map { it.id }
                    .filter { it !in engagementFetchedIds }
                    .take(20)
                if (newEventIds.isNotEmpty()) {
                    engagementFetchedIds.addAll(newEventIds)
                    delay(500)
                    relayPool.fetchEngagementBatch(newEventIds)
                }
            }
        }
    }

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

                // Build kind-3 event with all p-tags
                val nowSeconds = System.currentTimeMillis() / 1000L
                val tags = newFollowList.map { arrayOf("p", it) }.toTypedArray()
                val template = EventTemplate<Event>(
                    createdAt = nowSeconds,
                    kind      = 3,
                    tags      = tags,
                    content   = "",
                )
                val signed = signingManager.sign(template) ?: return@launch

                // Publish to write relays + indexer relays
                val writeUrls = getWriteRelayUrls(myPubkey)
                val indexerUrls = relayConfigDao.getIndexerRelayUrls()
                val targetUrls = (writeUrls + indexerUrls).distinct()
                relayPool.publishToRelays(toEventJson(signed), targetUrls)

                // Optimistic local mutation via MES
                if (nowFollowing) {
                    memoryEventStore.removeFollow(myPubkey, targetPubkey)
                } else {
                    memoryEventStore.addFollow(myPubkey, targetPubkey)
                }
            } finally {
                followLoading.value = false
            }
        }
    }

    private suspend fun getWriteRelayUrls(pubkey: String): List<String> {
        val relayList = relayListDao.getByPubkey(pubkey) ?: return GLOBAL_RELAY_URLS
        return runCatching {
            Json.decodeFromString<List<String>>(relayList.writeRelays)
        }.getOrDefault(GLOBAL_RELAY_URLS)
    }

    private fun toEventJson(event: Event): String = buildJsonObject {
        put("id",         event.id)
        put("pubkey",     event.pubKey)
        put("created_at", event.createdAt)
        put("kind",       event.kind)
        put("tags",       buildJsonArray {
            event.tags.forEach { row ->
                add(buildJsonArray { row.forEach { cell -> add(JsonPrimitive(cell)) } })
            }
        })
        put("content",    event.content)
        put("sig",        event.sig)
    }.toString()

    fun loadProfile(pubkey: String) {
        if (_pubkeyHex.value == pubkey) return
        _pubkeyHex.value = pubkey
        // Reset pagination + deduplication state for new profile
        selectedTab.value = ProfileTab.NOTES
        _displayLimit.value = 200
        oldestTimestamp = Long.MAX_VALUE
        fetching = false
        engagementFetchedIds.clear()
        isLoadingPosts.value = true
        followerCount.value = null
        followingCount.value = null

        viewModelScope.launch {
            userRepository.fetchProfilesWithFanout(listOf(pubkey))
            outboxRelayUrls = resolveOutboxRelays(pubkey)
            relayPool.connect(outboxRelayUrls)
            relayPool.fetchUserPosts(pubkey, outboxRelayUrls)
        }

        // Fetch follower count via NIP-45 COUNT (cache in MES, not Room)
        viewModelScope.launch(Dispatchers.IO) {
            val (cached, cachedAt) = memoryEventStore.getFollowerCount(pubkey)
            val oneDayAgo = System.currentTimeMillis() / 1000 - MemoryEventStore.FOLLOWER_COUNT_TTL_SECONDS

            if (cached != null && cachedAt != null && cachedAt > oneDayAgo) {
                followerCount.value = cached
                return@launch
            }
            // Ensure antiprimal.net is connected before sending COUNT — it may have been
            // evicted by the 60s idle timer or not yet connected on fresh navigation.
            // forceEvict=true because the pool may be at cap with all PERSISTENT connections.
            relayPool.connectAndAwait(listOf("wss://antiprimal.net"), timeoutMs = 3_000, forceEvict = true)
            val count = relayPool.sendCount(
                relayUrl = "wss://antiprimal.net",
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

        // Fetch following count by parsing p-tags from kind-3 event
        viewModelScope.launch(Dispatchers.IO) {
            val count = relayPool.fetchFollowingCount(pubkey)
            if (count != null) followingCount.value = count
        }
    }

    /**
     * Called when user scrolls near bottom of post list.
     * 1. Increases the Room query limit (growing window)
     * 2. Fetches older posts from relays
     */
    fun loadMore(currentOldest: Long) {
        val pubkey = _pubkeyHex.value ?: return
        if (fetching || currentOldest >= oldestTimestamp) return
        fetching = true
        oldestTimestamp = currentOldest
        _displayLimit.value += 200

        relayPool.fetchOlderPosts(pubkey, currentOldest, outboxRelayUrls)

        // Allow next fetch after relay responses have had time to arrive
        viewModelScope.launch {
            delay(2_000)
            fetching = false
        }
    }

    /**
     * NIP-65 outbox routing: resolve the user's declared write relays (max 5).
     * Falls back to 5 general relays if no kind 10002 found.
     */
    private suspend fun resolveOutboxRelays(pubkey: String): List<String> {
        // Step 1: check Room cache
        var relayList = userRepository.getRelayList(pubkey)

        // Step 2: if not cached, fetch from indexer relays and wait
        if (relayList == null) {
            relayPool.fetchRelayLists(listOf(pubkey))
            relayList = withTimeoutOrNull(5_000) {
                var result: com.unsilence.app.data.db.entity.RelayListEntity? = null
                while (result == null) {
                    delay(500)
                    result = userRepository.getRelayList(pubkey)
                }
                result
            }
        }

        if (relayList == null) {
            Log.d(TAG, "No relay list found for $pubkey — using general relays")
            return GLOBAL_RELAY_URLS.take(5)
        }

        // Step 3: parse write relay URLs (max 5)
        val writeUrls = runCatching {
            Json.decodeFromString<List<String>>(relayList.writeRelays)
        }.getOrDefault(emptyList()).take(5)

        if (writeUrls.isNotEmpty()) {
            Log.d(TAG, "Resolved ${writeUrls.size} outbox relays for $pubkey")
        }
        return writeUrls.ifEmpty { GLOBAL_RELAY_URLS.take(5) }
    }
}
