package com.unsilence.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.unsilence.app.data.memory.exceedsHashtagCap
import com.unsilence.app.data.memory.EventStats
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.isMuted
import com.unsilence.app.data.memory.isPubkeyMuted
import com.unsilence.app.data.memory.ReactionInfo
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.normalizedMutedHashtags
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.memory.ZapDetail
import com.unsilence.app.data.relay.ANTIPRIMAL_RELAY_URL
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.data.relay.ImpersonationRisk
import com.unsilence.app.data.relay.ProtectedProfile
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.TrendingClient
import com.unsilence.app.data.relay.WotHydrationCoalescer
import com.unsilence.app.data.relay.detectImpersonationRisk
import com.unsilence.app.data.relay.isProtectedWotLookup
import com.unsilence.app.data.relay.protectedProfileFor
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
    val impersonationRisks: Map<String, ImpersonationRisk> = emptyMap(),
    val noteResults: List<FeedRow>      = emptyList(),
    val tagResults: List<FeedRow>       = emptyList(),
    val loading: Boolean        = false,
    val hasSearched: Boolean    = false,
)

private data class TrendingCandidates(
    val hashtags: List<Pair<String, Int>> = emptyList(),
    val users: List<UserEntity> = emptyList(),
)

private data class SearchResultsBundle(
    val localNotes: List<FeedRow>,
    val tagNotes: List<FeedRow>,
    val relayNotes: List<FeedRow>,
    val people: List<UserEntity>,
    val muteList: MuteList? = null,
    val hashtagCap: Int? = null,
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
                val mutedHashtags = normalizedMutedHashtags(muteList)
                val hashtags = candidates.hashtags
                    .filterNot { (tag, _) -> normalizeHashtag(tag) in mutedHashtags }
                    .take(TRENDING_DISPLAY_LIMIT)
                val users = candidates.users
                    .map { it.withLatestProfile() }
                    .filterNot { isPubkeyMuted(it.pubkey, muteList) }
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
                                impersonationRisks = emptyMap(),
                                noteResults   = emptyList(),
                                tagResults    = emptyList(),
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

                    val explicitHashtagTag = extractExplicitHashtagQuery(query)
                    val tagSearchTag = extractTagSearchQuery(query)

                    if (explicitHashtagTag != null) {
                        // NIP-12 #t filter search
                        relayPool.searchHashtag(searchRelays, explicitHashtagTag, token)
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
                    val localNotesFlow = if (explicitHashtagTag != null) {
                        memoryEventStore.searchNotesByHashtagFlow(explicitHashtagTag)
                    } else {
                        memoryEventStore.searchNotesFlow(query)
                    }
                    val tagNotesFlow = if (tagSearchTag != null) {
                        memoryEventStore.searchNotesByHashtagFlow(tagSearchTag)
                    } else {
                        flowOf(emptyList())
                    }
                    val peopleFlow = if (explicitHashtagTag != null) {
                        flowOf(emptyList())
                    } else {
                        memoryEventStore.searchUsersFlow(query)
                    }
                    val searchResultsFlow = combine(
                        localNotesFlow,
                        tagNotesFlow,
                        relayResults,
                        peopleFlow,
                        memoryEventStore.wotSignalFlow,
                    ) { localNotes, tagNotes, relayNotes, people, _ ->
                        SearchResultsBundle(localNotes, tagNotes, relayNotes, people)
                    }
                    val safetyFlow = combine(
                        memoryEventStore.ownMuteListFlow(),
                        relayPreferencesStore.hashtagCapFlow(),
                    ) { muteList, hashtagCap ->
                        muteList to hashtagCap
                    }
                    combine(searchResultsFlow, safetyFlow) { results, safety ->
                        results.copy(muteList = safety.first, hashtagCap = safety.second)
                    }.collect { results ->
                        val localNotes = filterSearchNoteRows(results.localNotes, results.muteList, results.hashtagCap)
                        val relayNotes = filterSearchNoteRows(results.relayNotes, results.muteList, results.hashtagCap)
                        val people = filterSearchPeople(results.people, results.muteList)
                        val mergedNotes = (localNotes + relayNotes)
                            .distinctBy { it.id }
                            .sortedByDescending { it.createdAt }
                        val tagRelayNotes = if (explicitHashtagTag != null) relayNotes else emptyList()
                        val localTagNotes = filterSearchNoteRows(results.tagNotes, results.muteList, results.hashtagCap)
                        val tagResults = (localTagNotes + tagRelayNotes)
                            .distinctBy { it.id }
                            .sortedByDescending { it.createdAt }
                        val sortedPeople = sortPeopleForSearch(people, query, memoryEventStore::wotFor)
                        val protectedProfiles = buildProtectedProfiles(ownPubkey)
                        val impersonationRisks = sortedPeople.mapNotNull { user ->
                            detectImpersonationRisk(
                                candidate = user,
                                lookup = memoryEventStore.wotFor(user.pubkey),
                                protectedProfiles = protectedProfiles,
                            )?.let { user.pubkey to it }
                        }.toMap()
                        val wotSubjects = buildSet {
                            addAll(sortedPeople.map { it.pubkey })
                            addAll(wotSubjectsForFeedRows(mergedNotes, modelProvider = memoryEventStore::getEventModel))
                            addAll(wotSubjectsForFeedRows(tagResults, modelProvider = memoryEventStore::getEventModel))
                        }
                        _wotSubjects.value = wotSubjects
                        wotHydrationCoalescer.requestHydration(wotSubjects)
                        val hasResults = mergedNotes.isNotEmpty() || sortedPeople.isNotEmpty() || tagResults.isNotEmpty()
                        val elapsed = System.currentTimeMillis() - searchStart
                        val doneLoading = hasResults || elapsed > 3_000
                        _uiState.update {
                            it.copy(
                                noteResults   = mergedNotes,
                                tagResults    = tagResults,
                                peopleResults = sortedPeople,
                                impersonationRisks = impersonationRisks,
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
                impersonationRisks = emptyMap(),
                noteResults   = emptyList(),
                tagResults    = emptyList(),
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

    private fun buildProtectedProfiles(ownPubkey: String): List<ProtectedProfile> {
        if (ownPubkey.isBlank()) return emptyList()
        val protectedPubkeys = LinkedHashSet<String>()
        protectedPubkeys.addAll(memoryEventStore.getFollows(ownPubkey).orEmpty())
        memoryEventStore.getWotAssertions().values
            .filter { isProtectedWotLookup(WotLookup.Scored(it)) }
            .forEach { protectedPubkeys.add(it.subjectPubkey) }
        return protectedPubkeys.mapNotNull { pubkey ->
            memoryEventStore.getUserEntity(pubkey)?.let(::protectedProfileFor)
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
         * Returns the lowercase hashtag value for an explicit hashtag query, or null.
         */
        fun extractExplicitHashtagQuery(query: String): String? {
            val trimmed = query.trim()
            if (trimmed.startsWith("#") && trimmed.length > 1) {
                val tag = trimmed.substring(1)
                return normalizeTagSearchToken(tag)
            }
            return null
        }

        /**
         * Converts a searched word into the tag value used by the Tags tab.
         */
        fun extractTagSearchQuery(query: String): String? {
            val trimmed = query.trim().removePrefix("#")
            return normalizeTagSearchToken(trimmed)
        }

        private fun normalizeTagSearchToken(raw: String): String? {
            if (raw.isBlank()) return null
            if (!raw.all { it.isLetterOrDigit() || it == '_' }) return null
            return raw.lowercase()
        }
    }
}

internal fun filterSearchNoteRows(
    rows: List<FeedRow>,
    muteList: MuteList?,
    hashtagCap: Int?,
): List<FeedRow> = rows.filterNot { row ->
    isMuted(row, muteList) || exceedsHashtagCap(row, hashtagCap)
}

internal fun filterSearchPeople(
    users: List<UserEntity>,
    muteList: MuteList?,
): List<UserEntity> = users.filterNot { user ->
    isPubkeyMuted(user.pubkey, muteList)
}

private fun normalizeHashtag(tag: String): String =
    tag.trim().trimStart('#').lowercase()
