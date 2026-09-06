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
    fun `seed phrase shape ignores one person reference`() {
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
    fun `multiple mentions and a relay reference do not evade seed detection`() {
        val content =
            "one @alice @bob nostr:npub1${"q".repeat(58)} wss://relay.example, " +
                "two, three, four, five, six, seven, eight"

        assertTrue(isSeedPhraseShape(content))
        val marked = markLikelyCoordinatedSpam(
            listOf(depthRow("multi-reference-seed", "unknown", content)),
        )

        assertTrue(marked.single().spamClusterId?.startsWith("seed-shape:") == true)
    }

    @Test
    fun `seed phrase shape rejects prose punctuation uppercase digits and short lists`() {
        assertFalse(isSeedPhraseShape("one, two, three, four, five, six, seven"))
        assertFalse(isSeedPhraseShape("one, two, three, four, five, six, seven, Eight"))
        assertFalse(isSeedPhraseShape("one, two, three, four, five, six, seven, eight2"))
        assertFalse(isSeedPhraseShape("one, two, three, four, five, six, seven, eight."))
        assertFalse(isSeedPhraseShape("This is human prose, with commas, but not a bare word list"))
    }

    @Test
    fun `seed phrase shape ignores a trailing relay URL`() {
        assertTrue(isSeedPhraseShape("$SEED_A, wss://relay.example"))
    }

    @Test
    fun `seed phrase shape ignores a URL in the middle of the list`() {
        assertTrue(
            isSeedPhraseShape(
                "abandon, ability, able, https://example.com/path, about, above, absent, " +
                    "absorb, abstract, absurd, abuse, access, accident",
            ),
        )
    }

    @Test
    fun `a comma list containing only URLs is not seed shaped`() {
        assertFalse(
            isSeedPhraseShape(
                "wss://one.example, ws://two.example, https://three.example, " +
                    "http://four.example, nostr:note1qqqqqqqqqq",
            ),
        )
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
    fun `different appended relay URLs do not evade long near-duplicate clustering`() {
        val rows = listOf(
            depthRow(
                "bait-a",
                "alice",
                bait("alpha amber autumn arrive") + " wss://relay-a.example",
                createdAt = 100,
            ),
            depthRow(
                "bait-b",
                "bob",
                bait("binary breeze bronze balance", reversed = true) +
                    " https://relay-b.example/path",
                createdAt = 200,
            ),
            depthRow(
                "bait-c",
                "carol",
                bait("cactus canvas circle copper") + " ws://relay-c.example",
                createdAt = 300,
            ),
        )

        val marked = markLikelyCoordinatedSpam(rows)

        assertTrue(marked.all { it.spamClusterId?.startsWith("near-duplicate:") == true })
        assertEquals(1, marked.mapNotNull { it.spamClusterId }.toSet().size)
    }

    @Test
    fun `unrelated long replies sharing a link do not cluster`() {
        val sharedLink = " https://example.com/source"
        val marked = markLikelyCoordinatedSpam(
            listOf(
                depthRow(
                    "legitimate-a",
                    "alice",
                    "astronomy observers calibrated telescopes beneath winter skies while distant " +
                        "galaxies revealed spectral patterns mapping hydrogen clouds across ancient " +
                        "clusters; careful measurements supported an updated catalogue for students " +
                        "planning independent research sessions next spring.$sharedLink",
                ),
                depthRow(
                    "legitimate-b",
                    "bob",
                    "gardeners prepared raised beds using composted leaves before planting tomatoes " +
                        "basil peppers carrots and climbing beans; steady irrigation shaded seedlings " +
                        "during summer afternoons while ladybirds protected healthy shoots from aphids " +
                        "without chemical treatments.$sharedLink",
                ),
                depthRow(
                    "legitimate-c",
                    "carol",
                    "musicians rehearsed unfamiliar chamber passages slowly until rhythmic entrances " +
                        "aligned; violin cello clarinet and piano balanced dynamics through attentive " +
                        "listening then recorded several complete takes for tomorrow's community radio " +
                        "broadcast and archive.$sharedLink",
                ),
            ),
        )

        assertTrue(marked.all { it.spamClusterId == null })
    }

    @Test
    fun `multiple mentions and relay references do not evade clustering`() {
        val firstPerson = "nostr:npub1" + "q".repeat(58)
        val secondPerson = "@npub1" + "p".repeat(58)
        val rows = listOf(
            depthRow(
                "bait-a",
                "alice",
                bait("alpha amber autumn arrive") + " $firstPerson $secondPerson wss://relay-a.example",
            ),
            depthRow(
                "bait-b",
                "bob",
                bait("binary breeze bronze balance") + " @op @moderator https://relay-b.example",
            ),
            depthRow(
                "bait-c",
                "carol",
                bait("cactus canvas circle copper") + " #[0] #[1] nostr:note1${"z".repeat(58)}",
            ),
        )

        val marked = markLikelyCoordinatedSpam(rows)

        assertTrue(marked.all { it.spamClusterId?.startsWith("near-duplicate:") == true })
        assertEquals(1, marked.mapNotNull { it.spamClusterId }.toSet().size)
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
