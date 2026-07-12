package com.unsilence.app.data.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayClosedPolicyTest {
    @Test
    fun `closed reason resubscribe policy`() {
        assertFalse(shouldResubAfterClosed("rate-limit: slow down", isOneShot = false))
        assertFalse(shouldResubAfterClosed("too many concurrent subscriptions", isOneShot = false))
        assertFalse(shouldResubAfterClosed("auth-required: sign in", isOneShot = false))
        assertFalse(shouldResubAfterClosed("", isOneShot = true))
        assertTrue(shouldResubAfterClosed("", isOneShot = false))
        assertTrue(shouldResubAfterClosed("relay maintenance", isOneShot = false))
    }
}
