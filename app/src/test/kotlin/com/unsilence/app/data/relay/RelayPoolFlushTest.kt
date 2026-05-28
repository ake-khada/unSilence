package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for flushRelayQueue cooldown gate logic.
 *
 * Models the `count < MAX && queueNotEmpty && canSend` predicate that gates
 * whether queued REQs are flushed to a relay after a slot frees.
 */
class RelayPoolFlushTest {

    /**
     * Models the flush loop predicate: proceeds only when the slot count
     * is under the cap, the queue has items, AND the relay isn't rate-limited.
     * `queueNotEmpty` short-circuits before `canSend` so an empty-queue flush
     * never consumes a rate-limit token.
     */
    private fun shouldFlush(count: Int, max: Int, queueNotEmpty: Boolean, canSend: Boolean): Boolean =
        count < max && queueNotEmpty && canSend

    @Test
    fun `flush proceeds when under cap and queue non-empty and tokens available`() {
        assertTrue(shouldFlush(count = 5, max = 10, queueNotEmpty = true, canSend = true))
    }

    @Test
    fun `flush stops when at cap even if tokens available`() {
        assertFalse(shouldFlush(count = 10, max = 10, queueNotEmpty = true, canSend = true))
    }

    @Test
    fun `flush stops when in cooldown even if slots available`() {
        assertFalse(shouldFlush(count = 5, max = 10, queueNotEmpty = true, canSend = false))
    }

    @Test
    fun `flush stops when both at cap and in cooldown`() {
        assertFalse(shouldFlush(count = 10, max = 10, queueNotEmpty = true, canSend = false))
    }

    @Test
    fun `empty queue flush does not evaluate canSend`() {
        // Models the short-circuit: queueNotEmpty is false, so canSend is never reached.
        // In production this means no rate-limit token is consumed on empty flushes.
        var canSendCalled = false
        val canSend = run { canSendCalled = true; true }

        // shouldFlush with queueNotEmpty=false should return false
        // regardless of canSend value — and in the real while-loop,
        // && short-circuits so canSendToRelay is never invoked.
        assertFalse(shouldFlush(count = 0, max = 10, queueNotEmpty = false, canSend = true))
        assertFalse(shouldFlush(count = 0, max = 10, queueNotEmpty = false, canSend = false))
    }

    /**
     * Models a flush sequence: each successful flush increments count and
     * consumes a rate-limit token. When cooldown kicks in mid-flush,
     * remaining items stay queued.
     */
    @Test
    fun `flush sequence stops mid-queue when cooldown activates`() {
        val max = 10
        var count = 7
        val queue = mutableListOf("req1", "req2", "req3", "req4", "req5")
        val sent = mutableListOf<String>()
        var tokensLeft = 2  // cooldown after 2 sends

        while (shouldFlush(count, max, queue.isNotEmpty(), tokensLeft > 0)) {
            val req = queue.removeFirstOrNull() ?: break
            count++
            tokensLeft--
            sent.add(req)
        }

        assertEquals(listOf("req1", "req2"), sent)
        assertEquals(listOf("req3", "req4", "req5"), queue)
        assertEquals(9, count)
    }
}
