package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotAssertionEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.model.ContentWarnings
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.MediaManifest
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.model.ThreadRefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WotSurfacePolicyTest {

    @Test
    fun `feed exception renders absent but not pending or healthy scored`() {
        assertNull(wotFeedException(WotLookup.Pending))
        assertEquals(FeedWotSignal.Absent, wotFeedException(WotLookup.Absent))
        assertNull(wotFeedException(scored(rank = 87)))
    }

    @Test
    fun `feed numbers mode renders rank and absent dash but not pending`() {
        assertNull(wotFeedSignal(WotLookup.Pending, FeedWotDisplayMode.NUMBERS))
        assertEquals(FeedWotSignal.Absent, wotFeedSignal(WotLookup.Absent, FeedWotDisplayMode.NUMBERS))
        assertEquals(FeedWotSignal.Rank(rank = 87), wotFeedSignal(scored(rank = 87), FeedWotDisplayMode.NUMBERS))
    }

    @Test
    fun `feed exceptions mode preserves exception-only logic`() {
        assertNull(wotFeedSignal(scored(rank = 87), FeedWotDisplayMode.EXCEPTIONS_ONLY))
        assertEquals(FeedWotSignal.Absent, wotFeedSignal(WotLookup.Absent, FeedWotDisplayMode.EXCEPTIONS_ONLY))
        assertEquals(
            FeedWotSignal.Distrusted(muters = 1, reporters = 0),
            wotFeedSignal(scored(rank = 42, muters = 1), FeedWotDisplayMode.EXCEPTIONS_ONLY),
        )
    }

    @Test
    fun `feed off mode renders no signal`() {
        assertNull(wotFeedSignal(WotLookup.Absent, FeedWotDisplayMode.OFF))
        assertNull(wotFeedSignal(scored(rank = 99), FeedWotDisplayMode.OFF))
    }

    @Test
    fun `feed numbers mode tints positive distrust through rank signal`() {
        assertEquals(
            FeedWotSignal.Rank(rank = 42, distrusted = true),
            wotFeedSignal(scored(rank = 42, muters = 1), FeedWotDisplayMode.NUMBERS),
        )
    }

    @Test
    fun `feed exception reserves red distrust for positive distrust evidence`() {
        assertEquals(
            FeedWotSignal.Distrusted(muters = 1, reporters = 0),
            wotFeedException(scored(rank = 42, muters = 1)),
        )
    }

    @Test
    fun `mention sort uses rank descending with nulls last`() {
        val a = user("a")
        val b = user("b")
        val c = user("c")
        val lookups = mapOf(
            a.pubkey to scored(rank = 40),
            b.pubkey to WotLookup.Pending,
            c.pubkey to scored(rank = 99),
        )

        assertEquals(
            listOf(c.pubkey, a.pubkey, b.pubkey),
            sortMentionsByWotRank(listOf(a, b, c)) { lookups[it] }.map { it.pubkey },
        )
    }

    @Test
    fun `search ordering uses rank then verified followers`() {
        val lowPopular = user("a")
        val highSparse = user("b")
        val highPopular = user("c")
        val absent = user("d")
        val lookups = mapOf(
            lowPopular.pubkey to scored(rank = 50, followers = 10_000),
            highSparse.pubkey to scored(rank = 90, followers = 4),
            highPopular.pubkey to scored(rank = 90, followers = 700),
            absent.pubkey to WotLookup.Absent,
        )

        assertEquals(
            listOf(highPopular.pubkey, highSparse.pubkey, lowPopular.pubkey, absent.pubkey),
            sortPeopleForSearch(listOf(absent, lowPopular, highSparse, highPopular)) { lookups[it] }.map { it.pubkey },
        )
    }

    @Test
    fun `verified follower ordering keeps nulls last`() {
        val a = user("a")
        val b = user("b")
        val c = user("c")
        val lookups = mapOf(
            a.pubkey to scored(rank = 80, followers = 1),
            b.pubkey to scored(rank = 60, followers = 20),
            c.pubkey to WotLookup.Pending,
        )

        assertEquals(
            listOf(b.pubkey, a.pubkey, c.pubkey),
            sortPeopleByWotFollowers(listOf(c, a, b)) { lookups[it] }.map { it.pubkey },
        )
    }

    @Test
    fun `feed subjects include cached parent and quoted authors`() {
        val rowAuthor = hex("a")
        val parentAuthor = hex("b")
        val quoteHintAuthor = hex("c")
        val resolvedQuoteAuthor = hex("d")
        val addressAuthor = hex("e")
        val row = feedRow(
            id = "reply",
            pubkey = rowAuthor,
            replyToId = "parent",
        )
        val models = mapOf(
            "reply" to model(
                id = "reply",
                pubkey = rowAuthor,
                segments = listOf(
                    Segment.QuoteEvent(eventId = "quote", hints = emptyList(), author = quoteHintAuthor),
                    Segment.QuoteAddress(kind = 30023, author = addressAuthor, dTag = "article", hints = emptyList()),
                ),
            ),
            "parent" to model(id = "parent", pubkey = parentAuthor),
            "quote" to model(id = "quote", pubkey = resolvedQuoteAuthor),
        )

        assertEquals(
            setOf(rowAuthor, parentAuthor, quoteHintAuthor, resolvedQuoteAuthor, addressAuthor),
            wotSubjectsForFeedRows(listOf(row)) { models[it] },
        )
    }

    private fun user(seed: String): UserEntity =
        UserEntity(pubkey = hex(seed), displayName = "User $seed")

    private fun feedRow(
        id: String,
        pubkey: String,
        replyToId: String? = null,
    ): FeedRow =
        FeedRow(
            id = id,
            pubkey = pubkey,
            kind = 1,
            content = "",
            createdAt = 1,
            tags = "[]",
            relayUrl = "wss://relay.example",
            replyToId = replyToId,
            rootId = null,
            hasContentWarning = false,
            contentWarningReason = null,
            zapTotalSats = 0,
            authorName = null,
            authorDisplayName = null,
            authorPicture = null,
            authorNip05 = null,
            reactionCount = 0,
            replyCount = 0,
            repostCount = 0,
            zapCount = 0,
        )

    private fun model(
        id: String,
        pubkey: String,
        segments: List<Segment> = emptyList(),
    ): EventModel =
        EventModel(
            id = id,
            pubkey = pubkey,
            sourcePubkey = pubkey,
            kind = 1,
            effectiveKind = 1,
            articleContent = null,
            createdAt = 1,
            sourceCreatedAt = 1,
            relayUrl = "wss://relay.example",
            engagementId = id,
            navigateId = id,
            segments = segments,
            media = MediaManifest(images = emptyList(), videos = emptyList(), ogCandidate = null, youtubes = emptyList()),
            thread = ThreadRefs(replyToId = null, rootId = null),
            repost = null,
            article = null,
            warnings = ContentWarnings(hasContentWarning = false, reason = null),
        )

    private fun hex(seed: String): String = seed.repeat(64).take(64)

    private fun scored(
        rank: Int,
        followers: Long? = null,
        muters: Long? = null,
    ): WotLookup.Scored {
        val subject = rank.toString(16).padStart(64, '0').takeLast(64)
        return WotLookup.Scored(
            WotAssertionEntity(
                subjectPubkey = subject,
                providerPubkey = "f".repeat(64),
                rank = rank,
                verifiedFollowers = followers,
                verifiedMuters = muters,
            ),
        )
    }
}
