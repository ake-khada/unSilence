package com.unsilence.app.ui.search

import com.unsilence.app.ui.shared.CardDataFlow

import android.util.Log
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
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.memory.ZapDetail
import com.unsilence.app.data.relay.DEFAULT_SEARCH_RELAY_URLS
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.data.relay.FollowerCount
import com.unsilence.app.data.relay.ImpersonationRisk
import com.unsilence.app.data.relay.ProtectedProfile
import com.unsilence.app.data.relay.ProfilePipeline
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.TRENDING_CANDIDATE_LIMIT
import com.unsilence.app.data.relay.TrendingClient
import com.unsilence.app.data.relay.WOT_ASSERTION_CHUNK_SIZE
import com.unsilence.app.data.relay.WOT_FETCH_TIMEOUT_MS
import com.unsilence.app.data.relay.WOT_HYDRATION_WINDOW_MS
import com.unsilence.app.data.relay.WotHydrationCoalescer
import com.unsilence.app.data.relay.formatFollowerCount
import com.unsilence.app.data.relay.isProtectedWotLookup
import com.unsilence.app.data.relay.protectedProfileFor
import com.unsilence.app.data.relay.reconciledFollowerCount
import com.unsilence.app.data.relay.wotLookupSnapshot
import com.unsilence.app.data.relay.wotVerifiedFollowers
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.ui.shared.TimelineCardData
import com.unsilence.app.ui.navigation.DeepLinkRouter
import com.unsilence.app.ui.navigation.DeepLinkTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val TRENDING_WOT_CHUNK_COUNT =
    (TRENDING_CANDIDATE_LIMIT + WOT_ASSERTION_CHUNK_SIZE - 1) / WOT_ASSERTION_CHUNK_SIZE
// WoT chunks run sequentially: coalescing + every possible pool chunk + scheduling margin.
private const val TRENDING_WOT_MEASUREMENT_TIMEOUT_MS =
    WOT_HYDRATION_WINDOW_MS + WOT_FETCH_TIMEOUT_MS * TRENDING_WOT_CHUNK_COUNT + 1_500L

data class SearchUiState(
    val query: String           = "",
    val peopleResults: List<UserEntity> = emptyList(),
    val impersonationRisks: Map<String, ImpersonationRisk> = emptyMap(),
    val noteResults: List<FeedRow>      = emptyList(),
    val tagResults: List<FeedRow>       = emptyList(),
    val entityTarget: DeepLinkTarget?   = null,
    val entityProfile: UserEntity?      = null,
    val rejectedSecret: Boolean         = false,
    val loading: Boolean        = false,
    val hasSearched: Boolean    = false,
)

