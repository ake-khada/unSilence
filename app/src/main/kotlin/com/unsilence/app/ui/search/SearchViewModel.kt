package com.unsilence.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.db.dao.FeedRow
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

                    // Send NIP-50 REQ to search relays — results flow into Room via EventProcessor.
                    relayPool.searchNotes(SEARCH_RELAYS, query)

                    // Collect Room results reactively — re-emits as relay responses arrive.
                    combine(
                        eventRepository.searchNotes(query),
                        userRepository.searchUsers(query),
                    ) { notes, people -> Pair(notes, people) }
                        .collect { (notes, people) ->
                            _uiState.update {
                                it.copy(
                                    noteResults   = notes,
                                    peopleResults = people,
                                    loading       = false,
                                )
                            }
                        }
                }
        }
    }

    companion object {
        val SEARCH_RELAYS = listOf(
            "wss://relay.noswhere.com",
            "wss://search.nos.today",
            "wss://relay.ditto.pub",
        )
    }
}
