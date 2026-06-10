package com.unsilence.app.data.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for DNS-degraded detection and strike gating.
 * Models the heuristic from [RelayCapabilitiesStore] without Android context.
 *
 * The hard rule: a relay is only struck for failures that are ITS fault.
 * Failures during a network-wide outage teach us nothing about the relay.
 */
class DnsDegradedDetectionTest {

    // ── DNS-degraded heuristic model ──────────────────────────────────

    /**
     * Models the ring-buffer DNS-degraded detection logic.
     * [recentDnsFailures] maps relay URL → failure timestamp.
     */
    private class DegradedDetector {
        private val recentDnsFailures = mutableMapOf<String, Long>()
        var degraded = false
            private set
        var onsetAt = 0L
            private set
        val failedDuringDegradation = mutableSetOf<String>()

        /** Mirrors RelayCapabilitiesStore.dnsDegradedActive() — the TTL decision itself
         *  delegates to the REAL production pure function, not a duplicate. Lazily clears
         *  an expired latch so a fresh burst re-arms with a new onset. (H20a) */
        fun active(now: Long): Boolean {
            if (!degraded) return false
            if (RelayCapabilitiesStore.isDegradedActive(true, onsetAt, now)) return true
            degraded = false
            recentDnsFailures.clear()
            failedDuringDegradation.clear()
            return false
        }

        fun recordDnsFailure(url: String, now: Long) {
            val wasActive = active(now)  // clears an expired latch first
            recentDnsFailures[url] = now
            val cutoff = now - NETWORK_DOWN_WINDOW_MS
            recentDnsFailures.entries.removeIf { it.value < cutoff }
            if (!wasActive && recentDnsFailures.size >= NETWORK_DOWN_DNS_THRESHOLD) {
                degraded = true
                onsetAt = now
            }
            if (degraded) failedDuringDegradation.add(url)
        }

        fun heal(): Set<String> {
            if (!degraded) return emptySet()
            degraded = false
            recentDnsFailures.clear()
            val healed = failedDuringDegradation.toSet()
            failedDuringDegradation.clear()
            return healed
        }
    }

    // ── Threshold tests ───────────────────────────────────────────────

    @Test
    fun `3 distinct DNS failures within window does not trigger degraded`() {
        val d = DegradedDetector()
        val now = 1000L
        d.recordDnsFailure("wss://relay-a.com", now)
        d.recordDnsFailure("wss://relay-b.com", now + 100)
        d.recordDnsFailure("wss://relay-c.com", now + 200)
        assertFalse("3 < threshold, should not be degraded", d.degraded)
    }

    @Test
    fun `4 distinct DNS failures within window triggers degraded`() {
        val d = DegradedDetector()
        val now = 1000L
        d.recordDnsFailure("wss://relay-a.com", now)
        d.recordDnsFailure("wss://relay-b.com", now + 100)
        d.recordDnsFailure("wss://relay-c.com", now + 200)
        d.recordDnsFailure("wss://relay-d.com", now + 300)
        assertTrue("4 >= threshold, should be degraded", d.degraded)
    }

    @Test
    fun `same relay failing 4 times does not trigger - must be distinct`() {
        val d = DegradedDetector()
        val now = 1000L
        repeat(4) { d.recordDnsFailure("wss://relay-a.com", now + it * 100L) }
        assertFalse("same relay 4x, not 4 distinct relays", d.degraded)
    }

    @Test
    fun `failures outside window do not count`() {
        val d = DegradedDetector()
        d.recordDnsFailure("wss://relay-a.com", 1000L)
        d.recordDnsFailure("wss://relay-b.com", 1100L)
        // 5 seconds later — outside the 3s window
        val later = 1000L + NETWORK_DOWN_WINDOW_MS + 1000L
        d.recordDnsFailure("wss://relay-c.com", later)
        d.recordDnsFailure("wss://relay-d.com", later + 100)
        assertFalse("a and b expired, only c and d in window", d.degraded)
    }

    // ── Strike gating model ───────────────────────────────────────────

    /** Returns true if the strike should be applied (not gated). */
    private fun shouldStrike(
        reason: SkipReason,
        isOffline: Boolean,
        isDnsDegraded: Boolean,
    ): Boolean {
        if (reason == SkipReason.DNS_RESOLUTION && (isOffline || isDnsDegraded)) {
            return false  // network's fault, not relay's
        }
        return true
    }

    @Test
    fun `DNS failure while degraded is NOT struck`() {
        assertFalse(shouldStrike(SkipReason.DNS_RESOLUTION, isOffline = false, isDnsDegraded = true))
    }

    @Test
    fun `DNS failure while offline is NOT struck`() {
        assertFalse(shouldStrike(SkipReason.DNS_RESOLUTION, isOffline = true, isDnsDegraded = false))
    }

    @Test
    fun `DNS failure on clean network IS struck`() {
        assertTrue(shouldStrike(SkipReason.DNS_RESOLUTION, isOffline = false, isDnsDegraded = false))
    }

