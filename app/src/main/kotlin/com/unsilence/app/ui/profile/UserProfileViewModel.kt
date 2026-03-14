package com.unsilence.app.ui.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.extractRepostAuthorPubkey
import com.unsilence.app.data.repository.EventRepository
import com.unsilence.app.data.repository.UserRepository
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import dagger.hilt.android.lifecycle.HiltViewModel
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

private const val TAG = "UserProfileVM"

@HiltViewModel
class UserProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val eventRepository: EventRepository,
    private val relayPool: RelayPool,
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

    // Tracks which pubkeys we've already requested profiles for — prevents hot loop
    private val fetchedProfilePubkeys = mutableSetOf<String>()

    init {
        // Fetch missing profiles for repost original authors as posts arrive
        viewModelScope.launch {
            postsFlow.collectLatest { rows ->
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
            }
        }
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
