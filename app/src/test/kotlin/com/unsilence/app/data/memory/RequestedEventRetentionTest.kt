package com.unsilence.app.data.memory

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestedEventRetentionTest {
    @Test
    fun `distinct IDs are bounded and duplicate callers keep independent ownership`() {
        val retention = RequestedEventRetention(maxIds = 2)
        val first = retention.acquire("a")
        val second = retention.acquire("a")
        val other = retention.acquire("b")
        retention.acquire("c").close()
        assertEquals(setOf("a", "b"), retention.snapshot())
        first.close()
        first.close()
        assertTrue(retention.contains("a"))
        second.close()
        assertFalse(retention.contains("a"))
        other.close()
        assertTrue(retention.snapshot().isEmpty())
    }

    @Test
    fun `old session completion cannot release new session ownership`() {
        val retention = RequestedEventRetention()
        val old = retention.acquire("same")
        retention.clear()
        val fresh = retention.acquire("same")
        old.close()
        assertTrue(retention.contains("same"))
        fresh.close()
        assertFalse(retention.contains("same"))
    }

    @Test
    fun `cancelled lookup releases its lease`() = runTest {
        val retention = RequestedEventRetention()
        val job = launch {
            retention.acquire("pending").use { awaitCancellation() }
        }
        runCurrent()
        assertTrue(retention.contains("pending"))
        job.cancelAndJoin()
        assertTrue(retention.snapshot().isEmpty())
    }
}
