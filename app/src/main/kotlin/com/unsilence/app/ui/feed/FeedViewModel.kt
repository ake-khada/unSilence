package com.unsilence.app.ui.feed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.relay.OutboxRouter
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.EventRepository
import com.unsilence.app.data.repository.RelaySetRepository
import com.unsilence.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class FeedType { GLOBAL, FOLLOWING }

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
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    private val _feedType = MutableStateFlow(FeedType.GLOBAL)
    val feedType: StateFlow<FeedType> = _feedType.asStateFlow()

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

    val feedTypeLabel: String get() = when (_feedType.value) {
        FeedType.GLOBAL    -> "Global"
        FeedType.FOLLOWING -> "Following"
    }

    fun setFeedType(type: FeedType) { _feedType.value = type }

    /**
     * Fetch events older than the current oldest item (pagination).
     * No-op if the oldest timestamp hasn't changed since the last fetch — avoids
     * hammering a relay that returned nothing or whose results haven't landed yet.
     * When Room does emit new older events the oldest timestamp shifts, which
     * naturally allows the next scroll trigger to fire a fresh fetch.
     */
    fun loadMore() {
        val oldest = _uiState.value.events.lastOrNull()?.createdAt ?: return
        if (oldest == lastOldestTimestamp) return
        lastOldestTimestamp = oldest
        viewModelScope.launch {
            val set  = relaySetRepository.defaultSet() ?: return@launch
            val urls = relaySetRepository.decodeUrls(set)
            relayPool.fetchOlderEvents(urls, oldest)
        }
    }

    init {
        viewModelScope.launch {
            relaySetRepository.seedDefaults()

            val set    = relaySetRepository.defaultSet() ?: return@launch
            val urls   = relaySetRepository.decodeUrls(set)
            val filter = relaySetRepository.decodeFilter(set)

            relayPool.connect(urls)

            _feedType
                .flatMapLatest { type ->
                    // Reset all state on feed switch so the new feed starts clean.
                    newestTimestamp     = 0L
                    hasNewTopPost       = false
                    lastOldestTimestamp = 0L
                    _uiState.value = _uiState.value.copy(loading = true)
                    when (type) {
                        FeedType.GLOBAL    -> eventRepository.feedFlow(urls, filter)
                        FeedType.FOLLOWING -> {
                            outboxRouter.start()
                            eventRepository.followingFeedFlow()
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

                    userRepository.fetchMissingProfiles(rows.map { it.pubkey }.distinct())
                }
        }
    }
}
