package com.unsilence.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.dao.RelayConfigDao
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.EventRepository
import com.unsilence.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String           = "",
    val peopleResults: List<UserEntity> = emptyList(),
    val noteResults: List<FeedRow>      = emptyList(),
    val loading: Boolean        = false,
    val hasSearched: Boolean    = false,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val relayPool: RelayPool,
    private val relayConfigDao: RelayConfigDao,
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _queryFlow = MutableStateFlow("")

    fun search(query: String) {
        _queryFlow.value = query
        _uiState.update { it.copy(query = query) }
    }

    init {
        viewModelScope.launch {
            _queryFlow
                .debounce(300)
                .collectLatest { query ->
                    if (query.isBlank()) {
                        _uiState.update {
                            it.copy(
                                peopleResults = emptyList(),
                                noteResults   = emptyList(),
                                loading       = false,
                                hasSearched   = false,
                            )
                        }
                        return@collectLatest
                    }

                    _uiState.update { it.copy(loading = true, hasSearched = true) }

                    // Use user-configured search relays (kind 10007), fall back to defaults.
                    val userSearchRelays = relayConfigDao.searchRelayUrls()
                    val searchRelays = userSearchRelays.ifEmpty { DEFAULT_SEARCH_RELAYS }

                    // Send NIP-50 REQ to search relays — results flow into Room via EventProcessor.
                    relayPool.searchNotes(searchRelays, query)

                    // Give relays time to respond before declaring "no results"
                    val searchStart = System.currentTimeMillis()

                    // Collect Room results reactively — re-emits as relay responses arrive.
                    combine(
                        eventRepository.searchNotes(query),
                        userRepository.searchUsers(query),
                    ) { notes, people -> Pair(notes, people) }
                        .collect { (notes, people) ->
                            val hasResults = notes.isNotEmpty() || people.isNotEmpty()
                            val elapsed = System.currentTimeMillis() - searchStart
                            val doneLoading = hasResults || elapsed > 3_000
                            _uiState.update {
                                it.copy(
                                    noteResults   = notes,
                                    peopleResults = people,
                                    loading       = !doneLoading,
                                )
                            }
                        }
                }
        }
    }

    companion object {
        val DEFAULT_SEARCH_RELAYS = listOf(
            "wss://relay.noswhere.com",
            "wss://search.nos.today",
            "wss://antiprimal.net",
        )
    }
}
