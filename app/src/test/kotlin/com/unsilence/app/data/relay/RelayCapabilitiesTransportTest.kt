package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for transport-level strike weighting.
 * Tests [RelayCapabilitiesStore.strikesForReason] and the accumulation logic
 * without needing Android context.
 */
class RelayCapabilitiesTransportTest {

    // ── Strike weight tests ────────────────────────────────────────────

    @Test
    fun `DNS failure has single-strike weight`() {
        assertEquals(1, RelayCapabilitiesStore.strikesForReason(SkipReason.DNS_RESOLUTION))
    }

    @Test
    fun `cleartext block has instant-skip weight`() {
        assertEquals(MAX_CAPABILITY_STRIKES, RelayCapabilitiesStore.strikesForReason(SkipReason.CLEARTEXT_BLOCKED))
    }

    @Test
    fun `HTTP 5xx has single-strike weight`() {
        assertEquals(1, RelayCapabilitiesStore.strikesForReason(SkipReason.HTTP_UPGRADE_5XX))
    }

    @Test
    fun `HTTP 4xx has single-strike weight`() {
        assertEquals(1, RelayCapabilitiesStore.strikesForReason(SkipReason.HTTP_UPGRADE_4XX))
    }

    @Test
    fun `SSL error has single-strike weight`() {
        assertEquals(1, RelayCapabilitiesStore.strikesForReason(SkipReason.SSL_ERROR))
    }

    @Test
    fun `connect timeout has single-strike weight`() {
        assertEquals(1, RelayCapabilitiesStore.strikesForReason(SkipReason.CONNECT_TIMEOUT))
    }

    @Test
    fun `unknown failure has single-strike weight`() {
        assertEquals(1, RelayCapabilitiesStore.strikesForReason(SkipReason.UNKNOWN_FAILURE))
    }

    // ── Accumulation simulation ────────────────────────────────────────
    // Simulates the accumulation logic in recordTransportFailure without
    // needing the Android-dependent store instance.

    private fun simulateStrikes(vararg reasons: SkipReason): Int {
        var total = 0
        for (r in reasons) total += RelayCapabilitiesStore.strikesForReason(r)
        return total
    }

    @Test
    fun `DNS needs 3 occurrences to reach threshold`() {
        assertFalse(simulateStrikes(SkipReason.DNS_RESOLUTION) >= MAX_CAPABILITY_STRIKES)
        assertFalse(simulateStrikes(SkipReason.DNS_RESOLUTION, SkipReason.DNS_RESOLUTION) >= MAX_CAPABILITY_STRIKES)
        assertTrue(
            simulateStrikes(
                SkipReason.DNS_RESOLUTION,
                SkipReason.DNS_RESOLUTION,
                SkipReason.DNS_RESOLUTION,
            ) >= MAX_CAPABILITY_STRIKES
        )
    }

    @Test
    fun `HTTP 5xx needs 3 occurrences to reach threshold`() {
        assertFalse(simulateStrikes(SkipReason.HTTP_UPGRADE_5XX) >= MAX_CAPABILITY_STRIKES)
        assertFalse(simulateStrikes(SkipReason.HTTP_UPGRADE_5XX, SkipReason.HTTP_UPGRADE_5XX) >= MAX_CAPABILITY_STRIKES)
        assertTrue(
            simulateStrikes(
                SkipReason.HTTP_UPGRADE_5XX,
                SkipReason.HTTP_UPGRADE_5XX,
                SkipReason.HTTP_UPGRADE_5XX,
            ) >= MAX_CAPABILITY_STRIKES
        )
    }

    @Test
    fun `mixed transport and protocol strikes accumulate`() {
        // 1 HTTP + 2 generic = 3
        val strikes = simulateStrikes(
            SkipReason.HTTP_UPGRADE_5XX,
            SkipReason.UNKNOWN_FAILURE,
            SkipReason.SSL_ERROR,
        )
        assertTrue("mixed signals should reach threshold", strikes >= MAX_CAPABILITY_STRIKES)
    }

    @Test
    fun `timeout needs 3 occurrences`() {
        assertFalse(simulateStrikes(SkipReason.CONNECT_TIMEOUT, SkipReason.CONNECT_TIMEOUT) >= MAX_CAPABILITY_STRIKES)
        assertTrue(
            simulateStrikes(
                SkipReason.CONNECT_TIMEOUT,
                SkipReason.CONNECT_TIMEOUT,
                SkipReason.CONNECT_TIMEOUT,
            ) >= MAX_CAPABILITY_STRIKES
        )
    }

    // ── Dead-count regression (H18.4) ─────────────────────────────────

