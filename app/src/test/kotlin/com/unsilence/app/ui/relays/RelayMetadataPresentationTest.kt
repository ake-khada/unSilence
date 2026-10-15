package com.unsilence.app.ui.relays

import com.unsilence.app.data.relay.RelayDirectory
import com.unsilence.app.data.relay.RelayDirectoryEntry
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.Zap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayMetadataPresentationTest {
    @Test
    fun `shared dot tiers separate missing evidence from slow and failed tests`() {
        assertEquals(Text3, latencyTier(null))
        for (ms in listOf(0, 149)) assertEquals(Mint, latencyTier(ms))
        for (ms in listOf(150, 500)) assertEquals(Zap, latencyTier(ms))
        for (ms in listOf(501, -1)) assertEquals(Like, latencyTier(ms))
        assertEquals("Latency unknown; connection status unknown", relayLatencyDescription(null, false))
        assertEquals("Monitor estimate: 42 milliseconds", relayLatencyDescription(42, false))
        assertEquals("Device test: 42 milliseconds", relayLatencyDescription(42, true))
        assertEquals("Device test: no response", relayLatencyDescription(-1, true))
    }

    @Test
    fun `discovery excludes uncertain countries only from the geographic facet`() {
        val known = RelayDirectoryEntry("wss://r.example", countryCode = "JP",
            countryObservedAt = 1000, countryMonitorPubkey = "monitor")
        assertTrue(matchesFacet(known, DiscoveryFacet.OUTSIDE_14, 1001))
        for (entry in listOf(known.copy(countryCode = "ZZ"), known.copy(countryCode = null),
            known.copy(countryConflicting = true), known.copy(countryMonitorPubkey = null))) {
            assertFalse(matchesFacet(entry, DiscoveryFacet.OUTSIDE_14, 1001))
            assertTrue(matchesFacet(entry, DiscoveryFacet.ALL, 1001))
        }
        val expired = 1001 + RelayDirectory.COUNTRY_MAX_AGE_SECONDS
        assertFalse(matchesFacet(known, DiscoveryFacet.OUTSIDE_14, expired))
        assertTrue(matchesFacet(known, DiscoveryFacet.ALL, expired))
    }
}
