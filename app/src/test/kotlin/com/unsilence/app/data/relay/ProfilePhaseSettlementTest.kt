package com.unsilence.app.data.relay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePhaseSettlementTest {
    @Test fun `already settled profiles have no phase wait`() = runTest {
        val start = currentTime
        awaitProfilePhaseSettlement(MutableStateFlow(0L), { true }, 2_500)
        assertEquals(0L, currentTime - start)
    }

    @Test fun `wait ends on complete metadata without starting another fallback`() = runTest {
        val signal = MutableStateFlow(0L)
        var complete = false
        val job = launch { awaitProfilePhaseSettlement(signal, { complete }, 2_500) }
        runCurrent()
        assertFalse(job.isCompleted)
        advanceTimeBy(300)
        complete = true
        signal.value++
        runCurrent()
        assertTrue("Readiness, not another fixed sleep, releases the phase", job.isCompleted)
        assertEquals(300L, currentTime)
    }

    @Test fun `partial or absent metadata cannot advance fallback before original deadline`() = runTest {
        val signal = MutableStateFlow(0L)
        var nextTier = false
        val job = launch {
            awaitProfilePhaseSettlement(signal, { false }, 2_500)
            nextTier = true
        }
        runCurrent()
        signal.value++ // an unrelated/partial kind-0 response
        advanceTimeBy(2_499)
        runCurrent()
        assertFalse(nextTier)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(nextTier)
        job.join()
    }

    @Test fun `cancelled phase does not advance fallback`() = runTest {
        var nextTier = false
        val job = launch {
            awaitProfilePhaseSettlement(MutableStateFlow(0L), { false }, 2_500)
            nextTier = true
        }
        runCurrent()
        job.cancel()
        advanceTimeBy(2_500)
        runCurrent()
        assertFalse(nextTier)
    }
}
