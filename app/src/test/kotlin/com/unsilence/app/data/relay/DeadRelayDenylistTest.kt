package com.unsilence.app.data.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the cross-session dead-relay denylist.
 * Models the logic from [RelayCapabilitiesStore] without Android context.
 *
 * The critical invariant: dead-count MUST NOT increment during network-down.
 * A 2-minute state DNS block would otherwise mark every relay permanently dead.
 */
class DeadRelayDenylistTest {

    /** Models the dead-relay increment + shouldSkip logic. */
    private data class RelayCaps(
        val deadFailCount: Int = 0,
        val lastProbeAt: Long = 0L,
        val restricted: Boolean = false,
    )

    private fun incrementDeadCount(
        caps: RelayCaps,
        reason: SkipReason,
        isNetworkDown: Boolean,
        now: Long,
    ): RelayCaps {
        // Network-down gate: DNS failures during outage don't increment dead count
        if (reason == SkipReason.DNS_RESOLUTION && isNetworkDown) return caps

        val countable = reason == SkipReason.DNS_RESOLUTION || reason == SkipReason.CONNECT_TIMEOUT
        if (!countable) return caps

        val newCount = caps.deadFailCount + 1
        return caps.copy(
            deadFailCount = newCount,
            lastProbeAt = if (newCount >= DEAD_RELAY_THRESHOLD) now else caps.lastProbeAt,
        )
    }

    private fun shouldSkipDead(caps: RelayCaps, now: Long): Boolean {
        if (caps.deadFailCount < DEAD_RELAY_THRESHOLD) return false
        return (now - caps.lastProbeAt) < DEAD_RELAY_REPROBE_MS
    }

    private fun resetOnConnect(caps: RelayCaps): RelayCaps = caps.copy(deadFailCount = 0)

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
    fun `connect timeouts DO increment dead count`() {
        var caps = RelayCaps()
        repeat(10) {
            caps = incrementDeadCount(caps, SkipReason.CONNECT_TIMEOUT, isNetworkDown = false, now = 1000L + it)
        }
        assertTrue("timeouts should count", caps.deadFailCount == 10)
        assertTrue(shouldSkipDead(caps, 2000L))
    }

    // ── Reprobe ───────────────────────────────────────────────────────

    @Test
    fun `dead relay is reprobed after weekly window`() {
        var caps = RelayCaps(deadFailCount = 15, lastProbeAt = 1000L)
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
}
