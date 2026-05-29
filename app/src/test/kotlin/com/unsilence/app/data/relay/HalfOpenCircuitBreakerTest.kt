package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for half-open circuit breaker logic in shouldSkip.
 * Models the decision without Android context.
 */
class HalfOpenCircuitBreakerTest {

    companion object {
        private const val MAX = MAX_CAPABILITY_STRIKES  // 3
        private const val TRANSPORT_RETRY_BASE_MS = 60_000L
        private const val TRANSPORT_RETRY_MAX_MS = 30 * 60_000L
        private const val INTEGRAL_RETRY_COOLDOWN_MS = 60_000L
    }

    /** Models shouldSkip decision logic. */
    private fun shouldSkip(
        restricted: Boolean,
        strikes: Int,
        lastStrikeAt: Long,
        now: Long,
        isIntegral: Boolean,
    ): Boolean {
        if (restricted) return true
        if (strikes < MAX) return false
        val cooldown = retryCooldown(strikes, isIntegral)
        return (now - lastStrikeAt) < cooldown
    }

    /** Models retryCooldownMs logic. */
    private fun retryCooldown(strikes: Int, isIntegral: Boolean): Long {
        if (isIntegral) return INTEGRAL_RETRY_COOLDOWN_MS
        val overage = (strikes - MAX).coerceIn(0, 6)
        return (TRANSPORT_RETRY_BASE_MS shl overage).coerceAtMost(TRANSPORT_RETRY_MAX_MS)
    }

    // ── shouldSkip decision tests ──────────────────────────────────────

    @Test
    fun `restricted always skips`() {
        assertTrue(shouldSkip(restricted = true, strikes = 0, lastStrikeAt = 0, now = 0, isIntegral = false))
    }

    @Test
    fun `under threshold never skips`() {
        assertFalse(shouldSkip(restricted = false, strikes = 2, lastStrikeAt = 0, now = 0, isIntegral = false))
    }

    @Test
    fun `struck and within cooldown skips`() {
        val now = 100_000L
        val lastStrike = now - 30_000L  // 30s ago, cooldown is 60s
        assertTrue(shouldSkip(restricted = false, strikes = 3, lastStrikeAt = lastStrike, now = now, isIntegral = false))
    }

    @Test
    fun `struck and past cooldown allows retry`() {
        val now = 200_000L
        val lastStrike = now - 70_000L  // 70s ago, cooldown is 60s
        assertFalse(shouldSkip(restricted = false, strikes = 3, lastStrikeAt = lastStrike, now = now, isIntegral = false))
    }

    @Test
    fun `integral uses flat 60s cooldown`() {
        val now = 100_000L
        // 50s ago — within integral cooldown (60s)
        assertTrue(shouldSkip(restricted = false, strikes = 10, lastStrikeAt = now - 50_000, now = now, isIntegral = true))
        // 70s ago — past integral cooldown
        assertFalse(shouldSkip(restricted = false, strikes = 10, lastStrikeAt = now - 70_000, now = now, isIntegral = true))
    }

    @Test
    fun `non-integral backs off exponentially capped at 30m`() {
        val now = 100_000L
        // strikes=3 (overage=0): cooldown=60s. 50s ago → skip. 70s ago → allow.
        assertTrue(shouldSkip(restricted = false, strikes = 3, lastStrikeAt = now - 50_000, now = now, isIntegral = false))
        assertFalse(shouldSkip(restricted = false, strikes = 3, lastStrikeAt = now - 70_000, now = now, isIntegral = false))

        // strikes=5 (overage=2): cooldown=240s (4m). 200s ago → skip. 250s ago → allow.
        assertTrue(shouldSkip(restricted = false, strikes = 5, lastStrikeAt = now - 200_000, now = now, isIntegral = false))
        assertFalse(shouldSkip(restricted = false, strikes = 5, lastStrikeAt = now - 250_000, now = now, isIntegral = false))
    }

    // ── retryCooldownMs schedule tests ─────────────────────────────────

    @Test
    fun `cooldown schedule for non-integral`() {
        // overage 0→1m, 1→2m, 2→4m, 3→8m, 4→16m, 5→30m(cap), 6→30m(cap)
        assertEquals(60_000L, retryCooldown(strikes = 3, isIntegral = false))   // overage 0
        assertEquals(120_000L, retryCooldown(strikes = 4, isIntegral = false))  // overage 1
        assertEquals(240_000L, retryCooldown(strikes = 5, isIntegral = false))  // overage 2
        assertEquals(480_000L, retryCooldown(strikes = 6, isIntegral = false))  // overage 3
        assertEquals(960_000L, retryCooldown(strikes = 7, isIntegral = false))  // overage 4
        assertEquals(1_800_000L, retryCooldown(strikes = 8, isIntegral = false)) // overage 5 → 30m cap
        assertEquals(1_800_000L, retryCooldown(strikes = 9, isIntegral = false)) // overage 6 → 30m cap
        assertEquals(1_800_000L, retryCooldown(strikes = 100, isIntegral = false)) // clamped to 6
    }

    @Test
    fun `cooldown is flat 60s for integral regardless of strikes`() {
        assertEquals(60_000L, retryCooldown(strikes = 3, isIntegral = true))
        assertEquals(60_000L, retryCooldown(strikes = 10, isIntegral = true))
        assertEquals(60_000L, retryCooldown(strikes = 100, isIntegral = true))
    }

    // ── Heal pass selection test ───────────────────────────────────────

    @Test
    fun `heal pass selects disconnected non-blocked past-cooldown integrals`() {
        val integral = setOf("wss://a.example/", "wss://b.example/", "wss://c.example/", "wss://d.example/")
        val connected = setOf("wss://a.example/")
        val blocked = setOf("wss://c.example/")
        val inCooldown = setOf("wss://d.example/")

        val toHeal = integral
            .filter { it !in connected }
            .filter { it !in blocked }
            .filter { it !in inCooldown }

        assertEquals(listOf("wss://b.example/"), toHeal)
    }
}
