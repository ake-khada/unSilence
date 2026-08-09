package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCapabilityRoutingPolicyTest {

    private val structuralSearchRejection =
        requireNotNull(classifyClosedRejection("error: search filter is required"))

    @Test
    fun `repeated structural rejections widen capability retry cooldown`() {
        val cooldowns = (1..7).map { failures ->
            RelayCapabilitiesStore.computeCapabilityCooldownMs(failures)
        }

        assertEquals(60_000L, cooldowns[0])
        assertEquals(60_000L, cooldowns[2])
        assertEquals(120_000L, cooldowns[3])
        assertEquals(240_000L, cooldowns[4])
        assertEquals(300_000L, cooldowns[5])
        assertEquals(300_000L, cooldowns[6])
        assertTrue(cooldowns[5] > cooldowns[0])
    }

    @Test
    fun `structural CLOSED accumulates capability failures without touching transport strikes`() {
        var capabilities = RelayCapabilities(strikes = 2, consecutiveFailures = 2)
        repeat(5) { index ->
            capabilities = applyCapabilityRejection(
                capabilities,
                structuralSearchRejection,
                nowMs = 1_000L + index,
            )
        }

        assertEquals(2, capabilities.strikes)
        assertEquals(5, capabilities.consecutiveCapabilityFailures)
        assertTrue(capabilities.searchOnly)
        assertEquals("search filter is required", capabilities.lastCapabilityReason)
    }

    @Test
    fun `socket recovery preserves capability failures but accepted request clears them`() {
        val rejected = applyCapabilityRejection(
            RelayCapabilities(strikes = 3, consecutiveFailures = 5),
            structuralSearchRejection,
            nowMs = 1_000L,
        )

        val socketRecovered = clearTransportFailures(rejected)
        assertEquals(0, socketRecovered.strikes)
        assertEquals(1, socketRecovered.consecutiveCapabilityFailures)
        assertTrue(socketRecovered.searchOnly)

        val requestAccepted = clearCapabilityFailures(socketRecovered)
        assertEquals(0, requestAccepted.consecutiveCapabilityFailures)
        assertTrue(requestAccepted.searchOnly)
    }

    @Test
    fun `search-filter CLOSED learns search-only routing`() {
        val learned = applyCapabilityRejection(
            RelayCapabilities(),
            structuralSearchRejection,
            nowMs = 42L,
        )

        assertTrue(learned.searchOnly)
        assertFalse(isRequestClassCompatible(learned, RelayRequestClass.GENERAL))
        assertTrue(isRequestClassCompatible(learned, RelayRequestClass.NIP50_SEARCH))
        assertTrue(shouldIgnoreCapabilityCooldown(learned, RelayRequestClass.NIP50_SEARCH))

        val laterSearchFailure = learned.copy(lastCapabilityReason = "malformed search filter")
        assertFalse(
            shouldIgnoreCapabilityCooldown(
                laterSearchFailure,
                RelayRequestClass.NIP50_SEARCH,
            ),
        )
    }

    @Test
    fun `search-only routing fact survives transient strike expiry`() {
        val now = 48L * 60 * 60 * 1_000
        val staleSearchOnly = RelayCapabilities(
            searchOnly = true,
            lastCapabilityStrikeAt = 1L,
            lastCapabilityReason = "search filter is required",
        )
        val staleTransient = RelayCapabilities(
            consecutiveCapabilityFailures = 3,
            lastCapabilityStrikeAt = 1L,
            lastCapabilityReason = "unsupported filter",
        )

        assertTrue(shouldRetainPersistedCapabilities(staleSearchOnly, now))
        assertFalse(shouldRetainPersistedCapabilities(staleTransient, now))
    }

    @Test
    fun `legacy search rejection migrates without another losing request`() {
        val migrated = migrateLegacyCapabilityState(
            RelayCapabilities(
                strikes = 7,
                lastStrikeAt = 123_000L,
                lastReason = "search filter is required",
            ),
        )

        assertEquals(0, migrated.strikes)
        assertEquals(7, migrated.consecutiveCapabilityFailures)
        assertEquals(123_000L, migrated.lastCapabilityStrikeAt)
        assertEquals("search filter is required", migrated.lastCapabilityReason)
        assertTrue(migrated.searchOnly)
    }

    @Test
    fun `legacy transport state is not reclassified as capability state`() {
        val transport = RelayCapabilities(
            strikes = 7,
            lastStrikeAt = 123_000L,
            lastReason = SkipReason.CONNECT_TIMEOUT.name,
            consecutiveFailures = 7,
        )

        assertEquals(transport, migrateLegacyCapabilityState(transport))
    }

    @Test
    fun `blocked remains transient and teaches no capability`() {
        assertNull(classifyClosedRejection("blocked: filters must specify at least one kind"))
        assertNull(classifyClosedRejection("BLOCKED: search filter is required"))
    }

    @Test
    fun `restricted remains permanent across recovery paths`() {
        val restricted = applyCapabilityRejection(
            RelayCapabilities(),
            requireNotNull(classifyClosedRejection("restricted: not on white-list")),
            nowMs = 100L,
        )

        assertTrue(restricted.restricted)
        assertEquals(restricted, clearTransportFailures(restricted))
        assertEquals(restricted, clearCapabilityFailures(restricted))
        assertEquals(restricted, clearAllRelayFailures(restricted))
    }
}
