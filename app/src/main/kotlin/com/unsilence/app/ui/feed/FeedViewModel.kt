package com.unsilence.app.ui.feed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.dao.FollowDao
import com.unsilence.app.data.db.entity.RelaySetEntity
import com.unsilence.app.data.relay.OutboxRouter
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.extractRepostAuthorPubkey
import com.unsilence.app.data.repository.EventRepository
import com.unsilence.app.data.repository.RelaySetRepository
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.domain.model.FeedFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.unsilence.app.data.db.entity.UserEntity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

sealed class FeedType {
    data object Global    : FeedType()
    data object Following : FeedType()
    data class  RelaySet(val id: String, val name: String) : FeedType()
}

data class FeedUiState(
    val events: List<FeedRow> = emptyList(),
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FeedViewModel @Inject constructor(
    private val relaySetRepository: RelaySetRepository,
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val relayPool: RelayPool,
    private val outboxRouter: OutboxRouter,
    private val followDao: FollowDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    private val _feedType = MutableStateFlow<FeedType>(FeedType.Global)
    val feedType: StateFlow<FeedType> = _feedType.asStateFlow()

    /** All relay sets (built-in + user) for the dropdown. */
    val userSetsFlow: StateFlow<List<RelaySetEntity>> = relaySetRepository.allSetsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _filter = MutableStateFlow(FeedFilter())
    val filterFlow: StateFlow<FeedFilter> = _filter.asStateFlow()

    private val _displayLimit = MutableStateFlow(200)

    fun updateFilter(filter: FeedFilter) { _filter.value = filter }

    /**
     * True when the top-of-feed item has a newer created_at than the previous emission.
     * Used to drive the new-posts dot and to trigger a snap-to-top when the user
     * is already at index 0. Cleared by FeedScreen via clearNewTopPost().
     */
    var hasNewTopPost by mutableStateOf(false)
        private set

    fun clearNewTopPost() { hasNewTopPost = false }

    // created_at of the first item in the last emission; 0 until the first load.
    private var newestTimestamp = 0L

    // created_at of the last item when loadMore() last fired; guards duplicate page fetches.
    private var lastOldestTimestamp = 0L

    // ── Profile lookup for repost original authors ──────────────────────
    private val profileCache = ConcurrentHashMap<String, StateFlow<UserEntity?>>()
    private val fetchedProfilePubkeys = mutableSetOf<String>()
    private val engagementFetchedIds = mutableSetOf<String>()

    /**
     * Returns a cached StateFlow for the given pubkey's profile.
     * Used by LazyColumn items to resolve original author info on kind-6 reposts.
     * WhileSubscribed(5000) keeps the flow alive briefly when items scroll off-screen.
     */
    fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
        profileCache.getOrPut(pubkey) {
            userRepository.userFlow(pubkey)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }

    val feedTypeLabel: String get() = when (val t = _feedType.value) {
        is FeedType.Global    -> "Global"
        is FeedType.Following -> "Following"
        is FeedType.RelaySet  -> t.name
    }

    fun setFeedType(type: FeedType) { _feedType.value = type }

    /**
     * Fetch events older than the current oldest item (pagination).
     * No-op if the oldest timestamp hasn't changed since the last fetch — avoids
     * hammering a relay that returned nothing or whose results haven't landed yet.
     * When Room does emit new older events the oldest timestamp shifts, which
     * naturally allows the next scroll trigger to fire a fresh fetch.
     */
    // Relay URLs currently used by the active feed — kept in sync with flatMapLatest.
    private var currentRelayUrls: List<String> = emptyList()

    fun loadMore() {
        val oldest = _uiState.value.events.lastOrNull()?.createdAt ?: return
        if (oldest == lastOldestTimestamp) return
        lastOldestTimestamp = oldest
        _displayLimit.value += 200
        relayPool.fetchOlderEvents(currentRelayUrls, oldest)
    }

    init {
        viewModelScope.launch {
            val hasFollows = followDao.count() > 0
            if (hasFollows) _feedType.value = FeedType.Following

            relaySetRepository.seedDefaults()

            val set  = relaySetRepository.defaultSet() ?: return@launch
            val urls = relaySetRepository.decodeUrls(set)

            relayPool.connect(urls)

            combine(_feedType, _filter) { type, filter -> type to filter }
                .flatMapLatest { (type, filter) ->
                    // Reset all state on feed switch so the new feed starts clean.
                    newestTimestamp     = 0L
                    hasNewTopPost       = false
                    lastOldestTimestamp = 0L
                    _displayLimit.value = 200
                    fetchedProfilePubkeys.clear()
                    engagementFetchedIds.clear()
                    _uiState.value = _uiState.value.copy(loading = true)
                    viewModelScope.launch {
                        delay(3_000)
                        if (_uiState.value.loading) _uiState.update { it.copy(loading = false) }
                    }
                    when (type) {
                        is FeedType.Global    -> {
                            currentRelayUrls = urls
                            _displayLimit.flatMapLatest { limit ->
                                eventRepository.feedFlow(urls, filter, limit)
                            }
                        }
                        is FeedType.Following -> {
                            currentRelayUrls = emptyList()
                            outboxRouter.start()
                            _displayLimit.flatMapLatest { limit ->
                                eventRepository.followingFeedFlow(limit)
                            }
                        }
                        is FeedType.RelaySet  -> {
                            val setEntity = relaySetRepository.getById(type.id)
                            val setUrls   = setEntity?.let { relaySetRepository.decodeUrls(it) } ?: urls
                            currentRelayUrls = setUrls
                            relayPool.connect(setUrls)
                            _displayLimit.flatMapLatest { limit ->
                                eventRepository.feedFlow(setUrls, filter, limit)
                            }
                        }
                    }
                }
                .collectLatest { rows ->
                    val incomingNewest = rows.firstOrNull()?.createdAt ?: 0L

                    // Only flag a new top post after the initial load; the initial
                    // populate is not a "new post arrived" event.
                    if (newestTimestamp > 0 && incomingNewest > newestTimestamp) {
                        hasNewTopPost = true
                    }
                    newestTimestamp = incomingNewest

                    _uiState.value = FeedUiState(events = rows, loading = false)

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

                    // Fetch engagement (replies, reactions, zaps) for posts not yet fetched
                    val newEventIds = rows
                        .filter { it.kind != 6 } // skip reposts, they reference original event
                        .map { it.id }
                        .filter { it !in engagementFetchedIds }
                    if (newEventIds.isNotEmpty()) {
                        engagementFetchedIds.addAll(newEventIds)
                        newEventIds.chunked(20).forEach { chunk ->
                            relayPool.fetchEngagementBatch(chunk)
                        }
                    }
                }
        }
    }
}
