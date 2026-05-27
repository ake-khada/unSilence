package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the sweep candidate filter logic. Mirrors the
 * production filter in RelayPool's periodic sweep exactly.
 */
class RelayPoolSweepTest {

    private data class FakeConn(
        val url: String,
        val purposes: Set<ConnectionPurpose>,
        val lastActivity: Long,
    )

    private fun sweepCandidates(
        connections: List<FakeConn>,
        activeSubs: Set<String>,
        cap: Int,
    ): List<String> {
        if (connections.size <= cap) return emptyList()
        val sorted = connections
            .filter { conn ->
                ConnectionPurpose.PERSISTENT !in conn.purposes &&
                ConnectionPurpose.FEED_SUB !in conn.purposes &&
                conn.url !in activeSubs
            }
            .sortedBy { it.lastActivity }
        val toClose = (connections.size - cap).coerceAtMost(sorted.size)
        return sorted.take(toClose).map { it.url }
    }

    @Test
    fun `PERSISTENT relays are never candidates`() {
        val conns = (1..45).map { i ->
            FakeConn(
                url = "wss://r$i.example",
                purposes = if (i <= 5) setOf(ConnectionPurpose.PERSISTENT) else emptySet(),
                lastActivity = i.toLong(),
            )
        }
        val candidates = sweepCandidates(conns, activeSubs = emptySet(), cap = 40)
        assertEquals(5, candidates.size)
        for (i in 1..5) {
            assertTrue("r$i should be exempt", "wss://r$i.example" !in candidates)
        }
    }

    @Test
    fun `FEED_SUB relays are never candidates`() {
        val conns = (1..45).map { i ->
            FakeConn(
                url = "wss://r$i.example",
                purposes = if (i == 1) setOf(ConnectionPurpose.FEED_SUB) else emptySet(),
                lastActivity = i.toLong(),
            )
        }
        val candidates = sweepCandidates(conns, activeSubs = emptySet(), cap = 40)
        assertTrue("FEED_SUB should be exempt", "wss://r1.example" !in candidates)
    }

    @Test
    fun `URLs with active subs are never candidates`() {
        val conns = (1..45).map { i ->
            FakeConn(url = "wss://r$i.example", purposes = emptySet(), lastActivity = i.toLong())
        }
        val activeSubs = setOf("wss://r1.example", "wss://r2.example", "wss://r3.example")
        val candidates = sweepCandidates(conns, activeSubs = activeSubs, cap = 40)
        for (url in activeSubs) {
            assertTrue("active sub $url should be exempt", url !in candidates)
        }
    }

    @Test
    fun `evicts oldest by activity among non-exempt`() {
        val conns = listOf(
            FakeConn("wss://newest.example", emptySet(), lastActivity = 100),
            FakeConn("wss://middle.example", emptySet(), lastActivity = 50),
            FakeConn("wss://oldest.example", emptySet(), lastActivity = 10),
            FakeConn("wss://persistent.example", setOf(ConnectionPurpose.PERSISTENT), lastActivity = 5),
        )
        val candidates = sweepCandidates(conns, activeSubs = emptySet(), cap = 2)
        assertEquals(2, candidates.size)
        assertEquals("wss://oldest.example", candidates[0])
        assertEquals("wss://middle.example", candidates[1])
    }

    @Test
    fun `returns empty when under cap`() {
        val conns = (1..10).map { i ->
            FakeConn("wss://r$i.example", emptySet(), lastActivity = i.toLong())
        }
        val candidates = sweepCandidates(conns, activeSubs = emptySet(), cap = 40)
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `closes fewer than target when all candidates exempt`() {
        val conns = (1..45).map { i ->
            FakeConn(
                url = "wss://r$i.example",
                purposes = setOf(ConnectionPurpose.PERSISTENT),
                lastActivity = i.toLong(),
            )
        }
        val candidates = sweepCandidates(conns, activeSubs = emptySet(), cap = 40)
        assertTrue("no candidates when everything is PERSISTENT", candidates.isEmpty())
    }
}
