package com.unsilence.app.ui.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.db.dao.FollowDao
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.dao.RelayListDao
import com.unsilence.app.data.db.entity.FollowEntity
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.extractRepostAuthorPubkey
import com.unsilence.app.data.repository.EventRepository
import com.unsilence.app.data.repository.UserRepository
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
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
    private val eventRepository: EventRepository,
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
    private val followDao: FollowDao,
    private val relayListDao: RelayListDao,
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
        .flatMapLatest { userRepository.userFlow(it) }

    // ── Growing query window for pagination ────────────────────────────
    private val _displayLimit = MutableStateFlow(200)

    @OptIn(ExperimentalCoroutinesApi::class)
    val postsFlow: Flow<List<FeedRow>> =
        combine(_pubkeyHex.filterNotNull(), _displayLimit) { pk, limit -> pk to limit }
            .flatMapLatest { (pk, limit) -> eventRepository.userPostsFlow(pk, limit) }

    // ── Profile tabs ──────────────────────────────────────────────────
    val selectedTab = MutableStateFlow(ProfileTab.NOTES)

    @OptIn(ExperimentalCoroutinesApi::class)
    val tabPostsFlow: Flow<List<FeedRow>> =
        combine(_pubkeyHex.filterNotNull(), selectedTab) { pk, tab -> pk to tab }
            .flatMapLatest { (pk, tab) ->
                when (tab) {
                    ProfileTab.NOTES    -> eventRepository.userNotesFlow(pk)
                    ProfileTab.REPLIES  -> eventRepository.userRepliesFlow(pk)
                    ProfileTab.LONGFORM -> eventRepository.userLongformFlow(pk)
                }
            }

    // ── Pagination state ───────────────────────────────────────────────
    private var oldestTimestamp = Long.MAX_VALUE
    private var fetching = false

    // ── Profile lookup for repost original authors ─────────────────────
    private val profileCache = ConcurrentHashMap<String, StateFlow<UserEntity?>>()

    fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
        profileCache.getOrPut(pubkey) {
            userRepository.userFlow(pubkey)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }

    val isLoadingPosts = MutableStateFlow(true)

    // Tracks which pubkeys we've already requested profiles for — prevents hot loop
    private val fetchedProfilePubkeys = mutableSetOf<String>()
    private val engagementFetchedIds = mutableSetOf<String>()
    private val engagementQueue = mutableListOf<String>()
    private var engagementDebounceJob: Job? = null

    init {
        // Fetch missing profiles for repost original authors as posts arrive
        viewModelScope.launch {
            postsFlow.collectLatest { rows ->
                isLoadingPosts.value = false

                val pubkeys = rows.flatMap { row ->
                    val embedded = if (row.kind == 6) {
                        extractRepostAuthorPubkey(row.content, row.tags)
                    } else null
                    listOfNotNull(row.pubkey, embedded)
                }.distinct()
                val newPubkeys = pubkeys.filter { it !in fetchedProfilePubkeys }
                if (newPubkeys.isNotEmpty()) {
                    fetchedProfilePubkeys.addAll(newPubkeys)
                    userRepository.fetchMissingProfiles(newPubkeys)
                }

                // Queue engagement fetch with debounce
                val newEventIds = rows
                    .filter { it.kind != 6 }
                    .map { it.id }
                    .filter { it !in engagementFetchedIds }
                if (newEventIds.isNotEmpty()) {
                    engagementFetchedIds.addAll(newEventIds)
                    queueEngagementFetch(newEventIds)
                }
            }
        }
    }

    private fun queueEngagementFetch(ids: List<String>) {
        engagementQueue.addAll(ids)
        engagementDebounceJob?.cancel()
        engagementDebounceJob = viewModelScope.launch {
            delay(500)
            val toFetch = engagementQueue.toList()
            engagementQueue.clear()
            toFetch.chunked(20).forEach { chunk ->
                relayPool.fetchEngagementBatch(chunk)
                delay(100)
            }
        }
    }

    /** Whether the logged-in user follows the viewed pubkey. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val isFollowing: Flow<Boolean> = _pubkeyHex
        .filterNotNull()
        .flatMapLatest { followDao.isFollowingFlow(it) }

    val followLoading = MutableStateFlow(false)

    private val myPubkey: String? = keyManager.getPublicKeyHex()

    fun toggleFollow() {
        val targetPubkey = _pubkeyHex.value ?: return
        if (myPubkey == null) return
        if (followLoading.value) return
        followLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentFollows = followDao.allPubkeys()
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
                val targetUrls = (writeUrls + INDEXER_RELAY_URLS).distinct()
                relayPool.publishToRelays(toEventJson(signed), targetUrls)

                // Update local follows table
                if (nowFollowing) {
                    followDao.delete(targetPubkey)
                } else {
                    followDao.insert(FollowEntity(pubkey = targetPubkey, followedAt = nowSeconds))
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

    companion object {
        private val INDEXER_RELAY_URLS = listOf(
            "wss://purplepag.es",
            "wss://user.kindpag.es",
            "wss://indexer.coracle.social",
            "wss://antiprimal.net",
        )
        private val GLOBAL_RELAY_URLS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://nostr.mom",
            "wss://relay.nostr.net",
            "wss://relay.primal.net",
        )
    }

    fun loadProfile(pubkey: String) {
        if (_pubkeyHex.value == pubkey) return
        _pubkeyHex.value = pubkey
        // Reset pagination + deduplication state for new profile
        selectedTab.value = ProfileTab.NOTES
        _displayLimit.value = 200
        oldestTimestamp = Long.MAX_VALUE
        fetching = false
        fetchedProfilePubkeys.clear()
        engagementFetchedIds.clear()
        isLoadingPosts.value = true

        viewModelScope.launch {
            userRepository.fetchMissingProfiles(listOf(pubkey))
            connectOutboxRelays(pubkey)
            relayPool.fetchUserPosts(pubkey)
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

        relayPool.fetchOlderPosts(pubkey, currentOldest)

        // Allow next fetch after relay responses have had time to arrive
        viewModelScope.launch {
            delay(2_000)
            fetching = false
        }
    }

    /**
     * NIP-65 outbox routing: connect to the user's declared write relays
     * so we can fetch their posts from where they actually publish.
     */
    private suspend fun connectOutboxRelays(pubkey: String) {
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
            Log.d(TAG, "No relay list found for $pubkey — fetching from connected relays only")
            return
        }

        // Step 3: parse write relay URLs and connect
        val writeUrls = runCatching {
            Json.decodeFromString<List<String>>(relayList.writeRelays)
        }.getOrDefault(emptyList())

        if (writeUrls.isNotEmpty()) {
            Log.d(TAG, "Connecting to ${writeUrls.size} outbox relays for $pubkey")
            relayPool.connect(writeUrls)
        }
    }
}
