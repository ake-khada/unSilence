package com.unsilence.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.relay.WotHydrationCoalescer
import com.unsilence.app.data.relay.followsViewer
import com.unsilence.app.data.relay.nextFollowersCursor
import com.unsilence.app.data.relay.ProfilePipeline
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.wotRank
import com.unsilence.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

enum class ConnectionsTab { Following, Followers }

data class ConnectionRow(
    val pubkey: String,
    val user: UserEntity,
    val wot: WotLookup,
    val followsViewer: Boolean,
)

data class ConnectionsUiState(
    val subjectPubkey: String = "",
    val selectedTab: ConnectionsTab = ConnectionsTab.Following,
    val rows: List<ConnectionRow> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMoreFollowers: Boolean = true,
    val loadFailed: Boolean = false,
    val followerCount: Long? = null,
)

private data class ConnectionMembership(
    val subject: String,
    val tab: ConnectionsTab,
    val following: Set<String>,
    val followers: Set<String>,
)

private data class ConnectionsLoading(
    val following: Boolean,
    val followers: Boolean,
    val moreFollowers: Boolean,
    val hasMoreFollowers: Boolean,
    val failedTab: ConnectionsTab?,
    val followerCount: Long?,
)

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    keyManager: KeyManager,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val profilePipeline: ProfilePipeline,
    private val userRepository: UserRepository,
    private val wotHydrationCoalescer: WotHydrationCoalescer,
) : ViewModel() {
    private val viewerPubkey = keyManager.getPublicKeyHex()
    private val subjectPubkey = MutableStateFlow("")
    private val selectedTab = MutableStateFlow(ConnectionsTab.Following)
    private val followingPubkeys = MutableStateFlow<Set<String>>(emptySet())
    private val followerPubkeys = MutableStateFlow<Set<String>>(emptySet())
    private val loadingFollowing = MutableStateFlow(false)
    private val loadingFollowers = MutableStateFlow(false)
    private val loadingMoreFollowers = MutableStateFlow(false)
    private val hasMoreFollowers = MutableStateFlow(true)
    private val failedTab = MutableStateFlow<ConnectionsTab?>(null)
    private val followerCount = MutableStateFlow<Long?>(null)
    private var followersCursor: Long? = null
    private var initializedKey: Pair<String, ConnectionsTab>? = null
    private val followerVerificationMutex = Mutex()
    private val verifiedContactListVersions = mutableMapOf<String, Long>()

    private val membership = combine(
        subjectPubkey,
        selectedTab,
        followingPubkeys,
        followerPubkeys,
    ) { subject, tab, following, followers ->
        ConnectionMembership(subject, tab, following, followers)
    }

    private val hydrationSignal = combine(
        memoryEventStore.profileSignalFlow,
        memoryEventStore.wotSignalFlow,
        memoryEventStore.followsSignalFlow,
    ) { profile, wot, follows -> profile + wot + follows }

    private val baseLoadingState = combine(
        loadingFollowing,
        loadingFollowers,
        loadingMoreFollowers,
        hasMoreFollowers,
        followerCount,
    ) { following, followers, moreFollowers, hasMore, count ->
        ConnectionsLoading(following, followers, moreFollowers, hasMore, null, count)
    }

    private val loadingState = combine(baseLoadingState, failedTab) { loading, failed ->
        loading.copy(failedTab = failed)
    }

    val uiState: StateFlow<ConnectionsUiState> = combine(
        membership,
        hydrationSignal,
        loadingState,
    ) { membership, _, loading ->
        val pubkeys = if (membership.tab == ConnectionsTab.Following) {
            membership.following
        } else {
            membership.followers
        }
        val rows = pubkeys.map { pubkey ->
            val user = memoryEventStore.getUserEntity(pubkey) ?: UserEntity(pubkey = pubkey)
            val wot = memoryEventStore.wotFor(pubkey)
            ConnectionRow(
                pubkey = pubkey,
                user = user,
                wot = wot,
                followsViewer = followsViewer(memoryEventStore.getFollows(pubkey), viewerPubkey),
            )
        }.sortedWith(
            compareByDescending<ConnectionRow> { wotRank(it.wot) ?: Int.MIN_VALUE }
                .thenBy { it.user.displayName?.lowercase() ?: it.user.name?.lowercase() ?: it.pubkey },
        )
        ConnectionsUiState(
            subjectPubkey = membership.subject,
            selectedTab = membership.tab,
            rows = rows,
            loading = if (membership.tab == ConnectionsTab.Following) {
                loading.following
            } else {
                loading.followers
            },
            loadingMore = loading.moreFollowers,
            hasMoreFollowers = loading.hasMoreFollowers,
            loadFailed = loading.failedTab == membership.tab,
            followerCount = loading.followerCount,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionsUiState())

    fun initialize(pubkey: String, initialTab: ConnectionsTab) {
        val key = pubkey to initialTab
        if (initializedKey == key) return
        initializedKey = key
        subjectPubkey.value = pubkey
        selectedTab.value = initialTab
        followersCursor = null
        followerPubkeys.value = emptySet()
        followerCount.value = null
        hasMoreFollowers.value = true

        viewModelScope.launch(Dispatchers.IO) {
            followerCount.value = profilePipeline.fetchFollowerCount(pubkey)
        }

        viewModelScope.launch {
            memoryEventStore.followsFlow(pubkey).collect { followingPubkeys.value = it }
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (memoryEventStore.getFollows(pubkey) == null) {
                loadingFollowing.value = true
                try {
                    failedTab.value = null
                    check(relayPool.fetchLatestFollowLists(listOf(pubkey)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failedTab.value = ConnectionsTab.Following
                } finally {
                    loadingFollowing.value = false
                }
            }
        }
        if (initialTab == ConnectionsTab.Followers) loadMoreFollowers(initial = true)
    }

    fun selectTab(tab: ConnectionsTab) {
        selectedTab.value = tab
        if (tab == ConnectionsTab.Followers &&
            followerPubkeys.value.isEmpty() &&
            !loadingFollowers.value
        ) {
            loadMoreFollowers(initial = true)
        }
    }

    fun requestVisibleRows(pubkeys: Collection<String>) {
        val visible = pubkeys.distinct()
        if (visible.isEmpty()) return
        wotHydrationCoalescer.requestHydration(visible)
        viewModelScope.launch { userRepository.fetchMissingProfiles(visible) }
        val missingContactLists = visible.filter { memoryEventStore.getFollows(it) == null }
        if (missingContactLists.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                relayPool.fetchLatestFollowLists(missingContactLists)
            }
        }
    }

    fun loadMoreFollowers(initial: Boolean = false) {
        val subject = subjectPubkey.value.takeIf { it.isNotBlank() } ?: return
        if (loadingFollowers.value || loadingMoreFollowers.value || !hasMoreFollowers.value) return
        if (initial) loadingFollowers.value = true else loadingMoreFollowers.value = true
        failedTab.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                coroutineScope {
                    val pageSignals = Channel<Unit>(Channel.UNLIMITED)
                    val fetch = launch {
                        try {
                            val pages = relayPool.fetchFollowerPage(subject, followersCursor) { _, _ ->
                                pageSignals.trySend(Unit)
                            }
                            check(pages.any { it.totalPages > 0 })
                            val shallowestOldest = pages.map { it.oldestCreatedAt }
                                .filter { it > 0L }
                                .maxOrNull() ?: 0L
                            followersCursor = nextFollowersCursor(shallowestOldest)
                            hasMoreFollowers.value =
                                pages.any { it.totalEvents >= 100 } && followersCursor != null
                        } finally {
                            pageSignals.close()
                        }
                    }
                    for (ignored in pageSignals) verifyAndPublishFollowers(subject)
                    fetch.join()
                    verifyAndPublishFollowers(subject)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failedTab.value = ConnectionsTab.Followers
            } finally {
                loadingFollowers.value = false
                loadingMoreFollowers.value = false
            }
        }
    }

    fun retry() {
        when (selectedTab.value) {
            ConnectionsTab.Following -> {
                val subject = subjectPubkey.value.takeIf { it.isNotBlank() } ?: return
                loadingFollowing.value = true
                failedTab.value = null
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        check(relayPool.fetchLatestFollowLists(listOf(subject)))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failedTab.value = ConnectionsTab.Following
                    } finally {
                        loadingFollowing.value = false
                    }
                }
            }
            ConnectionsTab.Followers -> loadMoreFollowers(initial = followerPubkeys.value.isEmpty())
        }
    }

    private suspend fun verifyAndPublishFollowers(subject: String) {
        followerVerificationMutex.withLock {
            val candidates = memoryEventStore.followersOf(subject)
            if (candidates.isEmpty()) {
                followerPubkeys.value = emptySet()
                return
            }
            val changedCandidates = candidates.filter { author ->
                memoryEventStore.getFollowsCreatedAt(author) != verifiedContactListVersions[author]
            }
            if (changedCandidates.isNotEmpty()) {
                check(relayPool.fetchLatestFollowLists(changedCandidates))
                changedCandidates.forEach { author ->
                    memoryEventStore.getFollowsCreatedAt(author)?.let { createdAt ->
                        verifiedContactListVersions[author] = createdAt
                    }
                }
            }
            val confirmed = memoryEventStore.followersOf(subject)
            followerPubkeys.value = confirmed
            requestVisibleRows(confirmed.take(30))
        }
    }
}
