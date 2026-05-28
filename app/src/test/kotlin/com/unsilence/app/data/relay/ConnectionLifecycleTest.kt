package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for connection lifecycle decision logic:
 * - listenForEvents reconnect decision (identity check + purpose + skip)
 * - connectAndAwait stale-entry replacement (read-decide-write)
 * - map-before-close ordering contract
 *
 * Uses the same extracted-logic pattern as RelayPoolSweepTest.
 */
class ConnectionLifecycleTest {

    // ── listenForEvents reconnect decision ──────────────────────────────

    /**
     * Models the reconnect decision in listenForEvents.finally:
     * 1. Identity check: connections[url] === conn
     * 2. Still needed: hasAnyPurpose(url) || url in activeSubUrls
     * 3. Not skipped: !shouldSkip(url)
     */
    private enum class ReconnectDecision { RECONNECT, CLEANUP, SKIP_REPLACED }

    private fun reconnectDecision(
        currentConnId: Int?,
        exitingConnId: Int,
        hasPurpose: Boolean,
        inActiveSubs: Boolean,
        recentlyActive: Boolean = false,
        shouldSkip: Boolean,
    ): ReconnectDecision {
        // Identity check: is the exiting conn still the current map entry?
        if (currentConnId != exitingConnId) return ReconnectDecision.SKIP_REPLACED
        val stillNeeded = hasPurpose || inActiveSubs || recentlyActive
        val notSkipped = !shouldSkip
        return if (stillNeeded && notSkipped) ReconnectDecision.RECONNECT
        else ReconnectDecision.CLEANUP
    }

    @Test
    fun `reconnect when conn is current and has purpose`() {
        val result = reconnectDecision(
            currentConnId = 1, exitingConnId = 1,
            hasPurpose = true, inActiveSubs = false, shouldSkip = false,
        )
        assertEquals(ReconnectDecision.RECONNECT, result)
    }

    @Test
    fun `reconnect when conn is current and has active sub`() {
        val result = reconnectDecision(
            currentConnId = 1, exitingConnId = 1,
            hasPurpose = false, inActiveSubs = true, shouldSkip = false,
        )
        assertEquals(ReconnectDecision.RECONNECT, result)
    }

    @Test
    fun `reconnect when conn is current and recently active (outbox relay)`() {
        val result = reconnectDecision(
            currentConnId = 1, exitingConnId = 1,
            hasPurpose = false, inActiveSubs = false, recentlyActive = true, shouldSkip = false,
        )
        assertEquals(ReconnectDecision.RECONNECT, result)
    }

    @Test
    fun `cleanup when conn is current but no purpose no subs and not recently active`() {
        val result = reconnectDecision(
            currentConnId = 1, exitingConnId = 1,
            hasPurpose = false, inActiveSubs = false, recentlyActive = false, shouldSkip = false,
        )
        assertEquals(ReconnectDecision.CLEANUP, result)
    }

    @Test
    fun `cleanup when conn is current but transport-skipped`() {
        val result = reconnectDecision(
            currentConnId = 1, exitingConnId = 1,
            hasPurpose = true, inActiveSubs = true, recentlyActive = true, shouldSkip = true,
        )
        assertEquals(ReconnectDecision.CLEANUP, result)
    }

    @Test
    fun `skip when map already replaced by another conn`() {
        val result = reconnectDecision(
            currentConnId = 2, exitingConnId = 1,
            hasPurpose = true, inActiveSubs = true, shouldSkip = false,
        )
        assertEquals(ReconnectDecision.SKIP_REPLACED, result)
    }

    @Test
    fun `skip when map entry removed (null)`() {
        val result = reconnectDecision(
            currentConnId = null, exitingConnId = 1,
            hasPurpose = true, inActiveSubs = true, shouldSkip = false,
        )
        assertEquals(ReconnectDecision.SKIP_REPLACED, result)
    }

    // ── connectAndAwait stale-entry replacement ─────────────────────────

    /**
     * Models the read-decide-write logic in connectAndAwait/connect:
     * - CONNECTED/CONNECTING → reuse (skip)
     * - DISCONNECTED/FAILED → evict and replace
     * - null (no entry) → create new
     */
    private enum class EntryDecision { REUSE, EVICT_AND_REPLACE, CREATE_NEW }

