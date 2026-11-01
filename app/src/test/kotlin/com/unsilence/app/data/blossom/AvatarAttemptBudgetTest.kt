package com.unsilence.app.data.blossom

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AvatarAttemptBudgetTest {
    @Test fun `waiting behind busy recoveries does not consume the mirror attempt budget`() = runTest {
        val permits = Semaphore(2, acquiredPermits = 2)
        val attempts = mutableListOf<String>()
        val queued = async {
            withAvatarAttemptBudget(permits) {
                attempts += "https://blossom.primal.net/${"ab".repeat(32)}.jpg"
                "verified"
            }
        }
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertFalse("A queued request must not expire before it can attempt its candidates", queued.isCompleted)
        assertTrue(attempts.isEmpty())
        permits.release(); runCurrent()
        assertEquals("verified", queued.await())
        assertEquals(listOf("https://blossom.primal.net/${"ab".repeat(32)}.jpg"), attempts)
        permits.release()
    }

    @Test fun `actual attempt still times out and releases its slot`() = runTest {
        val permits = Semaphore(2)
        val timed = async { withAvatarAttemptBudget(permits) { delay(20_000); "late" } }
        runCurrent(); advanceTimeBy(15_001); runCurrent()
        assertNull(timed.await())
        assertEquals(2, permits.availablePermits)
    }
}
