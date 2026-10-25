package com.unsilence.app.ui.search

import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.LocalDiscoverySnapshot
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.memory.isPubkeyMuted
import com.unsilence.app.data.memory.normalizedMutedHashtags
import com.unsilence.app.data.relay.TrendingOutcome
import com.unsilence.app.data.relay.TrendingState
import com.unsilence.app.data.relay.wotLookupSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform

internal enum class DiscoverySource { Provider, Local }
internal enum class DiscoveryStatus { Loading, Ready, Empty, Unavailable }

internal data class SearchDiscoveryState(
    val hashtags: List<Pair<String, Int>> = emptyList(),
    val users: List<UserEntity> = emptyList(),
    val hashtagSource: DiscoverySource = DiscoverySource.Local,
    val peopleSource: DiscoverySource = DiscoverySource.Local,
    val hashtagStatus: DiscoveryStatus = DiscoveryStatus.Empty,
    val peopleStatus: DiscoveryStatus = DiscoveryStatus.Empty,
    val refreshing: Boolean = false,
    val wotLookups: Map<String, WotLookup> = emptyMap(),
    val candidatePubkeys: Set<String> = emptySet(),
) {
    val hasContent: Boolean get() = hashtags.isNotEmpty() || users.isNotEmpty()
    val loading: Boolean get() = !hasContent &&
        (hashtagStatus == DiscoveryStatus.Loading || peopleStatus == DiscoveryStatus.Loading)
    val unavailable: Boolean get() = !hasContent &&
        (hashtagStatus == DiscoveryStatus.Unavailable || peopleStatus == DiscoveryStatus.Unavailable)
}

internal data class TrendingCandidates(
    val hashtags: List<Pair<String, Int>> = emptyList(),
    val users: List<UserEntity> = emptyList(),
    val hashtagSource: DiscoverySource = DiscoverySource.Provider,
    val peopleSource: DiscoverySource = DiscoverySource.Provider,
    val provider: TrendingState = TrendingState(),
)

/** Session-local request dedup; profile/rank re-emissions do not refetch the same set. */
internal class DiscoveryHydration {
    private val profiles = LinkedHashSet<String>()
    private var candidates: Set<String> = emptySet()

    fun next(state: SearchDiscoveryState): DiscoveryHydrationRequest {
        val missing = state.users.asSequence().filter {
            (it.name.isNullOrBlank() && it.displayName.isNullOrBlank()) || it.picture.isNullOrBlank()
        }.map(UserEntity::pubkey).filter { pubkey ->
            val added = profiles.add(pubkey)
            if (profiles.size > 256) {
                val oldest = profiles.iterator()
                oldest.next()
                oldest.remove()
            }
            added
        }.toList()
        val wot = if (state.candidatePubkeys != candidates) state.candidatePubkeys else emptySet()
        candidates = state.candidatePubkeys
        return DiscoveryHydrationRequest(missing, wot)
    }
}

internal data class DiscoveryHydrationRequest(val profiles: List<String>, val wot: Set<String>)