    private fun entryDecision(existingState: RelayState?): EntryDecision = when (existingState) {
        RelayState.CONNECTED, RelayState.CONNECTING -> EntryDecision.REUSE
        RelayState.DISCONNECTED, RelayState.FAILED -> EntryDecision.EVICT_AND_REPLACE
        null -> EntryDecision.CREATE_NEW
    }

    @Test
    fun `reuse when CONNECTED`() {
        assertEquals(EntryDecision.REUSE, entryDecision(RelayState.CONNECTED))
    }

    @Test
    fun `reuse when CONNECTING`() {
        assertEquals(EntryDecision.REUSE, entryDecision(RelayState.CONNECTING))
    }

    @Test
    fun `evict when DISCONNECTED`() {
        assertEquals(EntryDecision.EVICT_AND_REPLACE, entryDecision(RelayState.DISCONNECTED))
    }

    @Test
    fun `evict when FAILED`() {
        assertEquals(EntryDecision.EVICT_AND_REPLACE, entryDecision(RelayState.FAILED))
    }

    @Test
    fun `create new when no entry`() {
        assertEquals(EntryDecision.CREATE_NEW, entryDecision(null))
    }

    // ── map-before-close ordering ───────────────────────────────────────

    /**
     * Verifies the map-before-close contract: after removal from the map,
     * the identity check (connections[url] === conn) must fail, preventing
     * the listenForEvents.finally block from scheduling a spurious reconnect.
     */
    @Test
    fun `identity check fails after map removal`() {
        // Simulate: connections map with conn A at url
        val connections = mutableMapOf<String, Int>()
        val url = "wss://relay.example"
        val connA = 1

        connections[url] = connA
        assertTrue(connections[url] == connA)

        // Remove from map (map-before-close step)
        connections.remove(url)

        // Identity check in finally block should fail
        assertFalse(connections[url] == connA)
    }

    @Test
    fun `identity check fails after map replacement`() {
        // Simulate: reconnectWithBackoff replaces conn A with conn B
        val connections = mutableMapOf<String, Int>()
        val url = "wss://relay.example"
        val connA = 1
        val connB = 2

        connections[url] = connA
        assertTrue(connections[url] == connA)

        // Replace in map (atomic replace step)
        connections[url] = connB

        // Identity check for old conn A should fail
        assertFalse(connections[url] == connA)
        // But succeeds for new conn B
        assertTrue(connections[url] == connB)
    }

    // ── getOrCreateConnection eviction closes old entry ──────────────────

    /**
     * Models the getOrCreateConnection eviction path: when an existing entry
     * is not connected, it must be removed from the map AND closed before
     * the new entry is inserted. Verifies map-before-close ordering.
     */
    @Test
    fun `getOrCreateConnection eviction removes then closes old entry`() {
        data class MockConn(val id: Int, var closed: Boolean = false)

        val connections = mutableMapOf<String, MockConn>()
        val url = "wss://relay.example"
        val oldConn = MockConn(1)
        connections[url] = oldConn

        // Simulate getOrCreateConnection eviction path:
        // existing != null && !existing.isConnected
        val existing = connections[url]
        assertFalse("old conn should not be closed yet", existing!!.closed)

        // map-before-close
        connections.remove(url)
        existing.closed = true

        // Insert new
        val newConn = MockConn(2)
        connections[url] = newConn

        // Verify: old is closed, new is in map
        assertTrue("old conn must be closed", oldConn.closed)
        assertEquals(newConn, connections[url])
        assertFalse("new conn must not be closed", newConn.closed)

        // Identity check: old conn no longer matches
        assertFalse(connections[url] == oldConn)
    }

    @Test
    fun `disconnectAll snapshot-then-clear prevents reconnect for all conns`() {
        val connections = mutableMapOf<String, Int>()
        connections["wss://a.example"] = 1
        connections["wss://b.example"] = 2
        connections["wss://c.example"] = 3

        // Snapshot then clear (production pattern)
        val snapshot = connections.values.toList()
        connections.clear()

        // All identity checks fail
        assertEquals(3, snapshot.size)
        for ((url, connId) in listOf(
            "wss://a.example" to 1,
            "wss://b.example" to 2,
            "wss://c.example" to 3,
        )) {
            assertFalse("$url identity check should fail", connections[url] == connId)
        }
    }
}
