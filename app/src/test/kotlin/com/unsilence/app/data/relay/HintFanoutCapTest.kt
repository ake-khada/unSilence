package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit tests for hint-relay fan-out cap logic and pooled-vs-ephemeral routing.
 */
class HintFanoutCapTest {

    @Test
    fun `caps each fetch to three locality relays`() {
        val hints = boundedSeenRelayHints(
            seenRelays = listOf(
                "wss://a.example",
                "wss://b.example",
                "wss://c.example",
                "wss://d.example",
            ),
        )
        assertEquals(
            listOf("wss://a.example", "wss://b.example", "wss://c.example"),
            hints,
        )
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
