package com.unsilence.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.unsilence.app.data.memory.EventStats
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.ReactionInfo
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.memory.ZapDetail
import com.unsilence.app.data.relay.ANTIPRIMAL_RELAY_URL
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.TrendingClient
import com.unsilence.app.data.relay.WotHydrationCoalescer
import com.unsilence.app.data.relay.sortPeopleForSearch
import com.unsilence.app.data.relay.wotLookupSnapshot
import com.unsilence.app.data.relay.wotSubjectsForFeedRows
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.ui.shared.TimelineCardData
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

private const val TRENDING_DISPLAY_LIMIT = 8
private const val TRENDING_CANDIDATE_LIMIT = 32

data class SearchUiState(
    val query: String           = "",
    val peopleResults: List<UserEntity> = emptyList(),
    val noteResults: List<FeedRow>      = emptyList(),
    val loading: Boolean        = false,
    val hasSearched: Boolean    = false,
)

private data class TrendingCandidates(
    val hashtags: List<Pair<String, Int>> = emptyList(),
    val users: List<UserEntity> = emptyList(),
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
    private val memoryEventStore: MemoryEventStore,
    private val trendingClient: TrendingClient,
    private val relayPreferencesStore: com.unsilence.app.data.relay.RelayPreferencesStore,
    private val userRepository: UserRepository,
    private val timelineCardData: TimelineCardData,
    private val wotHydrationCoalescer: WotHydrationCoalescer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** NIP-36 sensitive-content display mode (shared with feed). */
    val sensitiveContentMode: StateFlow<com.unsilence.app.data.memory.SensitiveContentMode> =
        relayPreferencesStore.sensitiveContentModeFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly,
                com.unsilence.app.data.memory.SensitiveContentMode.BLUR)

    /** Visible trending hashtags after account-level mute filtering. */
    private val _trendingHashtags = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val trendingHashtags: StateFlow<List<Pair<String, Int>>> = _trendingHashtags.asStateFlow()

    /** Visible trending users after account-level mute filtering. */
    private val _trendingUsers = MutableStateFlow<List<UserEntity>>(emptyList())
    val trendingUsers: StateFlow<List<UserEntity>> = _trendingUsers.asStateFlow()

    private val trendingCandidates = MutableStateFlow(TrendingCandidates())
    private val requestedTrendingProfilePubkeys = ConcurrentHashMap.newKeySet<String>()

    private val _queryFlow = MutableStateFlow("")
    private val _wotSubjects = MutableStateFlow<Set<String>>(emptySet())
    val wotLookups: StateFlow<Map<String, WotLookup>> =
        combine(_wotSubjects, memoryEventStore.wotSignalFlow) { subjects, _ ->
            wotLookupSnapshot(subjects, memoryEventStore::wotFor)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val feedWotDisplayMode: StateFlow<FeedWotDisplayMode> =
        relayPreferencesStore.feedWotDisplayModeFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, FeedWotDisplayMode.NUMBERS)

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

    fun profileFlow(pubkey: String): StateFlow<UserEntity?> =
        timelineCardData.profileFlow(pubkey, viewModelScope)

    fun statsFlow(eventId: String): StateFlow<EventStats> =
        timelineCardData.statsFlow(eventId, viewModelScope)

    fun zapDetailsForEvent(eventId: String): List<ZapDetail> =
        timelineCardData.zapDetailsForEvent(eventId)

    fun repostPubkeysForEvent(eventId: String): List<String> =
        timelineCardData.repostPubkeysForEvent(eventId)

    fun reactionsForEvent(eventId: String): List<ReactionInfo> =
        timelineCardData.reactionsForEvent(eventId)

    fun refreshTrendingIfStale() {
        trendingClient.refreshIfStale()
    }

    init {
        // Paint immediately from local MES, then collect the shared progressive
        // trending snapshot: stale cache, phase-1 hashtags/users, then counts.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            if (trendingClient.data.value == null) {
                trendingCandidates.value = TrendingCandidates(
                    hashtags = memoryEventStore.trendingHashtags(TRENDING_CANDIDATE_LIMIT),
                    users = memoryEventStore.trendingUsers(TRENDING_CANDIDATE_LIMIT),
                )
            }
            trendingClient.refreshIfStale()
            trendingClient.data.collect { data ->
                if (data != null && data.hashtags.isNotEmpty()) {
                    trendingCandidates.value = TrendingCandidates(
                        hashtags = data.hashtags.map { it.tag to it.score.toInt().coerceAtLeast(1) },
                        users = data.profiles.map { profile ->
                            UserEntity(
                                pubkey = profile.pubkey,
                                name = profile.name,
                                displayName = profile.displayName,
                                picture = profile.picture,
                                about = profile.about,
                                nip05 = profile.nip05,
                                followerCount = profile.followerCount,
                            )
                        },
                    )
                }
            }
        }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            combine(
                trendingCandidates,
                memoryEventStore.ownMuteListFlow(),
                memoryEventStore.profileSignalFlow,
            ) { candidates, muteList, _ ->
                val mutedHashtags = muteList.normalizedMutedHashtags()
                val mutedPubkeys = muteList.mutedPubkeys()
                val hashtags = candidates.hashtags
                    .filterNot { (tag, _) -> normalizeHashtag(tag) in mutedHashtags }
                    .take(TRENDING_DISPLAY_LIMIT)
                val users = candidates.users
                    .map { it.withLatestProfile() }
                    .filterNot { it.pubkey in mutedPubkeys }
                    .take(TRENDING_DISPLAY_LIMIT)
                hashtags to users
            }.collect { (hashtags, users) ->
                _trendingHashtags.value = hashtags
                _trendingUsers.value = users
                hydrateIncompleteTrendingProfiles(users)
            }
        }

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
                        _wotSubjects.value = emptySet()
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

                    // Detect hashtag queries: starts with # or is a bare
                    // alphanumeric+underscore token.
                    val hashtagTag = extractHashtagQuery(query)

                    if (hashtagTag != null) {
                        // NIP-12 #t filter search
                        relayPool.searchHashtag(searchRelays, hashtagTag, token)
                    } else {
                        // NIP-50 full-text search
                        relayPool.searchNotes(searchRelays, query, token)
                    }

                    // Give relays time to respond before declaring "no results"
                    val searchStart = System.currentTimeMillis()

                    // Relay results: events whose IDs were emitted by RelayPool's search-notes subs.
                    // flatMapLatest re-queries MES each time new IDs arrive.
                    val relayResults = _searchResultEventIds
                        .flatMapLatest { ids ->
                            if (ids.isEmpty()) flowOf(emptyList())
                            else memoryEventStore.feedSignalFlow.map {
                                memoryEventStore.feedRowsByIds(ids)
                            }.distinctUntilChanged()
                        }

                    // Combine local MES results with relay-returned results + people search.
                    val localNotesFlow = if (hashtagTag != null) {
                        memoryEventStore.searchNotesByHashtagFlow(hashtagTag)
                    } else {
                        memoryEventStore.searchNotesFlow(query)
                    }
                    val peopleFlow = if (hashtagTag != null) {
                        flowOf(emptyList())
                    } else {
                        memoryEventStore.searchUsersFlow(query)
                    }
                    combine(
                        localNotesFlow,
                        relayResults,
                        peopleFlow,
                        memoryEventStore.wotSignalFlow,
                    ) { localNotes, relayNotes, people, _ ->
                        Triple(localNotes, relayNotes, people)
                    }.collect { (localNotes, relayNotes, people) ->
                            val mergedNotes = (localNotes + relayNotes)
                                .distinctBy { it.id }
                                .sortedByDescending { it.createdAt }
                            val sortedPeople = sortPeopleForSearch(people, memoryEventStore::wotFor)
                            val wotSubjects = buildSet {
                                addAll(sortedPeople.map { it.pubkey })
                                addAll(wotSubjectsForFeedRows(mergedNotes, modelProvider = memoryEventStore::getEventModel))
                            }
                            _wotSubjects.value = wotSubjects
                            wotHydrationCoalescer.requestHydration(wotSubjects)
                            val hasResults = mergedNotes.isNotEmpty() || sortedPeople.isNotEmpty()
                            val elapsed = System.currentTimeMillis() - searchStart
                            val doneLoading = hasResults || elapsed > 3_000
                            _uiState.update {
                                it.copy(
                                    noteResults   = mergedNotes,
                                    peopleResults = sortedPeople,
                                    loading       = !doneLoading,
                                )
                            }
                        }
                }
        }
    }

    fun requestWotHydration(pubkeys: Collection<String>) {
        if (pubkeys.isEmpty()) return
        _wotSubjects.update { current -> current + pubkeys }
        wotHydrationCoalescer.requestHydration(pubkeys)
    }

    /** Called by DisposableEffect when SearchScreen exits composition (nav back). */
    fun onScreenLeft() {
        currentSearchToken.getAndSet(0L).let { prev ->
            if (prev != 0L) relayPool.closeSearch(prev)
        }
        _queryFlow.value = ""
        _searchResultEventIds.value = emptySet()
        _wotSubjects.value = emptySet()
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

    private fun UserEntity.withLatestProfile(): UserEntity {
        val latest = memoryEventStore.getUserEntity(pubkey) ?: return this
        return latest.copy(
            followerCount = followerCount ?: latest.followerCount,
            followerCountUpdatedAt = followerCountUpdatedAt ?: latest.followerCountUpdatedAt,
        )
    }

    private fun hydrateIncompleteTrendingProfiles(users: List<UserEntity>) {
        val missing = users
            .asSequence()
            .filter { it.displayName.isNullOrBlank() && it.name.isNullOrBlank() || it.picture.isNullOrBlank() }
            .map { it.pubkey }
            .distinct()
            .filter { requestedTrendingProfilePubkeys.add(it) }
            .toList()
        if (missing.isEmpty()) return

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            userRepository.fetchProfilesWithFanout(missing, maxRelays = 4)
        }
    }

    companion object {
        val DEFAULT_SEARCH_RELAYS = listOf(
            "wss://nostr.wine",
            "wss://relay.noswhere.com",
            "wss://search.nos.today",
            ANTIPRIMAL_RELAY_URL,
        )

        /**
         * Returns the lowercase hashtag value if [query] is a hashtag query, or null.
         * Hashtag query: starts with `#` followed by word chars, or is a bare
         * alphanumeric+underscore token without spaces or special characters.
         */
        fun extractHashtagQuery(query: String): String? {
            val trimmed = query.trim()
            if (trimmed.startsWith("#") && trimmed.length > 1) {
                val tag = trimmed.substring(1)
                // All chars must be hashtag-valid (letters, digits, underscore)
                if (tag.all { it.isLetterOrDigit() || it == '_' }) {
                    return tag.lowercase()
                }
            }
            return null
        }
    }
}

private fun MuteList?.normalizedMutedHashtags(): Set<String> {
    if (this == null) return emptySet()
    return (hashtags.asSequence() + privateHashtags.asSequence())
        .map(::normalizeHashtag)
        .filter { it.isNotEmpty() }
        .toSet()
}

private fun MuteList?.mutedPubkeys(): Set<String> =
    this?.let { it.pubkeys + it.privatePubkeys }.orEmpty()

private fun normalizeHashtag(tag: String): String =
    tag.trim().trimStart('#').lowercase()
