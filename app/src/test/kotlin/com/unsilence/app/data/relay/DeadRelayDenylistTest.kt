package com.unsilence.app.data.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract-documentation tests for the cross-session dead-relay denylist.
 *
 * These tests model the intended behavior of [RelayCapabilitiesStore] using local
 * helper functions, because the real store requires Android Context (DataStore).
 * They document the policy contract — not pin production code paths. Production
 * code is pinned by [RelayCapabilitiesTransportTest] (companion functions) and
 * by device-level logcat validation.
 *
 * The critical invariant: dead-count MUST NOT increment during network-down.
 * A 2-minute DNS block would otherwise mark every relay permanently dead.
 *
 * Hook models use [RelayCapabilitiesStore.strikesForReason] (production) for
 * strike weights and [RelayCapabilitiesStore.computeRetryCooldownMs] where applicable.
 * The dead-count increment and clear predicates are local models that mirror the
 * store's CHM-operating methods.
 */
class DeadRelayDenylistTest {

    /** Models the dead-relay increment + shouldSkip logic. */
    private data class RelayCaps(
        val deadFailCount: Int = 0,
        val lastProbeAt: Long = 0L,
        val restricted: Boolean = false,
        val strikes: Int = 0,
        val lastReason: String = "",
        val lastStrikeAt: Long = 0L,
    )

    private fun incrementDeadCount(
        caps: RelayCaps,
        reason: SkipReason,
        isNetworkDown: Boolean,
        now: Long,
    ): RelayCaps {
        // Network-down gate: DNS failures during outage don't increment dead count
        if (reason == SkipReason.DNS_RESOLUTION && isNetworkDown) return caps

        // Dead-count: DNS only. CONNECT_TIMEOUT is transient (H18.4).
        val newDeadCount = if (reason == SkipReason.DNS_RESOLUTION) {
            caps.deadFailCount + 1
        } else {
            caps.deadFailCount
        }

        val weight = RelayCapabilitiesStore.strikesForReason(reason)
        return caps.copy(
            deadFailCount = newDeadCount,
            strikes = caps.strikes + weight,
            lastStrikeAt = now,
            lastReason = reason.name,
            lastProbeAt = if (newDeadCount >= DEAD_RELAY_THRESHOLD) now else caps.lastProbeAt,
        )
    }

    private fun shouldSkipDead(caps: RelayCaps, now: Long): Boolean {
        if (caps.deadFailCount < DEAD_RELAY_THRESHOLD) return false
        return (now - caps.lastProbeAt) < DEAD_RELAY_REPROBE_MS
    }

    private fun resetOnConnect(caps: RelayCaps): RelayCaps =
        caps.copy(deadFailCount = 0, strikes = 0, lastReason = "")

    /** Models clearDnsDeadOnNetworkChange — clears DNS-reason relays only. */
    private fun clearDnsDeadOnNetworkChange(caps: RelayCaps): RelayCaps {
        if (caps.lastReason == SkipReason.DNS_RESOLUTION.name && (caps.strikes > 0 || caps.deadFailCount > 0)) {
            return caps.copy(strikes = 0, lastReason = "", deadFailCount = 0)
        }
        return caps
    }

    /** Models clearCooldownForRelay — clears everything (manual add). */
    private fun clearCooldownForRelay(caps: RelayCaps): RelayCaps {
        if (caps.restricted) return caps  // policy rejections are permanent
        if (caps.strikes == 0 && caps.deadFailCount == 0) return caps
        return caps.copy(strikes = 0, lastReason = "", deadFailCount = 0)
    }

    // ── Threshold tests ───────────────────────────────────────────────

    @Test
    fun `9 failures does not reach dead threshold`() {
        var caps = RelayCaps()
        repeat(9) {
            caps = incrementDeadCount(caps, SkipReason.DNS_RESOLUTION, isNetworkDown = false, now = 1000L + it)
        }
        assertFalse(shouldSkipDead(caps, 2000L))
    }

    @Test
    fun `10 failures reaches dead threshold`() {
        var caps = RelayCaps()
        repeat(10) {
            caps = incrementDeadCount(caps, SkipReason.DNS_RESOLUTION, isNetworkDown = false, now = 1000L + it)
        }
        assertTrue(shouldSkipDead(caps, 2000L))
    }

    // ── THE CRITICAL TEST: network-down gate wraps dead-count ─────────

    @Test
    fun `network-down DNS failures do NOT increment dead count`() {
        var caps = RelayCaps()
        // 100 DNS failures during network-down — must not kill the relay
        repeat(100) {
            caps = incrementDeadCount(caps, SkipReason.DNS_RESOLUTION, isNetworkDown = true, now = 1000L + it)
        }
        assertTrue("dead count should stay at 0 during network-down", caps.deadFailCount == 0)
        assertFalse("relay must not be dead", shouldSkipDead(caps, 2000L))
    }

    @Test
    fun `mixed network-down and healthy failures count correctly`() {
        var caps = RelayCaps()
        // 5 real failures (network healthy)
        repeat(5) {
            caps = incrementDeadCount(caps, SkipReason.DNS_RESOLUTION, isNetworkDown = false, now = 1000L + it)
        }
        // 50 during outage — should not count
        repeat(50) {
            caps = incrementDeadCount(caps, SkipReason.DNS_RESOLUTION, isNetworkDown = true, now = 2000L + it)
        }
        // 4 more real failures
        repeat(4) {
            caps = incrementDeadCount(caps, SkipReason.DNS_RESOLUTION, isNetworkDown = false, now = 3000L + it)
        }
        assertTrue("5 + 4 = 9, should not be dead yet", caps.deadFailCount == 9)
        assertFalse(shouldSkipDead(caps, 4000L))

        // One more real failure → threshold
        caps = incrementDeadCount(caps, SkipReason.DNS_RESOLUTION, isNetworkDown = false, now = 4000L)
        assertTrue("5 + 4 + 1 = 10, should be dead", caps.deadFailCount == 10)
        assertTrue(shouldSkipDead(caps, 4001L))
    }

