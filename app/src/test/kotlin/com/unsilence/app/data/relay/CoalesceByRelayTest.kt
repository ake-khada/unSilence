package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for coalesceByRelay helper.
 */
class CoalesceByRelayTest {

    @Test
    fun `collapses duplicate REQs to the same relay`() {
        // 10 ids all listing damus → 1 batch (≤chunk)
        val items = (1..10).associate { "id$it" to listOf("wss://relay.damus.io/") }
        val result = coalesceByRelay(items, maxRelays = 25, chunkSize = 20)

        assertEquals(1, result.size)
        assertEquals("wss://relay.damus.io/", result[0].first)
        assertEquals(10, result[0].second.size)
    }

    @Test
    fun `greedy selection covers all items within relay budget`() {
        val items = mapOf(
            "id1" to listOf("wss://a/", "wss://b/", "wss://c/"),
            "id2" to listOf("wss://a/", "wss://b/"),
            "id3" to listOf("wss://a/", "wss://d/"),
            "id4" to listOf("wss://e/"),
        )
        val result = coalesceByRelay(items, maxRelays = 2, chunkSize = 50)

        // a covers id1-id3; e then covers the only remaining item.
        val relays = result.map { it.first }
        assertTrue("wss://a/" in relays)
        assertTrue("wss://e/" in relays)
        assertEquals(2, relays.size)
    }

    @Test
    fun `chunks a relay's ids by chunkSize`() {
        // 45 ids all at same relay, chunk=20 → 3 batches (20+20+5)
        val items = (1..45).associate { "id$it" to listOf("wss://r/") }
        val result = coalesceByRelay(items, maxRelays = 25, chunkSize = 20)

        assertEquals(3, result.size)
        assertEquals(20, result[0].second.size)
        assertEquals(20, result[1].second.size)
        assertEquals(5, result[2].second.size)
        assertTrue(result.all { it.first == "wss://r/" })
    }

    @Test
    fun `high-coverage relay survives the cap`() {
        // own-read relay covers all 10 items; 10 other relays each cover 1
        val ownRead = "wss://own-read/"
        val items = (1..10).associate { i ->
            "id$i" to listOf(ownRead, "wss://author-$i/")
        }
        val result = coalesceByRelay(items, maxRelays = 3, chunkSize = 50)

        // own-read (10 items) always first
        assertEquals(ownRead, result[0].first)
        assertEquals(10, result[0].second.size)
        // Only 3 relays total (own-read + 2 from the single-item tail)
        assertEquals(3, result.map { it.first }.distinct().size)
    }

    @Test
    fun `empty input returns empty`() {
        val result = coalesceByRelay(emptyMap(), maxRelays = 25, chunkSize = 20)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `deduplicates relay URLs within a single item`() {
        // Same relay listed twice for one item → counted once
        val items = mapOf("id1" to listOf("wss://r/", "wss://r/"))
        val result = coalesceByRelay(items, maxRelays = 25, chunkSize = 20)

        assertEquals(1, result.size)
        assertEquals(1, result[0].second.size)
    }

    @Test
    fun `soft overflow preserves at least one relay per item`() {
        val items = mapOf(
            "id1" to listOf("wss://one/"),
            "id2" to listOf("wss://two/"),
            "id3" to listOf("wss://three/"),
        )

        val result = coalesceByRelay(items, maxRelays = 2, chunkSize = 50)
        val covered = result.flatMap { it.second }.toSet()
        assertEquals(items.keys, covered)
        assertEquals(3, result.map { it.first }.distinct().size)
    }
}
