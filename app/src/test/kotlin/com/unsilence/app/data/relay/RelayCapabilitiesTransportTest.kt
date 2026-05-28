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
}
