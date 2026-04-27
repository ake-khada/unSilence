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
        assertFalse(gate.followsReady)
        val pending = async { gate.awaitFollows() }
        assertFalse("should not be complete before signal", pending.isCompleted)
        gate.signalFollowsReady()
        pending.await()
        assertTrue(gate.followsReady)
    }

    @Test
    fun `awaitFollows returns immediately if already signaled`() = runTest {
        val gate = InitGate()
        gate.signalFollowsReady()
        // If await suspends, this would hang. runTest's virtual time means we'd timeout.
        gate.awaitFollows()  // must not suspend
        assertTrue(gate.followsReady)
    }

    @Test
    fun `multiple concurrent awaiters all release on single signal`() = runTest {
        val gate = InitGate()
        val a = async { gate.awaitFollows() }
        val b = async { gate.awaitFollows() }
        val c = async { gate.awaitFollows() }
        gate.signalFollowsReady()
        a.await(); b.await(); c.await()
    }

    @Test
    fun `awaitReady requires both signals`() = runTest {
        val gate = InitGate()
        val ready = async { gate.awaitReady() }
        gate.signalFollowsReady()
        assertFalse("not ready with follows only", ready.isCompleted)
        gate.signalRelaysReady()
        ready.await()
        assertTrue(gate.isReady)
    }

    @Test
    fun `signalFollowsReady is idempotent`() = runTest {
        val gate = InitGate()
        gate.signalFollowsReady()
        gate.signalFollowsReady()
        gate.signalFollowsReady()
        assertTrue(gate.followsReady)
    }

    @Test
    fun `signalRelaysReady is idempotent`() = runTest {
        val gate = InitGate()
        gate.signalRelaysReady()
        gate.signalRelaysReady()
        assertTrue(gate.relaysReady)
    }

    @Test
    fun `phase transitions through CONNECTING then FOLLOWS then READY`() = runTest {
        val gate = InitGate()
        assertEquals(InitGate.Phase.CONNECTING, gate.phase.value)
        gate.signalFollowsReady()
        assertEquals(InitGate.Phase.FOLLOWS, gate.phase.value)
        gate.signalRelaysReady()
        assertEquals(InitGate.Phase.READY, gate.phase.value)
    }

    @Test
    fun `phase handles relays-before-follows gracefully`() = runTest {
        val gate = InitGate()
        gate.signalRelaysReady()
        assertEquals(InitGate.Phase.RELAYS, gate.phase.value)
        gate.signalFollowsReady()
        assertEquals(InitGate.Phase.READY, gate.phase.value)
    }

    @Test
    fun `awaitFollows respects external timeout cancellation`() = runTest {
        val gate = InitGate()
        var threw = false
        try {
            withTimeout(50) { gate.awaitFollows() }
        } catch (_: TimeoutCancellationException) {
            threw = true
        }
        assertTrue("withTimeout should cancel awaitFollows", threw)
        assertFalse("gate state unchanged after cancellation", gate.followsReady)
        // Subsequent signal still works
        gate.signalFollowsReady()
        gate.awaitFollows()  // must not suspend
    }

    @Test
    fun `isReady false until both signaled`() = runTest {
        val gate = InitGate()
        assertFalse(gate.isReady)
        gate.signalFollowsReady()
        assertFalse(gate.isReady)
        gate.signalRelaysReady()
        assertTrue(gate.isReady)
    }
}