    @Test
    fun `non-DNS failure while degraded IS still struck`() {
        // SSL, HTTP, timeout failures are relay-specific — always strike
        assertTrue(shouldStrike(SkipReason.SSL_ERROR, isOffline = false, isDnsDegraded = true))
        assertTrue(shouldStrike(SkipReason.HTTP_UPGRADE_5XX, isOffline = true, isDnsDegraded = false))
        assertTrue(shouldStrike(SkipReason.CONNECT_TIMEOUT, isOffline = false, isDnsDegraded = true))
    }

    // ── Heal on recovery ──────────────────────────────────────────────

    @Test
    fun `heal returns relays that failed DURING degraded period`() {
        val d = DegradedDetector()
        val now = 1000L
        // a, b, c fail before degraded triggers — they get struck normally
        d.recordDnsFailure("wss://relay-a.com", now)
        d.recordDnsFailure("wss://relay-b.com", now + 100)
        d.recordDnsFailure("wss://relay-c.com", now + 200)
        // relay-d is the 4th → triggers degraded. It and everything after are gated.
        d.recordDnsFailure("wss://relay-d.com", now + 300)
        assertTrue(d.degraded)
        d.recordDnsFailure("wss://relay-e.com", now + 400)

        val healed = d.heal()
        assertFalse("degraded should be cleared", d.degraded)
        assertTrue("relay-d should be healed (triggered degraded)", "wss://relay-d.com" in healed)
        assertTrue("relay-e should be healed (during degraded)", "wss://relay-e.com" in healed)
        // a, b, c failed before degraded was detected — not in healed set
        assertFalse("relay-a was pre-degraded", "wss://relay-a.com" in healed)
        assertTrue("only 2 relays failed during degraded", healed.size == 2)
    }

    @Test
    fun `heal when not degraded returns empty`() {
        val d = DegradedDetector()
        d.recordDnsFailure("wss://relay-a.com", 1000L)
        assertFalse(d.degraded)
        val healed = d.heal()
        assertTrue(healed.isEmpty())
    }

    // ── TTL latch breaker (H20a) — production pure function tested directly ───

    @Test
    fun `isDegradedActive is false when not armed`() {
        assertFalse(RelayCapabilitiesStore.isDegradedActive(armed = false, onsetAtMs = 0L, nowMs = 50_000L))
    }

    @Test
    fun `isDegradedActive is true within TTL`() {
        val onset = 1000L
        assertTrue(RelayCapabilitiesStore.isDegradedActive(true, onset, onset))
        assertTrue(RelayCapabilitiesStore.isDegradedActive(true, onset, onset + DNS_DEGRADED_TTL_MS - 1))
    }

    @Test
    fun `isDegradedActive expires at and past TTL`() {
        val onset = 1000L
        assertFalse(RelayCapabilitiesStore.isDegradedActive(true, onset, onset + DNS_DEGRADED_TTL_MS))
        assertFalse(RelayCapabilitiesStore.isDegradedActive(true, onset, onset + DNS_DEGRADED_TTL_MS + 60_000L))
    }

    @Test
    fun `armed latch reports inactive once TTL elapses`() {
        val d = DegradedDetector()
        val t = 1000L
        repeat(4) { d.recordDnsFailure("wss://r$it.com", t + it) }
        assertTrue("4 distinct failures arm the latch", d.degraded)
        val onset = d.onsetAt
        assertTrue("active just before TTL", d.active(onset + DNS_DEGRADED_TTL_MS - 1))
        assertFalse("inactive at TTL — gate can't block its own exit", d.active(onset + DNS_DEGRADED_TTL_MS + 1))
    }

    @Test
    fun `fresh DNS burst re-arms after TTL expiry with a new onset`() {
        val d = DegradedDetector()
        val t = 1000L
        repeat(4) { d.recordDnsFailure("wss://a$it.com", t + it) }
        assertTrue(d.degraded)
        val firstOnset = d.onsetAt

        // Long past the TTL — the heuristic must stay useful: a fresh burst re-arms,
        // it just can't ride the stale latch.
        val t2 = t + DNS_DEGRADED_TTL_MS + 60_000L
        d.recordDnsFailure("wss://b0.com", t2)
        assertFalse("single fresh failure after expiry does not re-arm", d.degraded)
        d.recordDnsFailure("wss://b1.com", t2 + 1)
        d.recordDnsFailure("wss://b2.com", t2 + 2)
        d.recordDnsFailure("wss://b3.com", t2 + 3)
        assertTrue("fresh distinct burst re-arms", d.degraded)
        assertTrue("re-arm carries a new onset", d.onsetAt > firstOnset)
    }

    @Test
    fun `heal clears the latch before TTL (probe-success path)`() {
        val d = DegradedDetector()
        val t = 1000L
        repeat(4) { d.recordDnsFailure("wss://r$it.com", t + it) }
        assertTrue(d.degraded)
        // A probe connects well within the TTL → heal clears the latch immediately,
        // long before the 90s backstop would.
        val healed = d.heal()
        assertFalse("latch cleared by heal, not by TTL", d.degraded)
        assertTrue("relays struck during degradation are healed", healed.isNotEmpty())
        assertFalse("post-heal latch reads inactive", d.active(t + 5))
    }
}