/** Shared by the Search collector and its behavioral tests. */
internal fun providerDiscoveryCandidates(provider: Flow<TrendingState>): Flow<TrendingCandidates> =
    provider.map { state ->
        val data = state.data
        TrendingCandidates(
            hashtags = data?.hashtags.orEmpty().take(32).map { it.tag to it.score.toInt().coerceAtLeast(1) },
            users = data?.profiles.orEmpty().take(32).map { profile ->
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
            provider = state,
        )
    }

/**
 * The production Search collection path, also exercised with a real MES in JVM tests.
 * Only content/follow/mute changes rescan MES. Profile changes resolve the bounded
 * candidate IDs instead. Conflation plus a trailing delay preserves the latest
 * burst without a debounce starvation loop or idle polling.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal fun searchDiscoveryFlow(
    store: MemoryEventStore,
    owner: String,
    provider: StateFlow<TrendingState>,
    active: StateFlow<Boolean>,
    ownerIsCurrent: () -> Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    scan: () -> LocalDiscoverySnapshot = { store.discoverySnapshot(owner) },
): Flow<SearchDiscoveryState> = active.flatMapLatest { isActive ->
    if (!isActive) return@flatMapLatest emptyFlow()
    val safety = combine(store.discoveryMuteListFlow(owner, dispatcher), store.followsSignalFlow) { muteList, _ ->
        DiscoverySafety(muteList, store.getFollows(owner).orEmpty())
    }.distinctUntilChanged()
    val local = combine(store.feedSignalFlow, safety) { _, _ -> Unit }
        .conflate()
        .transform {
            emit(if (ownerIsCurrent()) scan() else LocalDiscoverySnapshot())
            delay(1_000L)
        }
        .flowOn(dispatcher)
    val remote = providerDiscoveryCandidates(provider)
    val candidates = combine(remote, local) { remoteData, localData ->
        remoteData.copy(
            hashtags = remoteData.hashtags.ifEmpty { localData.hashtags },
            users = remoteData.users.ifEmpty { localData.users },
            hashtagSource = if (remoteData.hashtags.isEmpty()) DiscoverySource.Local else DiscoverySource.Provider,
            peopleSource = if (remoteData.users.isEmpty()) DiscoverySource.Local else DiscoverySource.Provider,
        )
    }.distinctUntilChanged()
    val wotChanges = store.wotSignalFlow.drop(1).debounce(400L)
        .onStart { emit(store.wotSignalFlow.value) }
    val ranked = combine(candidates, wotChanges) { current, _ ->
        current to wotLookupSnapshot(current.users.map(UserEntity::pubkey), store::wotFor)
    }
    combine(ranked, safety, store.profileSignalFlow) { (current, ranks), account, _ ->
        if (!ownerIsCurrent()) return@combine SearchDiscoveryState()
        val mutedTags = normalizedMutedHashtags(account.muteList)
        val hashtags = current.hashtags
            .filterNot { it.first.trim().trimStart('#').lowercase() in mutedTags }.take(8)
        val eligible = current.users
            .filterNot {
                isPubkeyMuted(it.pubkey, account.muteList) ||
                    (current.peopleSource == DiscoverySource.Local &&
                        (it.pubkey == owner || it.pubkey in account.follows))
            }
            .map {
                if (current.peopleSource == DiscoverySource.Local) store.discoveryUser(it.pubkey)
                else mergeTrendingUserWithLatest(it, store.discoveryUser(it.pubkey, it))
            }
        val users = rankTrendingUsers(eligible) { ranks[it] ?: WotLookup.Pending }.take(8)
        val filteredAll = (current.users.isNotEmpty() || current.hashtags.isNotEmpty()) &&
            users.isEmpty() && hashtags.isEmpty()
        SearchDiscoveryState(
            hashtags = hashtags, users = users,
            hashtagSource = current.hashtagSource, peopleSource = current.peopleSource,
            hashtagStatus = if (filteredAll) DiscoveryStatus.Empty else sectionStatus(hashtags.size, current.hashtags.size, current.provider),
            peopleStatus = if (filteredAll) DiscoveryStatus.Empty else sectionStatus(users.size, current.users.size, current.provider),
            refreshing = current.provider.refreshing, wotLookups = ranks,
            candidatePubkeys = eligible.mapTo(linkedSetOf(), UserEntity::pubkey),
        )
    }
}.distinctUntilChanged().flowOn(dispatcher)

private data class DiscoverySafety(val muteList: MuteList?, val follows: Set<String>)

private fun sectionStatus(visible: Int, candidates: Int, provider: TrendingState): DiscoveryStatus = when {
    visible > 0 -> DiscoveryStatus.Ready
    candidates > 0 -> DiscoveryStatus.Empty // Filtered out: never turn moderation into a spinner.
    provider.outcome == TrendingOutcome.Success -> DiscoveryStatus.Empty // Counts may still be warming.
    provider.refreshing -> DiscoveryStatus.Loading
    provider.outcome == TrendingOutcome.Unavailable -> DiscoveryStatus.Unavailable
    else -> DiscoveryStatus.Empty
}
