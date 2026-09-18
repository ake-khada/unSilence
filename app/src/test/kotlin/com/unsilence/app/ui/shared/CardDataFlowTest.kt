package com.unsilence.app.ui.shared

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CardDataFlowTest {
    @Test fun `no work exists until a card collects and cancellation releases it immediately`() = runTest {
        var active = 0
        var snapshot = 1
        val handle = CardDataFlow(flow {
            active++
            try {
                emit(snapshot)
                awaitCancellation()
            } finally { active-- }
        }) { snapshot }
        assertEquals(1, handle.value)
        snapshot = 2
        assertEquals(2, handle.value)
        assertEquals(0, active)
        val visible = backgroundScope.launch { handle.collect() }
        runCurrent()
        assertEquals(1, active)
        visible.cancelAndJoin()
        assertEquals(0, active)
        val remounted = backgroundScope.launch { handle.collect() }
        runCurrent()
        assertEquals(1, active)
        remounted.cancelAndJoin()
        assertEquals(0, active)
    }
}
