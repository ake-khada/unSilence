package com.unsilence.app.ui.shared

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.unsilence.app.data.model.VideoRenderModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationPlaybackGateTest {
    @Test fun `first composition observes permission even before lifecycle attachment`() {
        val gate = NavigationPlaybackGate(EntryLifecycle(Lifecycle.State.RESUMED)) {}
        var invalidations = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        observer.observeReads(Any(), { invalidations++ }) { assertFalse(gate.canPlay) }
        gate.attach()
        Snapshot.sendApplyNotifications()
        assertEquals(1, invalidations)
        observer.stop()
        observer.clear()
    }

    @Test fun `unattached scope fails closed even on an already resumed entry`() {
        val lifecycle = EntryLifecycle(Lifecycle.State.RESUMED)
        val gate = NavigationPlaybackGate(lifecycle) {}
        assertFalse(gate.canPlay)
        gate.attach()
        assertTrue(gate.canPlay)
    }

    @Test fun `every state below resumed denies playback from first composition`() {
        Lifecycle.State.entries.filter { it != Lifecycle.State.RESUMED }.forEach { state ->
            val gate = NavigationPlaybackGate(EntryLifecycle(state)) {}
            gate.attach()
            assertFalse("$state must not authorize playback", gate.canPlay)
        }
    }

    @Test fun `pause releases permission and resume restores it`() {
        val lifecycle = EntryLifecycle(Lifecycle.State.RESUMED)
        val changes = mutableListOf<Boolean>()
        val gate = NavigationPlaybackGate(lifecycle) { changes += it }
        gate.attach()
        lifecycle.send(Lifecycle.Event.ON_PAUSE)
        assertFalse(gate.canPlay)
        lifecycle.send(Lifecycle.Event.ON_RESUME)
        assertTrue(gate.canPlay)
        gate.dispose()
        assertFalse(gate.canPlay)
        assertEquals(listOf(true, false, true, false), changes)
        assertEquals(0, lifecycle.observerCount)
    }

    @Test fun `queued playback rechecks lifecycle before observer notification`() {
        val lifecycle = EntryLifecycle(Lifecycle.State.RESUMED)
        val gate = NavigationPlaybackGate(lifecycle) {}
        gate.attach()
        lifecycle.state = Lifecycle.State.STARTED
        assertFalse(gate.canPlay)
    }

    @Test fun `cancelled preview never receives playback permission`() {
        val lifecycle = EntryLifecycle(Lifecycle.State.STARTED)
        val changes = mutableListOf<Boolean>()
        val gate = NavigationPlaybackGate(lifecycle) { changes += it }
        gate.attach()
        lifecycle.send(Lifecycle.Event.ON_STOP)
        gate.dispose()
        // A late event after disposal cannot re-authorize the preview.
        lifecycle.send(Lifecycle.Event.ON_RESUME)
        assertFalse(gate.canPlay)
        assertTrue(changes.isEmpty())
    }

    @Test fun `outgoing release callback respects shared player ownership`() {
        var owner: String? = "thread"
        var playing = true
        val lifecycle = EntryLifecycle(Lifecycle.State.RESUMED)
        val gate = NavigationPlaybackGate(lifecycle) { resumed ->
            // Same owner-checked contract as SharedPlayerHolder.releaseOwnership.
            if (!resumed && owner == "thread") { playing = false; owner = null }
        }
        gate.attach()
        lifecycle.send(Lifecycle.Event.ON_PAUSE)
        assertFalse(playing)
        assertNull(owner)
        lifecycle.send(Lifecycle.Event.ON_RESUME)
        owner = "profile"
        playing = true
        gate.dispose()
        assertTrue(playing)
        assertEquals("profile", owner)
    }

    @Test fun `resume never replaces live content warning consent with cached media`() {
        val lifecycle = EntryLifecycle(Lifecycle.State.RESUMED)
        val gate = NavigationPlaybackGate(lifecycle) {}
        val video = VideoRenderModel("https://example.invalid/sensitive.mp4", 1f, null, null, null)
        val cached = mapOf("row" to listOf(video))
        val grants = mutableListOf(VideoPlaybackRegistration("row", listOf(video)))
        fun permittedUrl() = if (gate.canPlay) {
            resolveSelectedVideoUrl(permittedVideoModels(cached, grants)["row"].orEmpty(), null)
        } else null
        gate.attach()
        assertEquals(video.videoUrl, permittedUrl())
        lifecycle.send(Lifecycle.Event.ON_PAUSE)
        assertNull(permittedUrl())
        grants.clear()
        lifecycle.send(Lifecycle.Event.ON_RESUME)
        assertTrue(gate.canPlay)
        assertNull(permittedUrl())
    }

    @Test fun `cover cancels delayed confirmation and a cancelled preview cannot restart it`() = runTest {
        Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            val lifecycle = EntryLifecycle(Lifecycle.State.STARTED)
            val gate = NavigationPlaybackGate(lifecycle) {}
            gate.attach()
            var activations = 0
            val job = launch {
                lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    delay(VideoPlaybackScope.ACTIVATION_CONFIRMATION_MS)
                    if (gate.canPlay) activations++
                }
            }
            runCurrent()
            advanceTimeBy(1_000)
            assertEquals(0, activations)
            lifecycle.send(Lifecycle.Event.ON_RESUME)
            runCurrent()
            advanceTimeBy(200)
            lifecycle.send(Lifecycle.Event.ON_PAUSE)
            runCurrent()
            advanceTimeBy(1_000)
            assertEquals(0, activations)
            lifecycle.send(Lifecycle.Event.ON_RESUME)
            runCurrent()
            advanceTimeBy(VideoPlaybackScope.ACTIVATION_CONFIRMATION_MS)
            runCurrent()
            assertEquals(1, activations)
            gate.dispose()
            job.cancel()
            runCurrent()
            assertEquals(0, lifecycle.observerCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class EntryLifecycle(var state: State) : Lifecycle(), LifecycleOwner {
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
