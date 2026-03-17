package com.unsilence.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.dao.RelayConfigDao
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.SearchResult
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

    /** Accumulates event IDs that arrive on search-notes-* subscriptions from RelayPool. */
    private val _searchResultEventIds = MutableStateFlow<Set<String>>(emptySet())

    /** Token of the current search session — late results from old tokens are dropped. */
    private val _currentSearchToken = MutableStateFlow(0L)

    fun search(query: String) {
        _queryFlow.value = query
        _uiState.update { it.copy(query = query) }
    }

    init {
        // Collect search result IDs for the lifetime of the ViewModel.
        // Only accumulate results matching the current search token —
        // late arrivals from previous queries are silently dropped.
        viewModelScope.launch {
            relayPool.searchResults.collect { result ->
                if (result.token == _currentSearchToken.value) {
                    _searchResultEventIds.update { it + result.eventId }
                }
            }
        }

        viewModelScope.launch {
            _queryFlow
                .debounce(300)
                .collectLatest { query ->
                    if (query.isBlank()) {
                        _searchResultEventIds.value = emptySet()
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

                    // Generate token and set it BEFORE sending any REQ so the collector
                    // is ready to accept the first fast result.
                    val token = System.currentTimeMillis()
                    _currentSearchToken.value = token
                    _searchResultEventIds.value = emptySet()

                    // Send NIP-50 REQ to search relays — results flow into Room via EventProcessor.
                    relayPool.searchNotes(searchRelays, query, token)

                    // Give relays time to respond before declaring "no results"
                    val searchStart = System.currentTimeMillis()

                    // Relay results: events whose IDs were emitted by RelayPool's search-notes subs.
                    // flatMapLatest re-queries Room each time new IDs arrive.
                    val relayResults = _searchResultEventIds
                        .map { ids -> ids.toList() }
                        .flatMapLatest { ids ->
                            if (ids.isEmpty()) flowOf(emptyList())
                            else eventRepository.eventsByIds(ids)
                        }

                    // Combine local LIKE results with relay-returned results + people search.
                    combine(
                        eventRepository.searchNotes(query),
                        relayResults,
                        userRepository.searchUsers(query),
                    ) { localNotes, relayNotes, people ->
                        Triple(localNotes, relayNotes, people)
                    }.collect { (localNotes, relayNotes, people) ->
                            val mergedNotes = (localNotes + relayNotes)
                                .distinctBy { it.id }
                                .sortedByDescending { it.createdAt }
                            val hasResults = mergedNotes.isNotEmpty() || people.isNotEmpty()
                            val elapsed = System.currentTimeMillis() - searchStart
                            val doneLoading = hasResults || elapsed > 3_000
                            _uiState.update {
                                it.copy(
                                    noteResults   = mergedNotes,
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
            "wss://relay.ditto.pub",
        )
    }
}
