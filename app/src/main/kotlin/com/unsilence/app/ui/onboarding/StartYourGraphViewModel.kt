package com.unsilence.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.init.InitGate
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotAssertionEntity
import com.unsilence.app.data.relay.FollowPack
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.GraphLanding
import com.unsilence.app.data.relay.NOTABLE_PEOPLE_LIMIT
import com.unsilence.app.data.relay.PrimalCacheClient
import com.unsilence.app.data.relay.PrimalSuggestedProfile
import com.unsilence.app.data.relay.ProfilePipeline
import com.unsilence.app.data.relay.ProfileResolver
import com.unsilence.app.data.relay.RankedFollowPack
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.relay.graphLanding
import com.unsilence.app.data.relay.latestFollowPacks
import com.unsilence.app.data.relay.rankFollowPacks
import com.unsilence.app.data.relay.shouldAutoOpenStartGraph
import com.unsilence.app.data.relay.shouldShowEmptyFollowingEntry
import com.unsilence.app.data.relay.topFollowPackMembers
import com.unsilence.app.data.relay.topNotablePubkeys
import com.unsilence.app.data.repository.FollowBatchPublisher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

internal data class StartGraphPersonUi(
    val pubkey: String,
    val user: UserEntity,
    val wot: WotAssertionEntity?,
    val followerCount: Long?,
)

internal data class StartGraphPackUi(
    val coordinate: String,
    val title: String,
    val memberCount: Int,
    val topMembers: List<StartGraphPersonUi>,
)

internal data class StartGraphUiState(
    val loading: Boolean = false,
    val publishing: Boolean = false,
    val packs: List<StartGraphPackUi> = emptyList(),
    val notablePeople: List<StartGraphPersonUi> = emptyList(),
    val notableFromGrapevine: Boolean = true,
    val selectedPackCoordinates: Set<String> = emptySet(),
    val selectedPeople: Set<String> = emptySet(),
    val selectedFollowCount: Int = 0,
    val error: String? = null,
)

