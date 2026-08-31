package com.unsilence.app.ui.shared

import com.unsilence.app.data.memory.FeedRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplySpamProjectionTest {

    @Test
    fun `seed phrase shape accepts comma word lists without knowing the words`() {
        assertTrue(
            isSeedPhraseShape(
                "abandon, ability, able, about, above, absent, absorb, abstract, absurd, abuse, access, accident",
            ),
        )
        assertTrue(
            isSeedPhraseShape(
                "pragmatic, ecosystem, encrypted, science, wheel, projects, fragmenting, messaging",
            ),
        )
    }

    @Test
    fun `seed phrase shape permits one person mention evasion`() {
        assertTrue(
            isSeedPhraseShape(
                "@alice, one, two, three, four, five, six, seven, eight",
            ),
        )
        assertTrue(
            isSeedPhraseShape(
                SEED_A + ", nostr:npub1" + "q".repeat(58),
            ),
        )
        assertTrue(
            isSeedPhraseShape(
                SEED_A + ", @nprofile1" + "q".repeat(90),
            ),
        )
        assertTrue(
            isSeedPhraseShape(
                "one, two, three, four, five, six, seven, eight #[0]",
            ),
        )
    }

    @Test
    fun `seed reply with one person mention is marked and collapsed`() {
        val taggedSeed = "@alice, one, two, three, four, five, six, seven, eight"
        val marked = markLikelyCoordinatedSpam(
            listOf(depthRow("tagged-seed", "unknown", taggedSeed)),
        )

        assertTrue(marked.single().spamClusterId?.startsWith("seed-shape:") == true)
        val item = replyListItems(marked, emptySet()).single()
        assertTrue(item is ReplyListItem.SpamCluster)
        assertEquals(1, (item as ReplyListItem.SpamCluster).replyCount)
    }

    @Test
    fun `seed phrase shape rejects a second mention or a non-person nostr entity`() {
        assertFalse(
            isSeedPhraseShape(
                "@alice, one, two, three, four, five, six, seven, eight, @bob",
            ),
        )
        assertFalse(
            isSeedPhraseShape(
                "one, two, three, four, five, six, seven, eight, nostr:note1qqqqqqqqqq",
            ),
        )
    }

    @Test
    fun `seed phrase shape rejects prose punctuation uppercase digits urls and short lists`() {
        assertFalse(isSeedPhraseShape("one, two, three, four, five, six, seven"))
        assertFalse(isSeedPhraseShape("one, two, three, four, five, six, seven, Eight"))
        assertFalse(isSeedPhraseShape("one, two, three, four, five, six, seven, eight2"))
        assertFalse(isSeedPhraseShape("one, two, three, four, five, six, seven, eight."))
        assertFalse(isSeedPhraseShape("one, two, three, four, five, six, seven, https://eight.test"))
        assertFalse(isSeedPhraseShape("This is human prose, with commas, but not a bare word list"))
    }

    @Test
    fun `different rotating seed lists collapse into one parent-stable cluster`() {
        val rows = listOf(
            depthRow("seed-a", "alice", SEED_A, createdAt = 100),
            depthRow("normal", "friend", "This is a normal reply to the thread.", createdAt = 150),
            depthRow("seed-b", "bob", SEED_B, createdAt = 200),
            depthRow("seed-c", "carol", SEED_C, createdAt = 300),
        )

        val marked = markLikelyCoordinatedSpam(rows)
        val clusterIds = marked.filter { it.row.id.startsWith("seed-") }
            .mapNotNull { it.spamClusterId }
            .toSet()

        assertEquals(1, clusterIds.size)
        assertTrue(clusterIds.single().startsWith("seed-shape:"))
        assertNull(marked.single { it.row.id == "normal" }.spamClusterId)

        val withoutFirst = markLikelyCoordinatedSpam(rows.drop(1).filterNot { it.row.id == "seed-a" })
        assertEquals(
            clusterIds.single(),
            withoutFirst.single { it.row.id == "seed-b" }.spamClusterId,
        )
    }

    @Test
    fun `trusted seed-shaped reply remains visible`() {
        val marked = markLikelyCoordinatedSpam(
            rows = listOf(depthRow("trusted", "friend", SEED_A)),
            isTrustedAuthor = { it == "friend" },
        )

        assertNull(marked.single().spamClusterId)
    }

    @Test
    fun `focused seed-shaped reply remains visible`() {
        val marked = markLikelyCoordinatedSpam(
            rows = listOf(depthRow("focused", "unknown", SEED_A)),
            protectedEventIds = setOf("focused"),
        )

        assertNull(marked.single().spamClusterId)
    }

    @Test
    fun `three authors posting shuffled long near-duplicates form one cluster`() {
        val rows = listOf(
            depthRow("bait-a", "alice", bait("alpha amber autumn arrive"), createdAt = 100),
            depthRow("bait-b", "bob", bait("binary breeze bronze balance", reversed = true), createdAt = 200),
            depthRow("bait-c", "carol", bait("cactus canvas circle copper"), createdAt = 300),
        )

        val marked = markLikelyCoordinatedSpam(rows)

        assertTrue(marked.all { it.spamClusterId?.startsWith("near-duplicate:") == true })
        assertEquals(1, marked.mapNotNull { it.spamClusterId }.toSet().size)
    }

    @Test
    fun `one OP mention does not evade long near-duplicate clustering`() {
        val opMention = "nostr:nprofile1" + "q".repeat(90)
        val rows = listOf(
            depthRow("bait-a", "alice", bait("alpha amber autumn arrive") + " $opMention", createdAt = 100),
            depthRow("bait-b", "bob", bait("binary breeze bronze balance", reversed = true) + " $opMention", createdAt = 200),
            depthRow("bait-c", "carol", bait("cactus canvas circle copper") + " $opMention", createdAt = 300),
        )

        val marked = markLikelyCoordinatedSpam(rows)

        assertTrue(marked.all { it.spamClusterId?.startsWith("near-duplicate:") == true })
        assertEquals(1, marked.mapNotNull { it.spamClusterId }.toSet().size)
    }

    @Test
    fun `multiple mentions and non-person references remain ineligible for clustering`() {
        val person = "nostr:npub1" + "q".repeat(58)
        val note = "nostr:note1" + "q".repeat(58)
        val rowsWithTwoMentions = listOf(
            depthRow("bait-a", "alice", bait("alpha amber autumn arrive") + " $person $person"),
            depthRow("bait-b", "bob", bait("binary breeze bronze balance") + " $person"),
            depthRow("bait-c", "carol", bait("cactus canvas circle copper") + " $person"),
        )
        val rowsWithNoteReference = listOf(
            depthRow("bait-d", "alice", bait("alpha amber autumn arrive") + " $note"),
            depthRow("bait-e", "bob", bait("binary breeze bronze balance") + " $note"),
            depthRow("bait-f", "carol", bait("cactus canvas circle copper") + " $note"),
        )

        assertTrue(markLikelyCoordinatedSpam(rowsWithTwoMentions).all { it.spamClusterId == null })
        assertTrue(markLikelyCoordinatedSpam(rowsWithNoteReference).all { it.spamClusterId == null })
    }

    @Test
    fun `near-duplicate cluster needs three distinct authors`() {
        val twoAuthors = listOf(
            depthRow("bait-a", "alice", bait("alpha amber autumn arrive"), createdAt = 100),
            depthRow("bait-b", "bob", bait("binary breeze bronze balance"), createdAt = 200),
            depthRow("bait-c", "alice", bait("cactus canvas circle copper"), createdAt = 300),
        )

        assertTrue(markLikelyCoordinatedSpam(twoAuthors).all { it.spamClusterId == null })
    }

    @Test
    fun `near-duplicate cluster does not cross parents or the time window`() {
        val differentParents = listOf(
            depthRow("bait-a", "alice", bait("alpha amber autumn arrive"), replyToId = "root-a"),
            depthRow("bait-b", "bob", bait("binary breeze bronze balance"), replyToId = "root-a"),
            depthRow("bait-c", "carol", bait("cactus canvas circle copper"), replyToId = "root-b"),
        )
        val spreadAcrossTime = listOf(
            depthRow("bait-d", "alice", bait("alpha amber autumn arrive"), createdAt = 0),
            depthRow("bait-e", "bob", bait("binary breeze bronze balance"), createdAt = 1),
            depthRow(
                "bait-f",
                "carol",
                bait("cactus canvas circle copper"),
                createdAt = 13L * 60L * 60L,
            ),
        )

        assertTrue(markLikelyCoordinatedSpam(differentParents).all { it.spamClusterId == null })
        assertTrue(markLikelyCoordinatedSpam(spreadAcrossTime).all { it.spamClusterId == null })
    }

    @Test
    fun `trusted author cannot complete a near-duplicate cluster`() {
        val rows = listOf(
            depthRow("bait-a", "alice", bait("alpha amber autumn arrive"), createdAt = 100),
            depthRow("bait-b", "bob", bait("binary breeze bronze balance"), createdAt = 200),
            depthRow("bait-c", "friend", bait("cactus canvas circle copper"), createdAt = 300),
        )

        val marked = markLikelyCoordinatedSpam(rows, isTrustedAuthor = { it == "friend" })

        assertTrue(marked.all { it.spamClusterId == null })
    }

    @Test
    fun `near-duplicate cluster id survives one member leaving the projection`() {
        val rows = listOf(
            depthRow("bait-a", "alice", bait("alpha amber autumn arrive"), createdAt = 100),
            depthRow("bait-b", "bob", bait("binary breeze bronze balance"), createdAt = 200),
            depthRow("bait-c", "carol", bait("cactus canvas circle copper"), createdAt = 300),
            depthRow("bait-d", "dave", bait("dragon drift dune dynamic"), createdAt = 400),
        )
        val firstId = markLikelyCoordinatedSpam(rows).first().spamClusterId
        val remainingId = markLikelyCoordinatedSpam(rows.drop(1)).first().spamClusterId

        assertEquals(firstId, remainingId)
    }

    @Test
    fun `spam-shaped parent with a live child is never collapsed`() {
        val rows = listOf(
            depthRow("parent", "alice", SEED_A, depth = 1, createdAt = 100),
            depthRow(
                "child",
                "friend",
                "A visible child must keep its parent in the thread.",
                depth = 2,
                replyToId = "parent",
                createdAt = 110,
            ),
        )

        val marked = markLikelyCoordinatedSpam(rows)

        assertNull(marked.single { it.row.id == "parent" }.spamClusterId)
        assertNull(marked.single { it.row.id == "child" }.spamClusterId)
    }

    @Test
    fun `collapsed list keeps one reversible summary and restores every source row`() {
        val marked = markLikelyCoordinatedSpam(
            listOf(
                depthRow("seed-a", "alice", SEED_A, createdAt = 100),
                depthRow("normal", "friend", "A normal reply remains visible.", createdAt = 150),
                depthRow("seed-b", "bob", SEED_B, createdAt = 200),
                depthRow("seed-c", "carol", SEED_C, createdAt = 300),
            ),
        )
        val clusterId = marked.first().spamClusterId!!

        val collapsed = replyListItems(marked, emptySet())
        assertEquals(2, collapsed.size)
        val summary = collapsed.filterIsInstance<ReplyListItem.SpamCluster>().single()
        assertEquals(3, summary.replyCount)
        assertFalse(summary.revealed)
        assertEquals(listOf("normal"), collapsed.filterIsInstance<ReplyListItem.Reply>().map { it.key })

        val revealed = replyListItems(marked, setOf(clusterId))
        assertEquals(5, revealed.size)
        assertTrue(revealed.filterIsInstance<ReplyListItem.SpamCluster>().single().revealed)
        assertEquals(
            listOf("seed-a", "normal", "seed-b", "seed-c"),
            revealed.filterIsInstance<ReplyListItem.Reply>().map { it.key },
        )
    }

    private fun depthRow(
        id: String,
        pubkey: String,
        content: String,
        depth: Int = 1,
        replyToId: String? = "root",
        createdAt: Long = 100L,
    ) = ModeratedReplyRow(
        row = FeedRow(
            id = id,
            pubkey = pubkey,
            kind = 1,
            content = content,
            createdAt = createdAt,
            tags = "[]",
            relayUrl = "wss://relay.test",
            replyToId = replyToId,
            rootId = "root",
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
        ),
        depth = depth,
    )

    private fun bait(extra: String, reversed: Boolean = false): String {
        val common = COMMON_BAIT.split(' ')
        val ordered = if (reversed) common.reversed() else common
        return (ordered + extra.split(' ')).joinToString(" ") + "."
    }

    private companion object {
        const val SEED_A =
            "abandon, ability, able, about, above, absent, absorb, abstract, absurd, abuse, access, accident"
        const val SEED_B =
            "bachelor, bacon, badge, balance, balcony, ball, bamboo, banana, banner, bar, barely, bargain"
        const val SEED_C =
            "cabin, cable, cactus, cage, cake, call, calm, camera, camp, canal, cancel, candy"
        const val COMMON_BAIT =
            "nostr ecosystem pragmatic interoperable encrypted science wheel projects fragmenting " +
                "reinventing messaging sovereign protocol community network identity relays clients " +
                "privacy freedom resilient portable"
    }
}
