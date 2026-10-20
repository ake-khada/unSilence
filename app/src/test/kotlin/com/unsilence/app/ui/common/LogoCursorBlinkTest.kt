package com.unsilence.app.ui.common

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogoCursorBlinkTest {
    @Test fun `only half second edges are scheduled without a frame clock`() = cursorTest {
        val lifecycle = EntryLifecycle(Lifecycle.State.RESUMED)
        val changes = mutableListOf<Pair<Long, Boolean>>()
        backgroundScope.launch {
            runLogoCursorBlink(lifecycle, enabled = true) {
                changes += testScheduler.currentTime to it
            }
        }
        runCurrent()
        assertEquals(listOf(0L to true), changes)
        advanceTimeBy(499)
        runCurrent()
        assertEquals(listOf(0L to true), changes)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L to true, 500L to false), changes)
        advanceTimeBy(499)
        runCurrent()
        assertEquals(2, changes.size)
        advanceTimeBy(1_501)
        runCurrent()
        assertEquals(
            listOf(0L to true, 500L to false, 1_000L to true, 1_500L to false,
                2_000L to true, 2_500L to false),
            changes,
        )
    }

    @Test fun `static or hidden cursor is solid with no lifecycle subscription`() = cursorTest {
        val lifecycle = EntryLifecycle(Lifecycle.State.RESUMED)
        val changes = mutableListOf<Boolean>()
        val job = backgroundScope.launch {
            runLogoCursorBlink(lifecycle, enabled = false) { changes += it }
        }
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(0, lifecycle.observerCount)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(true), changes)
    }

    @Test fun `started navigation preview stays solid until resumed`() = cursorTest {
        val lifecycle = EntryLifecycle(Lifecycle.State.STARTED)
        val changes = mutableListOf<Boolean>()
        backgroundScope.launch {
            runLogoCursorBlink(lifecycle, enabled = true) { changes += it }
        }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(true), changes)
        lifecycle.send(Lifecycle.Event.ON_RESUME)
        runCurrent()
        advanceTimeBy(499)
        runCurrent()
        assertEquals(listOf(true), changes)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(true, false), changes)
    }

    @Test fun `pause resets solid and resume waits a fresh half second`() = cursorTest {
        val lifecycle = EntryLifecycle(Lifecycle.State.RESUMED)
        val changes = mutableListOf<Boolean>()
        backgroundScope.launch {
            runLogoCursorBlink(lifecycle, enabled = true) { changes += it }
        }
        runCurrent()
        advanceTimeBy(750)
        runCurrent()
        assertEquals(listOf(true, false), changes)
        lifecycle.send(Lifecycle.Event.ON_PAUSE)
        runCurrent()
        assertEquals(listOf(true, false, true), changes)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(3, changes.size)
        lifecycle.send(Lifecycle.Event.ON_RESUME)
        runCurrent()
        advanceTimeBy(499)
        runCurrent()
        assertEquals(3, changes.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(true, false, true, false), changes)
    }

    @Test fun `background cancels an imminent edge rather than replaying it on return`() = cursorTest {
        val lifecycle = EntryLifecycle(Lifecycle.State.RESUMED)
        val changes = mutableListOf<Boolean>()
        backgroundScope.launch {
            runLogoCursorBlink(lifecycle, enabled = true) { changes += it }
        }
        runCurrent()
        advanceTimeBy(499)
        lifecycle.send(Lifecycle.Event.ON_PAUSE)
        lifecycle.send(Lifecycle.Event.ON_STOP)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(changes.all { it })
        val count = changes.size
        lifecycle.send(Lifecycle.Event.ON_START)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(count, changes.size)
        lifecycle.send(Lifecycle.Event.ON_RESUME)
        runCurrent()
        advanceTimeBy(499)
        runCurrent()
        assertEquals(count, changes.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(false, changes.last())
    }

    @Test fun `effect cancellation on hide or disposal removes the timer and observer`() = cursorTest {
        val lifecycle = EntryLifecycle(Lifecycle.State.RESUMED)
        val changes = mutableListOf<Boolean>()
        val job = backgroundScope.launch {
            runLogoCursorBlink(lifecycle, enabled = true) { changes += it }
        }
        runCurrent()
        advanceTimeBy(750)
        runCurrent()
        job.cancelAndJoin()
        assertEquals(listOf(true, false, true), changes)
        assertEquals(0, lifecycle.observerCount)
        lifecycle.send(Lifecycle.Event.ON_PAUSE)
        lifecycle.send(Lifecycle.Event.ON_RESUME)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, changes.size)
    }

    private fun cursorTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            backgroundScope.coroutineContext.cancelChildren()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private class EntryLifecycle(private var state: State) : Lifecycle(), LifecycleOwner {
        private val observers = linkedSetOf<LifecycleEventObserver>()
        val observerCount get() = observers.size
        override val lifecycle: Lifecycle get() = this
        override val currentState: State get() = state
        override fun addObserver(observer: LifecycleObserver) {
            observers += observer as LifecycleEventObserver
            if (state.isAtLeast(State.CREATED)) observer.onStateChanged(this, Event.ON_CREATE)
            if (state.isAtLeast(State.STARTED)) observer.onStateChanged(this, Event.ON_START)
            if (state.isAtLeast(State.RESUMED)) observer.onStateChanged(this, Event.ON_RESUME)
        }
        override fun removeObserver(observer: LifecycleObserver) { observers.remove(observer) }
        fun send(event: Event) {
            state = event.targetState
            observers.toList().forEach { it.onStateChanged(this, event) }
        }
    }
}
