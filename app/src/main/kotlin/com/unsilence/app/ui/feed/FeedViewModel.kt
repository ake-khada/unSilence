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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.collectLatest
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

    private val _uiState   = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    private val _feedType  = MutableStateFlow(FeedType.GLOBAL)
    val feedType: StateFlow<FeedType> = _feedType.asStateFlow()

    /** True when new posts arrived while the user was scrolled away from the top. */
    var newPostsAvailable by mutableStateOf(false)
        private set

    /** Called by FeedScreen whenever the list is at position 0. */
    fun markSeen() {
        newPostsAvailable = false
    }

    // Tracks the event count from the last emission; reset on feed type switch.
    private var lastEventCount = 0

    /** Label shown in the top bar dropdown (e.g. "Global ▾"). */
    val feedTypeLabel: String get() = when (_feedType.value) {
        FeedType.GLOBAL    -> "Global"
        FeedType.FOLLOWING -> "Following"
    }

    fun setFeedType(type: FeedType) {
        _feedType.value = type
    }

    init {
        viewModelScope.launch {
            // 1. Seed built-in relay sets (idempotent)
            relaySetRepository.seedDefaults()

            // 2. Load the default relay set and its filter
            val set    = relaySetRepository.defaultSet() ?: return@launch
            val urls   = relaySetRepository.decodeUrls(set)
            val filter = relaySetRepository.decodeFilter(set)

            // 3. Connect to global relays (relay → Room via EventProcessor)
            relayPool.connect(urls)

            // 4. React to feed type switches using flatMapLatest —
            //    cancels the previous Room Flow collection on every switch.
            _feedType
                .flatMapLatest { type ->
                    // Reset counters and loading state on every feed switch.
                    lastEventCount = 0
                    newPostsAvailable = false
                    _uiState.value = _uiState.value.copy(loading = true)
                    when (type) {
                        FeedType.GLOBAL    -> eventRepository.feedFlow(urls, filter)
                        FeedType.FOLLOWING -> {
                            // Kick off outbox routing (idempotent — does nothing if already started)
                            outboxRouter.start()
                            eventRepository.followingFeedFlow()
                        }
                    }
                }
                .collectLatest { rows ->
                    val isInitialLoad = lastEventCount == 0
                    val grew = rows.size > lastEventCount
                    lastEventCount = rows.size

                    _uiState.value = FeedUiState(events = rows, loading = false)

                    // Only flag new content after the initial load so the dot doesn't
                    // appear the moment the feed first populates.
                    if (!isInitialLoad && grew) {
                        newPostsAvailable = true
                    }

                    // Fetch profiles for any pubkeys not yet cached in Room
                    val pubkeys = rows.map { it.pubkey }.distinct()
                    userRepository.fetchMissingProfiles(pubkeys)
                }
        }
    }
}
