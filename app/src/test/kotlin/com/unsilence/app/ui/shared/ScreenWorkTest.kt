package com.unsilence.app.ui.shared

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenWorkTest {
    @Test fun `covered entry has no source collector and resumes from current value`() = runTest {
        val active = MutableStateFlow(false)
        val source = MutableStateFlow(1)
        val observed = mutableListOf<Int>()
        val job = launch { source.collectLatestWhileActive(active) { observed += it } }
        runCurrent()
        assertEquals(0, source.subscriptionCount.value)
        active.value = true
        runCurrent()
        assertEquals(1, source.subscriptionCount.value)
        assertEquals(listOf(1), observed)
        active.value = false
        runCurrent()
        source.value = 2
        source.value = 3
        runCurrent()
        assertEquals(0, source.subscriptionCount.value)
        assertEquals(listOf(1), observed)
        active.value = true
        runCurrent()
        assertEquals(listOf(1, 3), observed)
        job.cancel()
        runCurrent()
        assertEquals(0, source.subscriptionCount.value)
    }

    @Test fun `covering cancels in flight work not just future emissions`() = runTest {
        val active = MutableStateFlow(true)
        var working = 0
        var closed = 0
        val job = launch {
            MutableStateFlow(1).collectLatestWhileActive(active) {
                working++
                try { awaitCancellation() } finally { working--; closed++ }
            }
        }
        runCurrent()
        assertEquals(1, working)
        active.value = false
        runCurrent()
        assertEquals(0, working)
        assertEquals(1, closed)
        active.value = true
        runCurrent()
        assertEquals(1, working)
        job.cancel()
        runCurrent()
        assertEquals(0, working)
        assertEquals(2, closed)
    }

    @Test fun `repeated visits and cancellation release each owned subscription`() = runTest {
        val active = MutableStateFlow(false)
        var open = 0
        var closed = 0
        val source = flow {
            open++
            try { emit(Unit); awaitCancellation() } finally { open--; closed++ }
        }
        val job = launch { source.collectLatestWhileActive(active) {} }
        repeat(50) {
            active.value = true
            runCurrent()
            assertEquals(1, open)
            active.value = false
            runCurrent()
            assertEquals(0, open)
        }
        job.cancel()
        runCurrent()
        assertEquals(50, closed)
    }
}
