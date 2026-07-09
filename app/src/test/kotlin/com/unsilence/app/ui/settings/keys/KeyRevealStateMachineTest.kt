package com.unsilence.app.ui.settings.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRevealStateMachineTest {
    @Test
    fun startsMaskedThenAuthenticatingThenRevealed() {
        val machine = KeyRevealStateMachine(revealDurationMillis = 30_000L)

        assertEquals(KeyRevealState.Masked, machine.state)
        assertEquals(KeyRevealState.Authenticating, machine.startAuthentication())

        val revealed = machine.reveal(nowMillis = 1_000L)

        assertEquals(KeyRevealState.Revealed(expiresAtMillis = 31_000L), revealed)
        assertEquals(revealed, machine.state)
    }

    @Test
    fun timeoutMasksRevealedState() {
        val machine = KeyRevealStateMachine(revealDurationMillis = 30_000L)
        machine.reveal(nowMillis = 1_000L)

        assertTrue(machine.tick(nowMillis = 30_999L) is KeyRevealState.Revealed)
        assertEquals(KeyRevealState.Masked, machine.tick(nowMillis = 31_000L))
        assertEquals(KeyRevealState.Masked, machine.state)
    }

    @Test
    fun pauseMasksRevealedState() {
        val machine = KeyRevealStateMachine(revealDurationMillis = 30_000L)
        machine.reveal(nowMillis = 1_000L)

        assertEquals(KeyRevealState.Masked, machine.pause())
        assertEquals(KeyRevealState.Masked, machine.state)
    }

    @Test
    fun cancelMasksAuthenticatingState() {
        val machine = KeyRevealStateMachine(revealDurationMillis = 30_000L)
        machine.startAuthentication()

        assertEquals(KeyRevealState.Masked, machine.cancel())
        assertEquals(KeyRevealState.Masked, machine.state)
    }
}
