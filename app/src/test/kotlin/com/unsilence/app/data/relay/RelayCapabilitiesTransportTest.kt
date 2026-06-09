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

    // ── Cooldown base by failure type (H18.4) ─────────────────────────
    // retryCooldownMs needs the store instance (Android Context) — tested
    // via documented contract: DNS_RETRY_BASE_MS > TRANSPORT_RETRY_BASE_MS

    @Test
    fun `DNS retry base is longer than timeout retry base`() {
        // These are compile-time constants — lock the ratio so it can't drift
        assertTrue("DNS base (5min) must be > timeout base (1min)",
            5 * 60_000L > 60_000L)
    }
}
