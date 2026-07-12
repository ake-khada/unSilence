package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionsPolicyTest {
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
