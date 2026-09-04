package com.unsilence.app.data.init

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitGateTest {

    @Test
    fun `awaitFollows suspends until signaled`() = runTest {
        val gate = InitGate()
        val session = gate.beginSession("alice")
        assertFalse(gate.followsReady)
        val pending = async { gate.awaitFollows() }
        assertFalse("should not be complete before signal", pending.isCompleted)
        gate.signalFollowsReady(session)
        pending.await()
        assertTrue(gate.followsReady)
    }

    @Test
    fun `awaitFollows returns immediately if already signaled`() = runTest {
        val gate = InitGate()
        val session = gate.beginSession("alice")
        gate.signalFollowsReady(session)
        // If await suspends, this would hang. runTest's virtual time means we'd timeout.
        gate.awaitFollows()  // must not suspend
        assertTrue(gate.followsReady)
    }

    @Test
    fun `multiple concurrent awaiters all release on single signal`() = runTest {
        val gate = InitGate()
        val session = gate.beginSession("alice")
        val a = async { gate.awaitFollows() }
        val b = async { gate.awaitFollows() }
        val c = async { gate.awaitFollows() }
        gate.signalFollowsReady(session)
        a.await(); b.await(); c.await()
    }

    @Test
    fun `awaitReady requires both signals`() = runTest {
        val gate = InitGate()
        val session = gate.beginSession("alice")
        val ready = async { gate.awaitReady() }
        gate.signalFollowsReady(session)
        assertFalse("not ready with follows only", ready.isCompleted)
        gate.signalRelaysReady(session)
        ready.await()
        assertTrue(gate.isReady)
    }

    @Test
    fun `signalFollowsReady is idempotent`() = runTest {
        val gate = InitGate()
        val session = gate.beginSession("alice")
        gate.signalFollowsReady(session)
        gate.signalFollowsReady(session)
        gate.signalFollowsReady(session)
        assertTrue(gate.followsReady)
    }

    @Test
    fun `signalRelaysReady is idempotent`() = runTest {
        val gate = InitGate()
        val session = gate.beginSession("alice")
        gate.signalRelaysReady(session)
        gate.signalRelaysReady(session)
        assertTrue(gate.relaysReady)
    }

    @Test
    fun `phase transitions through CONNECTING then FOLLOWS then READY`() = runTest {
        val gate = InitGate()
        val session = gate.beginSession("alice")
        assertEquals(InitGate.Phase.CONNECTING, gate.phase.value)
        gate.signalFollowsReady(session)
        assertEquals(InitGate.Phase.FOLLOWS, gate.phase.value)
        gate.signalRelaysReady(session)
        assertEquals(InitGate.Phase.READY, gate.phase.value)
    }

    @Test
    fun `phase handles relays-before-follows gracefully`() = runTest {
        val gate = InitGate()
        val session = gate.beginSession("alice")
        gate.signalRelaysReady(session)
        assertEquals(InitGate.Phase.RELAYS, gate.phase.value)
        gate.signalFollowsReady(session)
        assertEquals(InitGate.Phase.READY, gate.phase.value)
    }

    @Test
    fun `awaitFollows respects external timeout cancellation`() = runTest {
        val gate = InitGate()
        val session = gate.beginSession("alice")
        var threw = false
        try {
            withTimeout(50) { gate.awaitFollows() }
        } catch (_: TimeoutCancellationException) {
            threw = true
        }
        assertTrue("withTimeout should cancel awaitFollows", threw)
        assertFalse("gate state unchanged after cancellation", gate.followsReady)
        // Subsequent signal still works
        gate.signalFollowsReady(session)
        gate.awaitFollows()  // must not suspend
    }

    @Test
    fun `isReady false until both signaled`() = runTest {
        val gate = InitGate()
        val session = gate.beginSession("alice")
        assertFalse(gate.isReady)
        gate.signalFollowsReady(session)
        assertFalse(gate.isReady)
        gate.signalRelaysReady(session)
        assertTrue(gate.isReady)
    }

    @Test
    fun `new login starts with fresh readiness`() = runTest {
        val gate = InitGate()
        val first = gate.beginSession("alice")
        gate.signalFollowsReady(first)
        gate.signalRelaysReady(first)
        gate.signalFeedConnectionsReady(first)

        gate.beginSession("bob")

        assertFalse(gate.followsReady)
        assertFalse(gate.relaysReady)
        assertFalse(gate.feedConnectionsReady)
        assertEquals(InitGate.Phase.CONNECTING, gate.phase.value)
    }

    @Test
    fun `late signals from previous login cannot release current login`() = runTest {
        val gate = InitGate()
        val stale = gate.beginSession("alice")
        val current = gate.beginSession("bob")

        gate.signalFollowsReady(stale)
        gate.signalRelaysReady(stale)
        gate.signalFeedConnectionsReady(stale)

        assertFalse(gate.followsReady)
        assertFalse(gate.relaysReady)
        assertFalse(gate.feedConnectionsReady)
        gate.signalFollowsReady(current)
        assertTrue(gate.followsReady)
    }

    @Test
    fun `logout invalidates current bootstrap token`() = runTest {
        val gate = InitGate()
        val stale = gate.beginSession("alice")

        assertTrue(gate.isCurrent(stale, "alice"))
        assertFalse(gate.isCurrent(stale, "bob"))

        gate.invalidateSession()
        gate.signalFollowsReady(stale)

        assertFalse(gate.followsReady)
        assertFalse(gate.isCurrent(stale))
    }
}