private data class SearchSafetyBundle(
    val muteList: MuteList?,
    val hashtagCap: Int?,
    val followedPubkeys: Set<String>?,
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
    private val profilePipeline: ProfilePipeline,
    private val deepLinkRouter: DeepLinkRouter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** NIP-36 sensitive-content display mode (shared with feed). */
    val sensitiveContentMode: StateFlow<com.unsilence.app.data.memory.SensitiveContentMode> =
        relayPreferencesStore.sensitiveContentMode

    private val discoveryOwner = keyManager.getPublicKeyHex().orEmpty()
    private val discoveryActive = MutableStateFlow(false)
    private val _discovery = MutableStateFlow(SearchDiscoveryState())
    internal val discovery: StateFlow<SearchDiscoveryState> = _discovery.asStateFlow()

    private fun discoveryOwnerIsCurrent(): Boolean =
        discoveryOwner.isNotBlank() && keyManager.getPublicKeyHex() == discoveryOwner &&
            memoryEventStore.ownPubkey == discoveryOwner

    fun setDiscoveryActive(active: Boolean) {
        discoveryActive.value = active
        if (active && discoveryOwnerIsCurrent()) trendingClient.refreshIfStale()
    }

    fun retryDiscovery() {
        if (discoveryActive.value && discoveryOwnerIsCurrent()) trendingClient.retry()
    }

    private val screenResumed = MutableStateFlow(false)

    fun setScreenResumed(resumed: Boolean) {
        screenResumed.value = resumed
        if (!resumed) {
            discoveryActive.value = false
            searchRequests.close()
            _uiState.update { it.copy(loading = false) }
        }
    }

    private val _queryFlow = MutableStateFlow("")
    private val _entityTarget = MutableStateFlow<DeepLinkTarget?>(null)
    private val _wotSubjects = MutableStateFlow<Set<String>>(emptySet())
    val wotLookups: StateFlow<Map<String, WotLookup>> =
        combine(_wotSubjects, memoryEventStore.wotSignalFlow) { subjects, _ ->
            wotLookupSnapshot(subjects, memoryEventStore::wotFor)
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val feedWotDisplayMode: StateFlow<FeedWotDisplayMode> =
        relayPreferencesStore.feedWotDisplayModeFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, FeedWotDisplayMode.NUMBERS)

    /** Accumulates event IDs that arrive on search-notes-* subscriptions from RelayPool. */
    private val _searchResultEventIds = MutableStateFlow<Set<String>>(emptySet())

    private val searchRequests = SearchRequestLifetime(closeRequest = relayPool::closeSearch)

    fun search(query: String) {
        if (_queryFlow.value == query) return
        searchRequests.queryChanged() // Fence the old query before the debounce.
        _searchResultEventIds.value = emptySet()
        _wotSubjects.value = emptySet()
        val entityInput = detectSearchEntityInput(query)
        val target = (entityInput as? SearchEntityInput.Resolved)?.target
        _entityTarget.value = target
        _queryFlow.value = query
        _uiState.value = SearchUiState(
            query = query,
            entityTarget = target,
            entityProfile = (target as? DeepLinkTarget.Profile)?.let {
                memoryEventStore.getUserEntity(it.pubkey)
            },
            rejectedSecret = entityInput == SearchEntityInput.RejectedSecret,
            loading = query.length >= 3 && entityInput != SearchEntityInput.RejectedSecret,
            hasSearched = query.length >= 3 || target != null || entityInput == SearchEntityInput.RejectedSecret,
        )
    }

    fun openEntityTarget(target: DeepLinkTarget) {
        if (_entityTarget.value == target) deepLinkRouter.submit(target)
    }

    fun profileFlow(pubkey: String): CardDataFlow<UserEntity?> =
        timelineCardData.profileFlow(pubkey)

    fun statsFlow(eventId: String): CardDataFlow<EventStats> =
        timelineCardData.statsFlow(eventId)

    fun zapDetailsForEvent(eventId: String): List<ZapDetail> =
        timelineCardData.zapDetailsForEvent(eventId)

    fun repostPubkeysForEvent(eventId: String): List<String> =
        timelineCardData.repostPubkeysForEvent(eventId)

    fun reactionsForEvent(eventId: String): List<ReactionInfo> =
        timelineCardData.reactionsForEvent(eventId)

    init {
        viewModelScope.launch(Dispatchers.Default) {
            val hydration = DiscoveryHydration()
            var measurement: kotlinx.coroutines.Job? = null
            searchDiscoveryFlow(
                store = memoryEventStore,
                owner = discoveryOwner,
                provider = trendingClient.state,
                active = discoveryActive,
                ownerIsCurrent = ::discoveryOwnerIsCurrent,
            ).collect { projection ->
                if (!discoveryOwnerIsCurrent()) {
                    _discovery.value = SearchDiscoveryState()
                    return@collect
                }
                _discovery.value = projection
                val requests = hydration.next(projection)
                if (requests.profiles.isNotEmpty()) launch(Dispatchers.IO) {
                    if (discoveryOwnerIsCurrent()) {
                        userRepository.fetchProfilesWithFanout(requests.profiles, maxRelays = 4)
                    }
                }
                val authors = requests.wot
                if (authors.isNotEmpty()) {
                    wotHydrationCoalescer.requestHydration(authors)
                    measurement?.cancel()
                    measurement = launch {
                        val summary = withTimeoutOrNull(TRENDING_WOT_MEASUREMENT_TIMEOUT_MS) {
                            memoryEventStore.wotSignalFlow.map {
                                trendingWotCoverage(authors, memoryEventStore::wotFor)
                            }.first { it.settled }
                        } ?: trendingWotCoverage(authors, memoryEventStore::wotFor)
                        if (discoveryOwnerIsCurrent()) Log.w("SearchViewModel", summary.logMessage())
                    }
                }
            }
        }

        // Collect search result IDs for the lifetime of the ViewModel.
        // Only accumulate results matching the current search token —
        // late arrivals from previous queries are silently dropped.
        viewModelScope.launch {
            relayPool.searchResults.collect { result ->
                if (result.token != 0L && result.token == searchRequests.token) {
                    _searchResultEventIds.update { it + result.eventId }
                }
            }
        }

        viewModelScope.launch {
            screenResumed.flatMapLatest { resumed ->
                if (resumed) _entityTarget else flowOf(null)
            }.collectLatest { target ->
                if (!screenResumed.value) return@collectLatest
                if (target !is DeepLinkTarget.Profile) {
                    _uiState.update { it.copy(entityProfile = null) }
                    return@collectLatest
                }
                _wotSubjects.update { it + target.pubkey }
                wotHydrationCoalescer.requestHydration(listOf(target.pubkey))
                profilePipeline.fetchProfileMetadata(target.pubkey, target.relayHints)
                memoryEventStore.userEntityFlow(target.pubkey).collect { profile ->
                    if (_entityTarget.value == target) {
                        _uiState.update { it.copy(entityProfile = profile) }
                    }
                }
            }
        }

        viewModelScope.launch {
            screenResumed.searchQueriesWhileResumed(_queryFlow).collectLatest { query ->
                searchRequests.close()
                if (query == null || query != _queryFlow.value || !discoveryOwnerIsCurrent()) return@collectLatest

                if (query.length < 3) {
                    _searchResultEventIds.value = emptySet()
                    _wotSubjects.value = emptySet()
                    _uiState.update {
                        it.copy(
                            peopleResults = emptyList(),
                            impersonationRisks = emptyMap(),
                            noteResults   = emptyList(),
                            tagResults    = emptyList(),
                            entityTarget = null,
                            entityProfile = null,
                            rejectedSecret = false,
                            loading       = false,
                            hasSearched   = false,
                        )
                    }
                    return@collectLatest
                }

                if (detectSearchEntityInput(query) == SearchEntityInput.RejectedSecret) {
                    _searchResultEventIds.value = emptySet()
                    _wotSubjects.value = emptySet()
                    _uiState.update {
                        it.copy(
                            peopleResults = emptyList(),
                            impersonationRisks = emptyMap(),
                            noteResults = emptyList(),
                            tagResults = emptyList(),
                            entityTarget = null,
                            entityProfile = null,
                            rejectedSecret = true,
                            loading = false,
                            hasSearched = true,
                        )
                    }
                    return@collectLatest
                }

                val ownPubkey = keyManager.getPublicKeyHex() ?: ""
                val userSearchRelays = memoryEventStore.getSearchRelayUrls(ownPubkey)
                val searchRelays = userSearchRelays.ifEmpty { DEFAULT_SEARCH_RELAY_URLS }

                // A covered visit retains IDs/rows, but owns no socket subscription.
                // Re-entering rebuilds the local projection without another REQ.
                val attempt = searchRequests.begin()
                val token = attempt.token
                try {
                    val explicitHashtagTag = extractExplicitHashtagQuery(query)
                    val tagSearchTag = extractTagSearchQuery(query)

                    if (attempt.fetch) {
                        _uiState.update { it.copy(loading = true, hasSearched = true) }
                        if (explicitHashtagTag != null) {
                            relayPool.searchHashtag(searchRelays, explicitHashtagTag, token)
                        } else {
                            relayPool.searchNotes(searchRelays, query, token)
                        }
                    }

                    // Relay results: events whose IDs were emitted by RelayPool's search-notes subs.
                    // flatMapLatest re-queries MES each time new IDs arrive.
                    val relayResults = _searchResultEventIds
                        .flatMapLatest { ids ->
                            if (ids.isEmpty()) flowOf(emptyList())
                            else memoryEventStore.feedSignalFlow.map {
                                memoryEventStore.feedRowsByIds(ids)
                            }.distinctUntilChanged()
                        }.conflate().flowOn(Dispatchers.Default)

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
                        memoryEventStore.followsSignalFlow.map {
                            memoryEventStore.getFollows(ownPubkey)?.toSet()
                        }.distinctUntilChanged(),
                    ) { muteList, hashtagCap, followedPubkeys ->
                        SearchSafetyBundle(muteList, hashtagCap, followedPubkeys)
                    }
                    combine(searchResultsFlow, safetyFlow) { results, safety ->
                        results.copy(
                            muteList = safety.muteList,
                            hashtagCap = safety.hashtagCap,
                            followedPubkeys = safety.followedPubkeys,
                        )
                    }.projectSearchResults(
                        query = query,
                        explicitHashtag = explicitHashtagTag != null,
                        eventProvider = memoryEventStore::getNostrEvent,
                        cachedModelProvider = memoryEventStore::getEventModel,
                        wotLookup = memoryEventStore::wotFor,
                        protectedProfiles = { buildProtectedProfiles(ownPubkey) },
                    ).collectWithInitialSearchLoading(
                        fetch = attempt.fetch,
                        isCurrent = {
                            searchRequests.token == token && _queryFlow.value == query &&
                                screenResumed.value && discoveryOwnerIsCurrent()
                        },
                        hasResults = { projection ->
                            _entityTarget.value != null || projection.notes.isNotEmpty() ||
                                projection.people.isNotEmpty() || projection.tags.isNotEmpty()
                        },
                        onLoadingChanged = { loading -> _uiState.update { it.copy(loading = loading) } },
                    ) { projection ->
                        val wotSubjects = buildSet {
                            (_entityTarget.value as? DeepLinkTarget.Profile)?.pubkey?.let(::add)
                            addAll(projection.wotSubjects)
                        }
                        _wotSubjects.value = wotSubjects
                        wotHydrationCoalescer.requestHydration(wotSubjects)
                        _uiState.update {
                            it.copy(
                                noteResults   = projection.notes,
                                tagResults    = projection.tags,
                                peopleResults = projection.people,
                                impersonationRisks = projection.impersonationRisks,
                            )
                        }
                    }
                } finally {
                    searchRequests.close(token)
                }
            }
        }
    }

    fun requestWotHydration(pubkeys: Collection<String>) {
        if (pubkeys.isEmpty()) return
        _wotSubjects.update { current -> current + pubkeys }
        wotHydrationCoalescer.requestHydration(pubkeys)
    }

    /** Covered entries retain bounded results in their VM, not active relay work. */
    fun onScreenLeft() { setScreenResumed(false) }

    override fun onCleared() {
        searchRequests.close()
        super.onCleared()
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

internal fun mergeTrendingUserWithLatest(
    candidate: UserEntity,
    latest: UserEntity?,
): UserEntity {
    if (latest == null) return candidate
    return latest.copy(
        followerCount = candidate.followerCount ?: latest.followerCount,
        followerCountUpdatedAt = candidate.followerCountUpdatedAt ?: latest.followerCountUpdatedAt,
    )
}

internal fun rankTrendingUsers(
    users: List<UserEntity>,
    lookup: (String) -> WotLookup,
): List<UserEntity> {
    val scored = ArrayList<UserEntity>()
    val pending = ArrayList<UserEntity>()
    val absent = ArrayList<UserEntity>()
    users.forEach { user ->
        when (lookup(user.pubkey)) {
            is WotLookup.Scored -> scored.add(user)
            WotLookup.Pending -> pending.add(user)
            WotLookup.Absent -> absent.add(user)
        }
    }
    return scored + pending + absent
}

internal fun trendingFollowerCountLabel(
    indexedCount: Long?,
    lookup: WotLookup?,
): String? = when (
    val count = reconciledFollowerCount(
        indexedCount = indexedCount,
        knownFollowers = 0,
        trustedFollowerEstimate = wotVerifiedFollowers(lookup),
    )
) {
    FollowerCount.Unknown -> null
    else -> formatFollowerCount(count)
}

internal data class TrendingWotCoverage(
    val authors: Int,
    val scored: Int,
    val pending: Int,
    val absent: Int,
    val verifiedFollowers: Int,
) {
    val settled: Boolean get() = pending == 0

    fun logMessage(): String =
        "TRENDING-WOT authors=$authors scored=$scored pending=$pending " +
            "absent=$absent verifiedFollowers=$verifiedFollowers"
}

internal fun trendingWotCoverage(
    authors: Collection<String>,
    lookup: (String) -> WotLookup,
): TrendingWotCoverage {
    var scored = 0
    var pending = 0
    var absent = 0
    var verifiedFollowers = 0
    authors.distinct().forEach { pubkey ->
        when (val state = lookup(pubkey)) {
            is WotLookup.Scored -> {
                scored += 1
                if (state.assertion.verifiedFollowers != null) verifiedFollowers += 1
            }
            WotLookup.Pending -> pending += 1
            WotLookup.Absent -> absent += 1
        }
    }
    return TrendingWotCoverage(
        authors = scored + pending + absent,
        scored = scored,
        pending = pending,
        absent = absent,
        verifiedFollowers = verifiedFollowers,
    )
}

internal fun filterSearchNoteRows(
    rows: List<FeedRow>,
    muteList: MuteList?,
    hashtagCap: Int?,
    eventProvider: ((String) -> com.unsilence.app.data.memory.NostrEvent?)? = null,
): List<FeedRow> = rows.filterNot { row ->
    val event = eventProvider?.invoke(row.id)
    if (event != null) {
        isMuted(event, muteList, eventProvider) || exceedsHashtagCap(event, hashtagCap, eventProvider)
    } else {
        isMuted(row, muteList) || exceedsHashtagCap(row, hashtagCap)
    }
}

internal fun filterSearchPeople(
    users: List<UserEntity>,
    muteList: MuteList?,
): List<UserEntity> = users.filterNot { user ->
    isPubkeyMuted(user.pubkey, muteList)
}
