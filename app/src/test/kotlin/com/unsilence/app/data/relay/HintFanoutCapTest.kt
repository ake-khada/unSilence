package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for hint-relay fan-out cap logic and pooled-vs-ephemeral routing.
 */
class HintFanoutCapTest {

    // ── Cap logic (mirrors CardHydrator/ProfilePipeline hint loop) ──────

    /**
     * Models the cap: entries filtered (exclude feedRelay), sorted by ref count
     * descending, take top N.
     */
    private fun capHints(
        hintBatches: Map<String, List<String>>,
        feedRelay: String?,
        cap: Int,
    ): List<Map.Entry<String, List<String>>> =
        hintBatches.entries
            .filter { it.key != feedRelay }
            .sortedByDescending { it.value.size }
            .take(cap)

    @Test
    fun `caps to top N by ref count`() {
        val hints = mapOf(
            "wss://a" to listOf("id1", "id2", "id3"),  // 3 refs
            "wss://b" to listOf("id4", "id5"),          // 2 refs
            "wss://c" to listOf("id6"),                 // 1 ref
            "wss://d" to listOf("id7"),                 // 1 ref
            "wss://e" to listOf("id8"),                 // 1 ref
        )
        val capped = capHints(hints, feedRelay = null, cap = 3)

        assertEquals(3, capped.size)
        assertEquals("wss://a", capped[0].key)  // most refs first
        assertEquals("wss://b", capped[1].key)
        // third is one of c/d/e (stable sort tiebreak)
    }

    @Test
    fun `excludes feed relay before capping`() {
        val hints = mapOf(
            "wss://feed" to listOf("id1", "id2", "id3", "id4", "id5"),  // 5 refs but it's the feed relay
            "wss://a" to listOf("id6", "id7"),
            "wss://b" to listOf("id8"),
        )
        val capped = capHints(hints, feedRelay = "wss://feed", cap = 10)

        assertEquals(2, capped.size)
        assertFalse(capped.any { it.key == "wss://feed" })
    }

    @Test
    fun `returns all when under cap`() {
        val hints = mapOf(
            "wss://a" to listOf("id1"),
            "wss://b" to listOf("id2"),
        )
        val capped = capHints(hints, feedRelay = null, cap = 12)

        assertEquals(2, capped.size)
    }

    @Test
    fun `empty map returns empty`() {
        val capped = capHints(emptyMap(), feedRelay = null, cap = 12)
        assertTrue(capped.isEmpty())
    }

    // ── Pooled-vs-ephemeral routing decision ────────────────────────────

    private enum class RouteDecision { POOLED, EPHEMERAL }

    private fun routeDecision(urlInPool: Boolean): RouteDecision =
        if (urlInPool) RouteDecision.POOLED else RouteDecision.EPHEMERAL

    @Test
    fun `routes pooled url to reuse`() {
        assertEquals(RouteDecision.POOLED, routeDecision(urlInPool = true))
    }

    @Test
    fun `routes non-pooled url to ephemeral`() {
        assertEquals(RouteDecision.EPHEMERAL, routeDecision(urlInPool = false))
    }
}
