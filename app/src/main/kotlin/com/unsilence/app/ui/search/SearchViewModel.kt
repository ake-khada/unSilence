package com.unsilence.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.ANTIPRIMAL_RELAY_URL
import com.unsilence.app.data.relay.RelayPool
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
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
    private val keyManager: KeyManager,
    private val memoryEventStore: MemoryEventStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _queryFlow = MutableStateFlow("")

    /** Accumulates event IDs that arrive on search-notes-* subscriptions from RelayPool. */
    private val _searchResultEventIds = MutableStateFlow<Set<String>>(emptySet())

    /** Token of the current search session — late results from old tokens are dropped.
     *  AtomicLong eliminates the race between the relay-result collector (IO) and
     *  collectLatest / onScreenLeft / onCleared (Main). 0L = no active search. */
    private val currentSearchToken = AtomicLong(0L)

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
                if (result.token == currentSearchToken.get()) {
                    _searchResultEventIds.update { it + result.eventId }
                }
            }
        }

        viewModelScope.launch {
            _queryFlow
                .debounce(1000)
                .filter { it.isEmpty() || it.length >= 3 }
                .distinctUntilChanged()
                .collectLatest { query ->
                    // Close prior search sub-IDs on relays before doing anything else.
                    currentSearchToken.get().let { prev ->
                        if (prev != 0L) relayPool.closeSearch(prev)
                    }

                    if (query.isEmpty()) {
                        currentSearchToken.set(0L)
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

                    val ownPubkey = keyManager.getPublicKeyHex() ?: ""
                    val userSearchRelays = memoryEventStore.getSearchRelayUrls(ownPubkey)
                    val searchRelays = userSearchRelays.ifEmpty { DEFAULT_SEARCH_RELAYS }

                    // Generate token and set it BEFORE sending any REQ so the collector
                    // is ready to accept the first fast result.
                    val token = System.currentTimeMillis()
                    currentSearchToken.set(token)
                    _searchResultEventIds.value = emptySet()

                    // Send NIP-50 REQ to search relays — results flow into MES via EventProcessor.
                    relayPool.searchNotes(searchRelays, query, token)

                    // Give relays time to respond before declaring "no results"
                    val searchStart = System.currentTimeMillis()

                    // Relay results: events whose IDs were emitted by RelayPool's search-notes subs.
                    // flatMapLatest re-queries MES each time new IDs arrive.
                    val relayResults = _searchResultEventIds
                        .flatMapLatest { ids ->
                            if (ids.isEmpty()) flowOf(emptyList())
                            else memoryEventStore.feedRowsByIdsFlow(ids)
                        }

                    // Combine local MES results with relay-returned results + people search.
                    combine(
                        memoryEventStore.searchNotesFlow(query),
                        relayResults,
                        memoryEventStore.searchUsersFlow(query),
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

    /** Called by DisposableEffect when SearchScreen exits composition (nav back). */
    fun onScreenLeft() {
        currentSearchToken.getAndSet(0L).let { prev ->
            if (prev != 0L) relayPool.closeSearch(prev)
        }
        _queryFlow.value = ""
        _searchResultEventIds.value = emptySet()
        _uiState.update {
            it.copy(
                query         = "",
                peopleResults = emptyList(),
                noteResults   = emptyList(),
                loading       = false,
                hasSearched   = false,
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        currentSearchToken.get().let { token ->
            if (token != 0L) relayPool.closeSearch(token)
        }
    }

    companion object {
        val DEFAULT_SEARCH_RELAYS = listOf(
            "wss://nostr.wine",
            "wss://relay.noswhere.com",
            "wss://search.nos.today",
            ANTIPRIMAL_RELAY_URL,
        )
    }
}
