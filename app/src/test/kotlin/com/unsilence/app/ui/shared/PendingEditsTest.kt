package com.unsilence.app.ui.shared

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingEditsTest {
    @Test fun `queued writes are visible before dispatch and drain independently`() = runTest {
        val edits = PendingEdits(backgroundScope)
        val one = CompletableDeferred<Unit>()
        val two = CompletableDeferred<Unit>()
        edits.launch { one.await() }
        edits.launch { two.await() }
        assertEquals(2, edits.count.value)
        runCurrent()
        one.complete(Unit)
        runCurrent()
        assertEquals(1, edits.count.value)
        two.complete(Unit)
        runCurrent()
        assertEquals(0, edits.count.value)
    }

    @Test fun `cancellation before dispatch cannot strand the pending count`() = runTest {
        val edits = PendingEdits(backgroundScope)
        val job = edits.launch { error("must not execute") }
        job.cancel()
        runCurrent()
        assertEquals(0, edits.count.value)
    }

    @Test fun `account scope cancellation cancels writes and drains count`() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val edits = PendingEdits(scope)
        val completion = CompletableDeferred<Unit>()
        edits.launch { completion.await() }
        runCurrent()
        assertEquals(1, edits.count.value)
        scope.cancel()
        runCurrent()
        assertEquals(0, edits.count.value)
    }
}
