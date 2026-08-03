package com.unsilence.app.ui.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SigningAttemptGateTest {

    @Test
    fun `only one signing attempt can be active`() {
        val gate = SigningAttemptGate()
        val first = gate.begin()
        assertNotNull(first)
        assertNull(gate.begin())
        assertTrue(gate.isCurrent(first!!))
    }

    @Test
    fun `cancel invalidates late signer result and permits a fresh attempt`() {
        val gate = SigningAttemptGate()
        val cancelled = gate.begin()!!
        gate.cancel()
        assertFalse(gate.isCurrent(cancelled))

        val replacement = gate.begin()!!
        assertTrue(gate.isCurrent(replacement))
        assertFalse(gate.isCurrent(cancelled))
    }

    @Test
    fun `completion releases the gate without affecting a newer token`() {
        val gate = SigningAttemptGate()
        val first = gate.begin()!!
        gate.complete(first)
        val second = gate.begin()!!
        gate.complete(first)
        assertTrue(gate.isCurrent(second))
    }
}