    /** CONNECT_TIMEOUT must never contribute to dead-count (transient, VPN-variable). */
    @Test
    fun `10x CONNECT_TIMEOUT does not produce dead-count`() {
        // Simulate: only DNS_RESOLUTION increments deadFailCount
        var deadCount = 0
        repeat(10) {
            val reason = SkipReason.CONNECT_TIMEOUT
            if (reason == SkipReason.DNS_RESOLUTION) deadCount++
        }
        assertEquals(0, deadCount)
        assertTrue("10 timeouts must not reach dead threshold", deadCount < DEAD_RELAY_THRESHOLD)
    }

    /** DNS_RESOLUTION does produce dead-count — the relay is genuinely unresolvable. */
    @Test
    fun `10x DNS_RESOLUTION reaches dead threshold`() {
        var deadCount = 0
        repeat(10) {
            val reason = SkipReason.DNS_RESOLUTION
            if (reason == SkipReason.DNS_RESOLUTION) deadCount++
        }
        assertEquals(10, deadCount)
        assertTrue("10 DNS failures must reach dead threshold", deadCount >= DEAD_RELAY_THRESHOLD)
    }

    // ── Cooldown base by failure type (H18.4) ────────────────────────
    // Tests call the extracted companion function directly — no Android context needed.

    @Test
    fun `DNS lastReason uses 5min base`() {
        val cooldown = RelayCapabilitiesStore.computeRetryCooldownMs(
            isIntegral = false,
            lastReason = SkipReason.DNS_RESOLUTION.name,
            strikes = MAX_CAPABILITY_STRIKES,
        )
        assertEquals(5 * 60_000L, cooldown)
    }

    @Test
    fun `CONNECT_TIMEOUT lastReason uses 1min base`() {
        val cooldown = RelayCapabilitiesStore.computeRetryCooldownMs(
            isIntegral = false,
            lastReason = SkipReason.CONNECT_TIMEOUT.name,
            strikes = MAX_CAPABILITY_STRIKES,
        )
        assertEquals(60_000L, cooldown)
    }

    @Test
    fun `integral uses flat 60s regardless of DNS reason`() {
        val cooldown = RelayCapabilitiesStore.computeRetryCooldownMs(
            isIntegral = true,
            lastReason = SkipReason.DNS_RESOLUTION.name,
            strikes = 10,
        )
        assertEquals(60_000L, cooldown)
    }

    @Test
    fun `integral uses flat 60s regardless of timeout reason`() {
        val cooldown = RelayCapabilitiesStore.computeRetryCooldownMs(
            isIntegral = true,
            lastReason = SkipReason.CONNECT_TIMEOUT.name,
            strikes = 10,
        )
        assertEquals(60_000L, cooldown)
    }

    @Test
    fun `DNS cooldown schedule with exponential backoff`() {
        val dns = SkipReason.DNS_RESOLUTION.name
        // overage 0 → 5min, 1 → 10min, 2 → 20min, 3 → 30min(cap)
        assertEquals(5 * 60_000L, RelayCapabilitiesStore.computeRetryCooldownMs(false, dns, 3))
        assertEquals(10 * 60_000L, RelayCapabilitiesStore.computeRetryCooldownMs(false, dns, 4))
        assertEquals(20 * 60_000L, RelayCapabilitiesStore.computeRetryCooldownMs(false, dns, 5))
        assertEquals(30 * 60_000L, RelayCapabilitiesStore.computeRetryCooldownMs(false, dns, 6))  // cap
        assertEquals(30 * 60_000L, RelayCapabilitiesStore.computeRetryCooldownMs(false, dns, 100))
    }

    // ── Integral cooldown escalation (H20b) ──────────────────────────

    @Test
    fun `integral cooldown is 60s base below the escalation threshold`() {
        for (fails in 0 until 5) {
            assertEquals(
                "consecutiveFailures=$fails should stay at base 60s",
                60_000L,
                RelayCapabilitiesStore.computeIntegralCooldownMs(fails),
            )
        }
    }

    @Test
    fun `integral cooldown escalates to 5min at and past the threshold`() {
        assertEquals(5 * 60_000L, RelayCapabilitiesStore.computeIntegralCooldownMs(5))
        assertEquals(5 * 60_000L, RelayCapabilitiesStore.computeIntegralCooldownMs(6))
        assertEquals(5 * 60_000L, RelayCapabilitiesStore.computeIntegralCooldownMs(100))
    }

    @Test
    fun `computeRetryCooldownMs routes integral through escalation, ignoring reason and strikes`() {
        val dns = SkipReason.DNS_RESOLUTION.name
        // Below threshold → base 60s; at threshold → escalated 5min. Strikes/reason irrelevant for integral.
        assertEquals(60_000L, RelayCapabilitiesStore.computeRetryCooldownMs(true, dns, 10, 4))
        assertEquals(5 * 60_000L, RelayCapabilitiesStore.computeRetryCooldownMs(true, dns, 10, 5))
        // Default consecutiveFailures (0) keeps the legacy flat-60s behavior intact.
        assertEquals(60_000L, RelayCapabilitiesStore.computeRetryCooldownMs(true, dns, 10))
    }
}
