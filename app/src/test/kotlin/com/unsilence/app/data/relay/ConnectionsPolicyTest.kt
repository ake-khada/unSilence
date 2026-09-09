package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionsPolicyTest {
    @Test
    fun `follower discovery retains count relay redundancy`() {
        assertTrue(FOLLOWER_INDEX_RELAY_URLS.containsAll(FOLLOWER_COUNT_RELAY_URLS))
        assertTrue("wss://purplepag.es" in FOLLOWER_INDEX_RELAY_URLS)
        assertTrue("wss://user.kindpag.es" in FOLLOWER_INDEX_RELAY_URLS)
    }

    @Test
    fun `latest contact list wins and removes stale follower after unfollow`() {
        val subject = "subject"
        val events = listOf(
            ContactListSnapshot("alice", "a1", 10L, setOf(subject)),
            ContactListSnapshot("alice", "a2", 20L, setOf("someone-else")),
            ContactListSnapshot("bob", "b1", 15L, setOf(subject)),
        )

        assertEquals(setOf("bob"), followersFromLatestContactLists(events, subject))
    }

    @Test
    fun `equal timestamp contact lists use deterministic event id tiebreak`() {
        val subject = "subject"
        val events = listOf(
            ContactListSnapshot("alice", "ffff", 20L, setOf(subject)),
            ContactListSnapshot("alice", "0000", 20L, emptySet()),
        )

        assertEquals(emptySet<String>(), followersFromLatestContactLists(events, subject))
    }

    @Test
    fun `follows viewer derives only from materialized contact list`() {
        assertTrue(followsViewer(setOf("me", "other"), "me"))
        assertFalse(followsViewer(setOf("other"), "me"))
        assertFalse(followsViewer(null, "me"))
        assertFalse(followsViewer(setOf("me"), null))
    }

    @Test
    fun `pagination cursor is exclusive and saturates at zero`() {
        assertEquals(99L, nextFollowersCursor(100L))
        assertEquals(0L, nextFollowersCursor(1L))
        assertNull(nextFollowersCursor(0L))
    }

    @Test
    fun `partial follower refresh only surfaces failure without usable rows`() {
        assertTrue(shouldSurfaceFollowerLoadFailure(fetchCompleted = false, hasFollowers = false))
        assertFalse(shouldSurfaceFollowerLoadFailure(fetchCompleted = false, hasFollowers = true))
        assertFalse(shouldSurfaceFollowerLoadFailure(fetchCompleted = true, hasFollowers = false))
    }

    @Test
    fun `no index and no known followers is unknown`() {
        assertEquals(
            FollowerCount.Unknown,
            reconciledFollowerCount(null, 0, trustedFollowerEstimate = null),
        )
    }

    @Test
    fun `known followers without an index are a lower bound`() {
        assertEquals(
            FollowerCount.AtLeast(3L),
            reconciledFollowerCount(null, 3, trustedFollowerEstimate = null),
        )
    }

    @Test
    fun `index consistent with known followers remains indexed`() {
        assertEquals(
            FollowerCount.Indexed(5L),
            reconciledFollowerCount(5L, 3, trustedFollowerEstimate = null),
        )
        assertEquals(
            FollowerCount.Indexed(3L),
            reconciledFollowerCount(3L, 3, trustedFollowerEstimate = null),
        )
    }

    @Test
    fun `known followers above the index expose the proven lower bound`() {
        assertEquals(
            FollowerCount.AtLeast(3L),
            reconciledFollowerCount(1L, 3, trustedFollowerEstimate = null),
        )
    }

    @Test
    fun `trusted provider count joins the index estimate without claiming local proof`() {
        assertEquals(
            FollowerCount.Indexed(25L),
            reconciledFollowerCount(null, 13, trustedFollowerEstimate = 25L),
        )
        assertEquals(
            FollowerCount.Indexed(25L),
            reconciledFollowerCount(13L, 13, trustedFollowerEstimate = 25L),
        )
    }

    @Test
    fun `strongest third party follower estimate wins`() {
        assertEquals(
            FollowerCount.Indexed(25L),
            reconciledFollowerCount(25L, 13, trustedFollowerEstimate = 20L),
        )
        assertEquals(
            FollowerCount.Indexed(30L),
            reconciledFollowerCount(25L, 13, trustedFollowerEstimate = 30L),
        )
        val largeEstimate = reconciledFollowerCount(
            indexedCount = 1_200L,
            knownFollowers = 13,
            trustedFollowerEstimate = 5_000L,
        )
        assertEquals(FollowerCount.Indexed(5_000L), largeEstimate)
        assertEquals("~5k", formatFollowerCount(largeEstimate))
    }

    @Test
    fun `local proof above every estimate remains a lower bound`() {
        assertEquals(
            FollowerCount.AtLeast(30L),
            reconciledFollowerCount(13L, 30, trustedFollowerEstimate = 25L),
        )
    }

    @Test
    fun `zero trusted estimate is indexed while negative estimates are ignored`() {
        assertEquals(
            FollowerCount.Indexed(0L),
            reconciledFollowerCount(null, 0, trustedFollowerEstimate = 0L),
        )
        assertEquals(
            FollowerCount.Unknown,
            reconciledFollowerCount(-1L, 0, trustedFollowerEstimate = -2L),
        )
    }

    @Test
    fun `lower bound formatting uses raw digits without approximate rounding`() {
        assertEquals("340+", formatFollowerCount(FollowerCount.AtLeast(340L)))
        assertFalse(formatFollowerCount(FollowerCount.AtLeast(340L)).contains("~"))
    }

    @Test
    fun `follower count takes maximum honest relay response`() {
        assertEquals(
            120L,
            maxFollowerCount(
                listOf(
                    Nip45CountResult(100L, limited = false),
                    Nip45CountResult(10_000L, limited = true),
                    null,
                    Nip45CountResult(120L, limited = false),
                ),
            ),
        )
        assertNull(maxFollowerCount(listOf(Nip45CountResult(500L, limited = true))))
        assertNull(maxFollowerCount(listOf(null, null)))
    }

    @Test
    fun `follower count formatting rounds before selecting the unit`() {
        val cases = mapOf(
            0L to "0",
            42L to "42",
            99L to "99",
            100L to "~100",
            286L to "~300",
            365L to "~350",
            975L to "~1k",
            990L to "~1k",
            25_870L to "~25.9k",
            199_068L to "~199.1k",
            10_000L to "~10k",
            999_949L to "~999.9k",
            1_234_567L to "~1.23M",
        )

        cases.forEach { (count, expected) ->
            assertEquals("count=$count", expected, formatFollowerCount(count))
        }
    }
}
