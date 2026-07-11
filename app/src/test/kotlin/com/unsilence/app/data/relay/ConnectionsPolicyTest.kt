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
    fun `follower count takes maximum successful relay response`() {
        assertEquals(120L, maxFollowerCount(listOf(100L, null, 120L)))
        assertNull(maxFollowerCount(listOf(null, null)))
    }
}
