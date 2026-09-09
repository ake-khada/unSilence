package com.unsilence.app.ui.search

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotAssertionEntity
import com.unsilence.app.data.memory.WotLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchViewModelTest {

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
