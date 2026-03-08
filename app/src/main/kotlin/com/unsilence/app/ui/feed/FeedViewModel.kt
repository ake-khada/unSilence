package com.unsilence.app.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.EventRepository
import com.unsilence.app.data.repository.RelaySetRepository
import com.unsilence.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FeedUiState(
    val events: List<FeedRow> = emptyList(),
    val loading: Boolean = true,
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val relaySetRepository: RelaySetRepository,
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val relayPool: RelayPool,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // 1. Seed built-in relay sets (idempotent)
            relaySetRepository.seedDefaults()

            // 2. Load the default relay set and its filter
            val set    = relaySetRepository.defaultSet() ?: return@launch
            val urls   = relaySetRepository.decodeUrls(set)
            val filter = relaySetRepository.decodeFilter(set)

            // 3. Connect to relays (relay → Room via EventProcessor)
            relayPool.connect(urls)

            // 4. Observe Room — UI never waits on the network
            eventRepository.feedFlow(urls, filter).collectLatest { rows ->
                _uiState.value = FeedUiState(events = rows, loading = false)

                // 5. Fetch profiles for any new pubkeys we haven't cached yet
                val pubkeys = rows.map { it.pubkey }.distinct()
                userRepository.fetchMissingProfiles(pubkeys)
            }
        }
    }
}
