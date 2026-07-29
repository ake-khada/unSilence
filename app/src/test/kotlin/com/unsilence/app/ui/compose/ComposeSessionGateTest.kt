package com.unsilence.app.ui.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeSessionGateTest {

    @Test
    fun `reattaching the same compose session does not initialize it again`() {
        val gate = ComposeSessionGate()

        assertTrue(gate.begin("new"))
        assertFalse(gate.begin("new"))
    }

    @Test
    fun `finishing a session permits a clean reopen`() {
        val gate = ComposeSessionGate()

        assertTrue(gate.begin("reply:event-id"))
        gate.finish("reply:event-id")

        assertTrue(gate.begin("reply:event-id"))
    }

    @Test
    fun `a different compose context starts a new session`() {
        val gate = ComposeSessionGate()

        assertTrue(gate.begin("new"))
        assertTrue(gate.begin("quote:event-id"))
        assertFalse(gate.begin("quote:event-id"))
    }
}