    // ── Non-DNS failures ──────────────────────────────────────────────

    @Test
    fun `SSL errors do not increment dead count`() {
        var caps = RelayCaps()
        repeat(20) {
            caps = incrementDeadCount(caps, SkipReason.SSL_ERROR, isNetworkDown = false, now = 1000L + it)
        }
        assertTrue("SSL errors should not count toward dead", caps.deadFailCount == 0)
    }

    @Test
    fun `connect timeouts do NOT increment dead count (H18_4)`() {
        var caps = RelayCaps()
        repeat(10) {
            caps = incrementDeadCount(caps, SkipReason.CONNECT_TIMEOUT, isNetworkDown = false, now = 1000L + it)
        }
        assertTrue("timeouts must not count toward dead-count", caps.deadFailCount == 0)
        assertFalse("relay must not be dead from timeouts alone", shouldSkipDead(caps, 2000L))
    }

    // ── Reprobe ───────────────────────────────────────────────────────

    @Test
    fun `dead relay is reprobed after weekly window`() {
        val caps = RelayCaps(deadFailCount = 15, lastProbeAt = 1000L)
        assertTrue("should be skipped within reprobe window", shouldSkipDead(caps, 1000L + DEAD_RELAY_REPROBE_MS - 1))
        assertFalse("should be allowed for reprobe after window", shouldSkipDead(caps, 1000L + DEAD_RELAY_REPROBE_MS + 1))
    }

    // ── Reset on success ──────────────────────────────────────────────

    @Test
    fun `successful connection resets dead count`() {
        val caps = RelayCaps(deadFailCount = 15, lastProbeAt = 1000L)
        val reset = resetOnConnect(caps)
        assertTrue("dead count should be 0 after connect", reset.deadFailCount == 0)
        assertFalse(shouldSkipDead(reset, 2000L))
    }

    // ── Hook 1: manual relay add clears dead state (H18.4b) ──────────

    @Test
    fun `manual add clears dead relay — shouldSkip becomes false`() {
        // Strike + kill the relay
        var caps = RelayCaps()
        repeat(10) {
            caps = incrementDeadCount(caps, SkipReason.DNS_RESOLUTION, isNetworkDown = false, now = 1000L + it)
        }
        assertTrue("relay should be dead", shouldSkipDead(caps, 2000L))

        // User manually re-adds → clearCooldownForRelay
        caps = clearCooldownForRelay(caps)
        assertTrue("dead count should be 0 after manual add", caps.deadFailCount == 0)
        assertTrue("strikes should be 0 after manual add", caps.strikes == 0)
        assertFalse("shouldSkip must be false immediately after manual add", shouldSkipDead(caps, 2000L))
    }

    @Test
    fun `manual add does not clear restricted relay`() {
        val caps = RelayCaps(restricted = true, strikes = 5, deadFailCount = 15)
        val after = clearCooldownForRelay(caps)
        assertTrue("restricted relay must stay restricted", after.restricted)
        assertTrue("dead count must not clear on restricted relay", after.deadFailCount == 15)
    }

    // ── Hook 2: network change clears DNS-dead state (H18.4b) ────────

    @Test
    fun `network change clears DNS-dead relay — shouldSkip becomes false`() {
        // DNS-dead relay
        var caps = RelayCaps()
        repeat(10) {
            caps = incrementDeadCount(caps, SkipReason.DNS_RESOLUTION, isNetworkDown = false, now = 1000L + it)
        }
        assertTrue("relay should be dead", shouldSkipDead(caps, 2000L))

        // Network change (VPN toggle)
        caps = clearDnsDeadOnNetworkChange(caps)
        assertTrue("dead count should be 0 after network change", caps.deadFailCount == 0)
        assertTrue("strikes should be 0 after network change", caps.strikes == 0)
        assertFalse("shouldSkip must be false after network change", shouldSkipDead(caps, 2000L))
    }

    @Test
    fun `network change does NOT clear timeout-struck relay`() {
        // Relay struck by CONNECT_TIMEOUT (not DNS)
        var caps = RelayCaps()
        repeat(5) {
            caps = incrementDeadCount(caps, SkipReason.CONNECT_TIMEOUT, isNetworkDown = false, now = 1000L + it)
        }
        assertTrue("timeout strikes should accumulate", caps.strikes == 5)
        assertTrue("timeout should not produce dead-count", caps.deadFailCount == 0)

        // Network change — timeout-struck relay must survive
        val after = clearDnsDeadOnNetworkChange(caps)
        assertTrue("timeout strikes must survive network change", after.strikes == 5)
        assertTrue("lastReason must survive", after.lastReason == SkipReason.CONNECT_TIMEOUT.name)
    }

    @Test
    fun `network change does NOT clear SSL-struck relay`() {
        var caps = RelayCaps()
        repeat(5) {
            caps = incrementDeadCount(caps, SkipReason.SSL_ERROR, isNetworkDown = false, now = 1000L + it)
        }
        val after = clearDnsDeadOnNetworkChange(caps)
        assertTrue("SSL strikes must survive network change", after.strikes == 5)
    }
}
