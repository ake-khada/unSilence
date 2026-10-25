package com.unsilence.app.ui.search

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotAssertionEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.relay.TrendingState
import com.unsilence.app.data.relay.TrendingOutcome
import com.unsilence.app.data.relay.TrendingHashtag
import com.unsilence.app.data.relay.TrendingData
import com.unsilence.app.data.relay.TrendingProfile
import com.unsilence.app.data.relay.TrendingClient
import com.unsilence.app.data.relay.TrendingTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private class DiscoveryHarness(scope: TestScope, source: StateFlow<TrendingState>? = null) {
        val store = discoveryStore()
        val provider = MutableStateFlow(TrendingState(outcome = TrendingOutcome.Unavailable))
        val active = MutableStateFlow(true)
        var scans = 0
        var state = SearchDiscoveryState()
        val hydration = DiscoveryHydration()
        val requests = mutableListOf<DiscoveryHydrationRequest>()
        val job = scope.backgroundScope.launch {
            searchDiscoveryFlow(
                store, "owner", source ?: provider, active, { store.ownPubkey == "owner" },
                StandardTestDispatcher(scope.testScheduler),
                scan = { scans++; store.discoverySnapshot("owner") },
            ).collect {
                state = it
                requests += hydration.next(it)
            }
        }
    }

    private fun providerState(vararg authors: String, tags: List<String> = emptyList()) = TrendingState(
        data = TrendingData(tags.map { TrendingHashtag(it, 1.0) }, authors.map {
            TrendingProfile(it, "Person $it", null, null, null, null, null)
        }),
        outcome = TrendingOutcome.Success,
    )

    @Test
    fun `live production flow handles people only tags only and terminal emptiness`() = runTest {
        val h = DiscoveryHarness(this)
        runCurrent()
        assertTrue(h.state.unavailable)
        h.provider.value = providerState("person")
        runCurrent()
        assertEquals(listOf("person"), h.state.users.map { it.pubkey })
        assertEquals("Person person", h.state.users.single().name)
        assertEquals(DiscoveryStatus.Empty, h.state.hashtagStatus)
        h.provider.value = providerState(tags = listOf("nostr"))
        runCurrent()
        assertEquals(listOf("nostr"), h.state.hashtags.map { it.first })
        assertTrue(h.state.users.isEmpty())
        h.provider.value = TrendingState(outcome = TrendingOutcome.Empty)
        runCurrent()
        assertFalse(h.state.loading)
        assertFalse(h.state.unavailable)
        assertFalse(h.state.hasContent)
    }

    @Test
    fun `empty MES populates while provider has only hashtags without recreating collector`() = runTest {
        val h = DiscoveryHarness(this)
        h.provider.value = providerState(tags = listOf("remote"))
        runCurrent()
        h.store.insert(discoveryEvent("note", "person", tags = listOf(listOf("t", "local"))))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("person"), h.state.users.map { it.pubkey })
        assertEquals(DiscoverySource.Local, h.state.peopleSource)
        assertEquals(DiscoverySource.Provider, h.state.hashtagSource)
        assertEquals(listOf("remote"), h.state.hashtags.map { it.first })
        assertNull(h.state.users.single().followerCount)
        val scans = h.scans
        h.store.cacheFollowerCount("person", 0)
        h.store.insert(discoveryEvent("profile", "person", kind = 0, content = """{"name":"Alice"}"""))
        runCurrent()
        assertEquals("Alice", h.state.users.single().name)
        assertEquals(0L, h.state.users.single().followerCount)
        assertEquals(scans, h.scans) // Profile updates only project the bounded candidates.
    }

    @Test
    fun `local topics fill a people only response and retain local source`() = runTest {
        val h = DiscoveryHarness(this)
        h.store.insert(discoveryEvent("note", tags = listOf(listOf("t", "local"))))
        h.provider.value = providerState("remote-person")
        runCurrent()
        assertEquals(listOf("local"), h.state.hashtags.map { it.first })
        assertEquals(DiscoverySource.Local, h.state.hashtagSource)
        assertEquals(DiscoverySource.Provider, h.state.peopleSource)
        assertEquals(listOf("remote-person"), h.state.users.map { it.pubkey })
    }

    @Test
    fun `following and moderation remove local suggestions immediately and refill within budget`() = runTest {
        val h = DiscoveryHarness(this)
        repeat(40) { h.store.insert(discoveryEvent("note-$it", "person-$it", it.toLong())) }
        runCurrent()
        assertEquals(32, h.state.candidatePubkeys.size)
        val first = h.state.users.first().pubkey
        h.store.updateFollows("owner", setOf(first), 1)
        runCurrent()
        assertFalse(first in h.state.users.map { it.pubkey })
        val muted = h.state.users.first().pubkey
        h.store.insert(discoveryEvent("mute", "owner", kind = 10000, content = "", tags = listOf(listOf("p", muted))))
        runCurrent()
        assertFalse(muted in h.state.users.map { it.pubkey })
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(32, h.state.candidatePubkeys.size)
        assertFalse(first in h.state.candidatePubkeys)
        assertFalse(muted in h.state.candidatePubkeys)
    }

    @Test
    fun `moderation of every candidate is terminal even during count enrichment`() = runTest {
        val h = DiscoveryHarness(this)
        h.provider.value = providerState("person").copy(refreshing = true)
        h.store.insert(discoveryEvent("mute", "owner", kind = 10000, content = "", tags = listOf(listOf("p", "person"))))
        runCurrent()
        assertFalse(h.state.hasContent)
        assertFalse(h.state.loading)
        assertEquals(DiscoveryStatus.Empty, h.state.peopleStatus)
    }

    @Test
    fun `WoT rerank and profile updates do not rescan or rehydrate the same candidates`() = runTest {
        val h = DiscoveryHarness(this)
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val provider = "c".repeat(64)
        h.store.setActiveWotProvider(provider)
        h.provider.value = providerState(a, b)
        runCurrent()
        assertEquals(listOf(a, b), h.state.users.map { it.pubkey })
        val scans = h.scans
        h.store.insert(discoveryEvent("rank", provider, kind = 30382, content = "", tags = listOf(listOf("d", b), listOf("rank", "80"))))
        h.store.markWotSubjectsQueried(listOf(a))
        runCurrent()
        advanceTimeBy(400)
        runCurrent()
        assertEquals(listOf(b, a), h.state.users.map { it.pubkey })
        h.store.insert(discoveryEvent("profile", a, kind = 0, content = """{"name":"Alice"}"""))
        runCurrent()
        assertEquals(scans, h.scans)
        assertEquals(1, h.requests.count { it.wot.isNotEmpty() })
        assertEquals(2, h.requests.sumOf { it.profiles.size })
    }

    @Test
    fun `burst coalesces scans without polling or refreshing provider and stops off screen`() = runTest {
        var discoveryRequests = 0
        var warmRequests = 0
        val transport = object : TrendingTransport {
            override suspend fun fetchTrendingEvents(): List<kotlinx.serialization.json.JsonObject>? {
                discoveryRequests++
                return null
            }
            override suspend fun warmCountRelay() { warmRequests++ }
            override suspend fun fetchFollowerCount(pubkey: String): Long? = error("No provider people")
        }
        val client = TrendingClient(transport, { null }, backgroundScope, { testScheduler.currentTime })
        client.refreshIfStale()
        val h = DiscoveryHarness(this, client.state)
        runCurrent()
        val initialWarmRequests = warmRequests
        assertEquals(1, h.scans)
        repeat(100) { h.store.insert(discoveryEvent("note-$it", time = it.toLong())) }
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, h.scans)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, h.scans)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, h.scans)
        assertEquals(TrendingOutcome.Unavailable, client.state.value.outcome)
        assertEquals(1, discoveryRequests)
        assertEquals(initialWarmRequests, warmRequests)
        h.active.value = false
        runCurrent()
        h.store.insert(discoveryEvent("off-screen", time = 200))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, h.scans)
        h.active.value = true
        runCurrent()
        assertEquals(3, h.scans)
        assertEquals("off-screen", h.state.users.first().pubkey)
        assertEquals(1, discoveryRequests)
    }

    @Test
    fun `owner change and collector teardown fence late local and provider results`() = runTest {
        val h = DiscoveryHarness(this)
        h.store.insert(discoveryEvent("old-person"))
        runCurrent()
        assertTrue(h.state.hasContent)
        h.store.ownPubkey = "next-owner"
        h.provider.value = providerState("late-person")
        runCurrent()
        assertFalse(h.state.hasContent)
        h.job.cancel()
        runCurrent()
        val scans = h.scans
        h.store.insert(discoveryEvent("new-person", time = 200))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(scans, h.scans)
        assertFalse(h.state.hasContent)
    }

    @Test
    fun `hydration requests are bounded to candidate and displayed pools`() {
        val hydration = DiscoveryHydration()
        val state = SearchDiscoveryState(
            users = (1..8).map { UserEntity(pubkey = "person-$it") },
            candidatePubkeys = (1..32).mapTo(linkedSetOf()) { "person-$it" },
        )
        assertEquals(8, hydration.next(state).profiles.size)
        assertEquals(DiscoveryHydrationRequest(emptyList(), emptySet()), hydration.next(state))
        // New candidate sets use existing coalescers; enrichment never restarts them.
        assertEquals(32, DiscoveryHydration().next(state).wot.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `production discovery collector accepts people without hashtags`() = runTest {
        val provider = MutableStateFlow(TrendingState())
        val received = mutableListOf<TrendingCandidates>()
        backgroundScope.launch { providerDiscoveryCandidates(provider).collect { received += it } }
        runCurrent()
        provider.value = TrendingState(data = TrendingData(
            hashtags = emptyList(),
            profiles = listOf(TrendingProfile("author", "Alice", null, null, null, null, null)),
        ))
        runCurrent()
        assertEquals(listOf("author"), received.lastOrNull()?.users?.map { it.pubkey })
    }

    @Test
    fun `tag search query turns bare word into hashtag value`() {
        assertEquals("calle", SearchViewModel.extractTagSearchQuery("Calle"))
    }

    @Test
    fun `tag search query strips optional hash prefix`() {
        assertEquals("nostr", SearchViewModel.extractTagSearchQuery("#Nostr"))
    }

    @Test
    fun `tag search query rejects text phrases`() {
        assertNull(SearchViewModel.extractTagSearchQuery("called calle"))
    }

    @Test
    fun `explicit hashtag query requires hash prefix`() {
        assertEquals("nostr", SearchViewModel.extractExplicitHashtagQuery("#Nostr"))
        assertNull(SearchViewModel.extractExplicitHashtagQuery("Nostr"))
    }

    @Test
    fun `search safety filters remove muted pubkey notes and profiles`() {
        val muteList = muteList(pubkeys = setOf("muted"))
        val mutedNote = row(id = "muted-note", pubkey = "muted")
        val visibleNote = row(id = "visible-note", pubkey = "visible")
        val mutedUser = UserEntity(pubkey = "muted", name = "Muted")
        val visibleUser = UserEntity(pubkey = "visible", name = "Visible")

        assertEquals(listOf(visibleNote), filterSearchNoteRows(listOf(mutedNote, visibleNote), muteList, hashtagCap = null))
        assertEquals(listOf(visibleUser), filterSearchPeople(listOf(mutedUser, visibleUser), muteList))
    }

    @Test
    fun `trending trust tiers are stable and pending precedes absent`() {
        val absentOne = UserEntity(pubkey = "absent-1")
        val scoredOne = UserEntity(pubkey = "scored-1")
        val pendingOne = UserEntity(pubkey = "pending-1")
        val scoredTwo = UserEntity(pubkey = "scored-2")
        val absentTwo = UserEntity(pubkey = "absent-2")
        val pendingTwo = UserEntity(pubkey = "pending-2")
        val users = listOf(absentOne, scoredOne, pendingOne, scoredTwo, absentTwo, pendingTwo)
        val states = mapOf(
            absentOne.pubkey to WotLookup.Absent,
            scoredOne.pubkey to scored(scoredOne.pubkey),
            pendingOne.pubkey to WotLookup.Pending,
            scoredTwo.pubkey to scored(scoredTwo.pubkey),
            absentTwo.pubkey to WotLookup.Absent,
            pendingTwo.pubkey to WotLookup.Pending,
        )

        val ranked = rankTrendingUsers(users, states::getValue)

        assertEquals(
            listOf(scoredOne, scoredTwo, pendingOne, pendingTwo, absentOne, absentTwo),
            ranked,
        )
        assertEquals(listOf(scoredOne, scoredTwo, pendingOne), ranked.take(3))
    }

    @Test
    fun `all absent trending authors retain their original order and limit`() {
        val users = (1..12).map { UserEntity(pubkey = "absent-$it") }

        val ranked = rankTrendingUsers(users) { WotLookup.Absent }

        assertEquals(users, ranked)
        assertEquals(users.take(8), ranked.take(8))
    }

    @Test
    fun `unresolved trending count yields to MES cache while real zero does not`() {
        val cached = UserEntity(
            pubkey = "author",
            displayName = "Cached profile",
            followerCount = 340L,
            followerCountUpdatedAt = 10L,
        )

        val unresolved = mergeTrendingUserWithLatest(
            candidate = UserEntity(pubkey = "author", followerCount = null),
            latest = cached,
        )
        val knownZero = mergeTrendingUserWithLatest(
            candidate = UserEntity(pubkey = "author", followerCount = 0L),
            latest = cached,
        )

        assertEquals(340L, unresolved.followerCount)
        assertEquals(10L, unresolved.followerCountUpdatedAt)
        assertEquals(0L, knownZero.followerCount)
    }

    @Test
    fun `trending follower label distinguishes unknown from indexed and WoT estimates`() {
        assertNull(trendingFollowerCountLabel(indexedCount = null, lookup = WotLookup.Pending))
        assertEquals("0", trendingFollowerCountLabel(indexedCount = 0L, lookup = WotLookup.Absent))
        assertEquals(
            "~5k",
            trendingFollowerCountLabel(
                indexedCount = null,
                lookup = scored("author", verifiedFollowers = 5_000L),
            ),
        )
    }

    private fun muteList(
        pubkeys: Set<String> = emptySet(),
        hashtags: Set<String> = emptySet(),
        words: Set<String> = emptySet(),
        eventIds: Set<String> = emptySet(),
    ): MuteList = MuteList(
        pubkeys = pubkeys,
        hashtags = hashtags,
        words = words,
        eventIds = eventIds,
    )

    private fun row(
        id: String,
        pubkey: String,
        kind: Int = 1,
        content: String = "",
        tags: String = "[]",
    ): FeedRow = FeedRow(
        id = id,
        pubkey = pubkey,
        kind = kind,
        content = content,
        createdAt = 1L,
        tags = tags,
        relayUrl = "wss://relay.example",
        replyToId = null,
        rootId = null,
        hasContentWarning = false,
        contentWarningReason = null,
        zapTotalSats = 0L,
        authorName = null,
        authorDisplayName = null,
        authorPicture = null,
        authorNip05 = null,
        reactionCount = 0,
        replyCount = 0,
        repostCount = 0,
        zapCount = 0,
    )

    private fun scored(
        subject: String,
        verifiedFollowers: Long? = null,
    ): WotLookup.Scored = WotLookup.Scored(
        WotAssertionEntity(
            subjectPubkey = subject,
            providerPubkey = "provider",
            rank = 80,
            verifiedFollowers = verifiedFollowers,
        ),
    )
}
