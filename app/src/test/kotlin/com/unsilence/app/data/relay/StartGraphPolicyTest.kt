package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.WotAssertionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class StartGraphPolicyTest {
    private val own = "f".repeat(64)
    private val creatorHigh = "a".repeat(64)
    private val creatorMid = "b".repeat(64)
    private val creatorUnknown = "c".repeat(64)
    private val member1 = "1".repeat(64)
    private val member2 = "2".repeat(64)
    private val member3 = "3".repeat(64)

    @Test
    fun `pack parser enforces member bounds and title fallback`() {
        assertNull(parseFollowPack(packEvent(memberCount = 2)))
        assertNull(parseFollowPack(packEvent(memberCount = 501)))

        val parsed = parseFollowPack(packEvent(memberCount = 3, title = null))
        assertEquals("pack", parsed?.title)
        assertEquals(3, parsed?.memberPubkeys?.size)
    }

    @Test
    fun `addressable pack projection keeps latest with deterministic tie break`() {
        val events = listOf(
            packEvent(id = "ffff", createdAt = 10, title = "old"),
            packEvent(id = "ffff", createdAt = 20, title = "new-high-id"),
            packEvent(id = "0000", createdAt = 20, title = "new-low-id"),
        )

        val projected = latestFollowPacks(events)

        assertEquals(1, projected.size)
        assertEquals("new-low-id", projected.single().title)
    }

    @Test
    fun `ranking uses creator rank before grapevine overlap and unknown sinks`() {
        val packs = listOf(
            followPack("high", creatorHigh, listOf(member3)),
            followPack("mid-low-overlap", creatorMid, listOf(member1, member3)),
            followPack("mid-high-overlap", creatorMid, listOf(member1, member2, member3)),
            followPack("unknown", creatorUnknown, listOf(member1, member2, member3)),
        )
        val assertions = mapOf(
            creatorHigh to assertion(creatorHigh, 10),
            creatorMid to assertion(creatorMid, 9),
            member1 to assertion(member1, 2),
            member2 to assertion(member2, 1),
        )

        assertEquals(
            listOf("high", "mid-high-overlap", "mid-low-overlap", "unknown"),
            rankFollowPacks(packs, assertions).map { it.pack.title },
        )
    }

    @Test
    fun `notable people are rank ordered and exclude self and existing follows`() {
        val assertions = listOf(
            assertion(own, 100),
            assertion(member1, 30),
            assertion(member2, 20),
            assertion(member3, 10),
        )

        assertEquals(
            listOf(member1, member3),
            topNotablePubkeys(assertions, own, setOf(member2), limit = 2),
        )
    }

    @Test
    fun `batch contact tags preserve existing and deduplicate all selections`() {
        val tags = buildFollowContactTags(
            existingFollows = listOf(member1, member2),
            selectedFollows = listOf(member2, member3, member3),
        )

        assertEquals(
            listOf(member1, member2, member3),
            tags.map { it[1] },
        )
        assertTrue(tags.all { it.first() == "p" })
    }

    @Test
    fun `fresh onboarding and empty Following gates distinguish unresolved imports`() {
        assertTrue(shouldAutoOpenStartGraph(true, onboardingCompleted = false, follows = null))
        assertTrue(shouldAutoOpenStartGraph(true, onboardingCompleted = false, follows = emptySet()))
        assertTrue(shouldAutoOpenStartGraph(false, onboardingCompleted = false, follows = emptySet()))
        assertFalse(shouldAutoOpenStartGraph(false, onboardingCompleted = false, follows = null))
        assertFalse(shouldAutoOpenStartGraph(true, onboardingCompleted = false, follows = setOf(member1)))
        assertFalse(shouldAutoOpenStartGraph(false, onboardingCompleted = true, follows = emptySet()))

        assertFalse(shouldShowEmptyFollowingEntry(followsResolved = false, follows = emptySet()))
        assertFalse(shouldShowEmptyFollowingEntry(followsResolved = true, follows = null))
        assertTrue(shouldShowEmptyFollowingEntry(followsResolved = true, follows = emptySet()))
        assertFalse(shouldShowEmptyFollowingEntry(followsResolved = true, follows = setOf(member1)))
    }

    @Test
    fun `landing follows the resulting graph rather than button label`() {
        assertEquals(GraphLanding.FOLLOWING, graphLanding(setOf(member1)))
        assertEquals(GraphLanding.GLOBAL_TRUSTED, graphLanding(emptySet()))
    }

    private fun followPack(title: String, author: String, members: List<String>) = FollowPack(
        eventId = title,
        authorPubkey = author,
        createdAt = 1,
        dTag = title,
        title = title,
        imageUrl = null,
        memberPubkeys = members,
    )

    private fun assertion(subject: String, rank: Int) = WotAssertionEntity(
        subjectPubkey = subject,
        providerPubkey = own,
        rank = rank,
    )

    private fun packEvent(
        id: String = "event",
        createdAt: Long = 1,
        memberCount: Int = 3,
        title: String? = "title",
    ): NostrEvent {
        val tags = buildList {
            add(listOf("d", "pack"))
            title?.let { add(listOf("title", it)) }
            repeat(memberCount) { index ->
                add(listOf("p", index.toString(16).padStart(64, '0')))
            }
        }
        return NostrEvent(
            id = id,
            pubkey = creatorHigh,
            kind = FOLLOW_PACK_KIND,
            content = "",
            createdAt = createdAt,
            tags = tags,

            sig = "sig",
            relayUrl = "wss://nos.lol",
            replyToId = null,
            rootId = null,
            hasContentWarning = false,
            contentWarningReason = null,
            firstSeenAt = 0,
            relaysSeen = ConcurrentHashMap.newKeySet(),
        )
    }
}