@HiltViewModel
internal class StartYourGraphViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val initGate: InitGate,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val relayPreferencesStore: RelayPreferencesStore,
    private val profileResolver: ProfileResolver,
    private val profilePipeline: ProfilePipeline,
    private val primalCacheClient: PrimalCacheClient,
    private val followBatchPublisher: FollowBatchPublisher,
) : ViewModel() {
    private val ownPubkey = keyManager.getPublicKeyHex()
    private val _uiState = MutableStateFlow(StartGraphUiState())
    val uiState: StateFlow<StartGraphUiState> = _uiState.asStateFlow()

    private val _autoOpen = MutableStateFlow(false)
    val autoOpen: StateFlow<Boolean> = _autoOpen.asStateFlow()
    private val _showEmptyFollowingEntry = MutableStateFlow(false)
    val showEmptyFollowingEntry: StateFlow<Boolean> = _showEmptyFollowingEntry.asStateFlow()

    private val landingChannel = Channel<GraphLanding>(capacity = Channel.BUFFERED)
    val landingEvents = landingChannel.receiveAsFlow()

    private var packs: List<FollowPack> = emptyList()
    private var notablePubkeys: List<String> = emptyList()
    private var notableFromGrapevine = true
    private var primalProfiles: Map<String, PrimalSuggestedProfile> = emptyMap()
    private val followerCounts = ConcurrentHashMap<String, Long>()
    private val requestedFollowerCounts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var loadStarted = false

    init {
        viewModelScope.launch {
            initGate.awaitFollows()
            var follows = ownPubkey?.let(memoryEventStore::getFollows)
            if (follows == null && keyManager.isGraphKnownEmpty()) {
                follows = materializeEmptyFollowsIfNeeded()
            }
            val freshPending = keyManager.isGraphOnboardingPending()
            if (follows?.isNotEmpty() == true && !keyManager.isGraphOnboardingCompleted()) {
                keyManager.completeGraphOnboarding(hasFollows = true)
            }
            _autoOpen.value = shouldAutoOpenStartGraph(
                freshIdentityPending = freshPending,
                onboardingCompleted = keyManager.isGraphOnboardingCompleted(),
                follows = follows,
            )
            _showEmptyFollowingEntry.value = shouldShowEmptyFollowingEntry(
                followsResolved = true,
                follows = follows,
            )
            if (_autoOpen.value) open()
        }
        viewModelScope.launch {
            memoryEventStore.followsSignalFlow.collect {
                val follows = ownPubkey?.let(memoryEventStore::getFollows)
                if (initGate.followsReady) {
                    if (follows?.isNotEmpty() == true && !keyManager.isGraphOnboardingCompleted()) {
                        keyManager.completeGraphOnboarding(hasFollows = true)
                    } else if (shouldAutoOpenStartGraph(
                            freshIdentityPending = keyManager.isGraphOnboardingPending(),
                            onboardingCompleted = keyManager.isGraphOnboardingCompleted(),
                            follows = follows,
                        )
                    ) {
                        _autoOpen.value = true
                        open()
                    }
                }
                _showEmptyFollowingEntry.value = shouldShowEmptyFollowingEntry(
                    followsResolved = initGate.followsReady,
                    follows = follows,
                )
                rebuildUi()
            }
        }
        viewModelScope.launch {
            memoryEventStore.profileSignalFlow.collect { rebuildUi() }
        }
        viewModelScope.launch {
            memoryEventStore.wotSignalFlow.collect { rebuildUi() }
        }
    }

    fun consumeAutoOpen() {
        _autoOpen.value = false
    }

    fun open() {
        if (loadStarted) return
        loadStarted = true
        viewModelScope.launch { loadGraphChoices() }
    }

    fun retry() {
        if (_uiState.value.loading || _uiState.value.publishing) return
        loadStarted = true
        viewModelScope.launch { loadGraphChoices() }
    }

    fun togglePack(coordinate: String) {
        val current = _uiState.value
        val selected = current.selectedPackCoordinates.toMutableSet().apply {
            if (!add(coordinate)) remove(coordinate)
        }
        _uiState.value = current.copy(selectedPackCoordinates = selected)
        rebuildUi()
    }

    fun togglePerson(pubkey: String) {
        val current = _uiState.value
        val selected = current.selectedPeople.toMutableSet().apply {
            if (!add(pubkey)) remove(pubkey)
        }
        _uiState.value = current.copy(selectedPeople = selected)
        rebuildUi()
    }

    fun requestVisiblePerson(pubkey: String) {
        if (!requestedFollowerCounts.add(pubkey)) return
        viewModelScope.launch {
            val count = profilePipeline.fetchFollowerCount(pubkey)
            if (count != null) {
                followerCounts[pubkey] = count
                rebuildUi()
            }
        }
    }

    fun finish() {
        if (_uiState.value.publishing) return
        viewModelScope.launch {
            val selected = selectedPubkeys()
            _uiState.value = _uiState.value.copy(publishing = selected.isNotEmpty(), error = null)
            val follows = if (selected.isEmpty()) {
                materializeEmptyFollowsIfNeeded()
            } else {
                followBatchPublisher.addFollows(selected)
            }
            if (follows == null) {
                _uiState.value = _uiState.value.copy(
                    publishing = false,
                    error = "Couldn't publish follows. Try again.",
                )
                return@launch
            }
            keyManager.completeGraphOnboarding(hasFollows = follows.isNotEmpty())
            _showEmptyFollowingEntry.value = follows.isEmpty()
            landingChannel.send(graphLanding(follows))
            _uiState.value = _uiState.value.copy(publishing = false)
        }
    }

    private suspend fun loadGraphChoices() {
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        try {
            val existingFollows = ownPubkey?.let(memoryEventStore::getFollows).orEmpty()
            val initialAssertions = memoryEventStore.getWotAssertions()
            notablePubkeys = topNotablePubkeys(
                assertions = initialAssertions.values,
                ownPubkey = ownPubkey,
                alreadyFollowed = existingFollows,
            )
            notableFromGrapevine = notablePubkeys.isNotEmpty()

            val relayTargets = interleavedPackRelays(
                GLOBAL_RELAY_URLS,
                relayPreferencesStore.indexerRelayUrlsSuspending(),
            )
            val (packEvents, fallbackProfiles) = coroutineScope {
                val packsDeferred = async(Dispatchers.IO) {
                    relayPool.fetchFollowPackEvents(relayTargets)
                }
                val fallbackDeferred = if (!notableFromGrapevine && ownPubkey != null) {
                    async {
                        primalCacheClient.fetchTrendingProfiles(ownPubkey, NOTABLE_PEOPLE_LIMIT)
                            .orEmpty()
                    }
                } else {
                    null
                }
                packsDeferred.await() to fallbackDeferred?.await().orEmpty()
            }

            val hydratedNotable = topNotablePubkeys(
                assertions = memoryEventStore.getWotAssertions().values,
                ownPubkey = ownPubkey,
                alreadyFollowed = existingFollows,
            )
            if (hydratedNotable.isNotEmpty()) {
                notableFromGrapevine = true
                notablePubkeys = hydratedNotable
                primalProfiles = emptyMap()
            } else if (!notableFromGrapevine) {
                val fallback = fallbackProfiles
                    .filter { it.pubkey != ownPubkey && it.pubkey !in existingFollows }
                primalProfiles = fallback.associateBy(PrimalSuggestedProfile::pubkey)
                fallback.forEach { profile ->
                    profile.followerCount?.let { followerCounts[profile.pubkey] = it }
                }
                notablePubkeys = fallback.map(PrimalSuggestedProfile::pubkey)
            }
            packs = latestFollowPacks(packEvents)

            val profileTargets = LinkedHashSet<String>()
            val assertions = memoryEventStore.getWotAssertions()
            packs.forEach { pack ->
                profileTargets.addAll(topFollowPackMembers(pack, assertions))
            }
            profileTargets.addAll(notablePubkeys)
            profileResolver.request(profileTargets.toList())

            rebuildUi(loading = false)
            if (packs.isEmpty() && notablePubkeys.isEmpty()) {
                val follows = materializeEmptyFollowsIfNeeded().orEmpty()
                keyManager.completeGraphOnboarding(hasFollows = follows.isNotEmpty())
                landingChannel.send(graphLanding(follows))
                return
            }

            val creatorPubkeys = packs.map(FollowPack::authorPubkey).distinct()
            if (creatorPubkeys.isNotEmpty()) {
                val provider = memoryEventStore.activeWotProvider()
                try {
                    withContext(Dispatchers.IO) {
                        relayPool.fetchWotAssertions(
                            providerPubkey = provider.providerPubkey,
                            relayHint = provider.relayHint,
                            subjects = creatorPubkeys,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Ranking enrichment is optional; loaded packs remain usable.
                }
                rebuildUi()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            rebuildUi(loading = false, error = "Couldn't load suggestions")
            if (packs.isEmpty() && notablePubkeys.isEmpty()) {
                val follows = materializeEmptyFollowsIfNeeded().orEmpty()
                keyManager.completeGraphOnboarding(hasFollows = follows.isNotEmpty())
                landingChannel.send(graphLanding(follows))
            }
        }
    }

    private fun rebuildUi(
        loading: Boolean = _uiState.value.loading,
        error: String? = _uiState.value.error,
    ) {
        val current = _uiState.value
        val assertions = memoryEventStore.getWotAssertions()
        val ranked = rankFollowPacks(packs, assertions)
        val packUi = ranked.map { rankedPack -> rankedPack.toUi(assertions) }
        val notableUi = notablePubkeys.map { pubkey -> personUi(pubkey, assertions) }
        _uiState.value = current.copy(
            loading = loading,
            packs = packUi,
            notablePeople = notableUi,
            notableFromGrapevine = notableFromGrapevine,
            selectedFollowCount = selectedPubkeys().size,
            error = error,
        )
    }

    private fun RankedFollowPack.toUi(
        assertions: Map<String, WotAssertionEntity>,
    ): StartGraphPackUi = StartGraphPackUi(
        coordinate = pack.coordinate,
        title = pack.title,
        memberCount = pack.memberPubkeys.size,
        topMembers = topFollowPackMembers(pack, assertions).map { personUi(it, assertions) },
    )

    private fun personUi(
        pubkey: String,
        assertions: Map<String, WotAssertionEntity>,
    ): StartGraphPersonUi {
        val primal = primalProfiles[pubkey]
        val user = memoryEventStore.getUserEntity(pubkey) ?: UserEntity(
            pubkey = pubkey,
            name = primal?.name,
            displayName = primal?.displayName,
            picture = primal?.picture,
            about = primal?.about,
            nip05 = primal?.nip05,
        )
        val cachedCount = memoryEventStore.getFollowerCount(pubkey).first
        return StartGraphPersonUi(
            pubkey = pubkey,
            user = user,
            wot = assertions[pubkey],
            followerCount = followerCounts[pubkey] ?: cachedCount ?: primal?.followerCount,
        )
    }

    private fun selectedPubkeys(): Set<String> {
        val current = _uiState.value
        val selected = LinkedHashSet(current.selectedPeople)
        packs.asSequence()
            .filter { it.coordinate in current.selectedPackCoordinates }
            .forEach { selected.addAll(it.memberPubkeys) }
        ownPubkey?.let(selected::remove)
        memoryEventStore.getFollows(ownPubkey.orEmpty())?.let(selected::removeAll)
        return selected
    }

    private fun materializeEmptyFollowsIfNeeded(): Set<String>? {
        val own = ownPubkey ?: return null
        val existing = memoryEventStore.getFollows(own)
        if (existing != null) return existing
        val now = System.currentTimeMillis() / 1000L
        memoryEventStore.updateFollows(own, emptySet(), now)
        return emptySet()
    }
}

internal fun interleavedPackRelays(
    globalRelays: List<String>,
    indexerRelays: List<String>,
): List<String> = buildList {
    repeat(maxOf(globalRelays.size, indexerRelays.size)) { index ->
        globalRelays.getOrNull(index)?.let(::add)
        indexerRelays.getOrNull(index)?.let(::add)
    }
}.distinct()
