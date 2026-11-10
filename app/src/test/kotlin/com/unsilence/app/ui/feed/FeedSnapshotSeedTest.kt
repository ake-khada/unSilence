package com.unsilence.app.ui.feed

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.init.InitGate
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import com.unsilence.app.data.relay.toNostrEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.FilterInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class FeedSnapshotSeedTest {
    @get:Rule val temp = TemporaryFolder()
    private val owner = "a".repeat(64)
    private val author = "b".repeat(64)
    private val now = System.currentTimeMillis() / 1_000
    private fun store() = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider()).apply {
        ownPubkey = owner
    }
    private fun event(index: Int, kind: Int = 1, tags: List<List<String>> = emptyList()) =
        EventDto(index.toString(16).padStart(64, '0'), if (kind == 3) owner else author,
            kind, "fixture $index", now - index, tags, "c".repeat(128)).toNostrEvent("wss://fixture.example")

    @Test fun `partial 19 row restore cannot seed initial Following`() = exercise(19, 0)
    @Test fun `partial 21 row restore cannot consume completion allowance`() = exercise(21, 0)
    @Test fun `19 live rows still receive the completed cache below ceiling`() = exercise(19, 19)
    @Test fun `20 live rows keep existing cache skip rule at ceiling`() = exercise(19, 20)
    @Test fun `large partial cache with 19 live rows still receives complete seed`() = exercise(21, 19)
    @Test fun `large partial cache with 20 live rows does not reload from MES`() = exercise(21, 20)
    @Test fun `saved feed waits for complete restore and seeds once`() = exercise(21, 0, saved = true)

    private fun exercise(partialSize: Int, liveCount: Int, saved: Boolean = false) = runTest {
        val source = store()
        source.insert(event(500, 3, listOf(listOf("p", author))))
        val cached = (1..40).map { event(it) }
        cached.forEach { source.insert(it) }
        val file = temp.newFile()
        file.outputStream().use { source.saveSnapshotBinary(it) }
        val destination = store()
        val gate = InitGate()
        val session = gate.beginSession(owner)
        var timeline = FeedTimeline()
        var initial: List<NostrEvent>? = null
        var cacheReads = 0
        var savedSeedPending = saved
        val load = {
            cacheReads++
            destination.eventsByAuthors(setOf(author), setOf(1))
        }
        val completion = launch {
            destination.snapshotRestoredFlow.filter { it > 0 }.first()
            timeline = timeline.merge(completedSnapshotCache(timeline.events.size, savedSeedPending, load))
        }
        val selection = launch {
            if (saved) gate.awaitSnapshot() else gate.awaitInitialFollows()
            // Production initial/reset cache stage. Selection is allowed before full restore.
            initial = initialFeedCache(gate.snapshotReady || destination.snapshotRestoredFlow.value > 0, load)
            timeline = FeedTimeline(events = initial!!)
            savedSeedPending = false
        }
        runCurrent()
        var checkedPartial = false
        val live = (100..<(100 + liveCount)).map { event(it) }
        val input = object : FilterInputStream(file.inputStream()) {
            fun beforeRead() {
                if (checkedPartial || destination.eventsByAuthors(setOf(author), setOf(1)).size < partialSize) return
                checkedPartial = true
                assertFalse(gate.snapshotReady)
                assertEquals(0L, destination.snapshotRestoredFlow.value)
                // Hold the real binary reader at a partially restored events section,
                // then run the initial subscription's seed and incoming relay rows.
                runCurrent()
                if (saved) {
                    assertFalse(selection.isCompleted)
                    assertNull(initial)
                } else {
                    assertTrue(selection.isCompleted)
                    assertEquals("No partial event section in first projection", emptyList<NostrEvent>(), initial)
                }
                assertEquals("No cache construction while restore is pending", 0, cacheReads)
                live.forEach { destination.insert(it) }
                timeline = timeline.merge(live)
                assertEquals(live.map { it.id }.toSet(), timeline.events.map { it.id }.toSet())
            }
            override fun read(): Int { beforeRead(); return `in`.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                beforeRead()
                return `in`.read(b, off, len)
            }
        }
        DataInputStream(input).use { stream ->
            destination.restoreSnapshotBinary(stream) { outcome ->
                gate.signalSnapshotFollows(session, outcome)
                // Selection stays queued until the events section checkpoint above.
            }
        }
        assertTrue("Fixture really paused partway through events", checkedPartial)
        // MES's completion signal precedes InitGate's full-restore signal in bootstrap.
        runCurrent()
        gate.signalSnapshotReady(session)
        runCurrent()
        assertTrue(completion.isCompleted)
        assertTrue(selection.isCompleted)
        val expected = if (liveCount < 20) cached + live else live
        assertEquals(expected.map { it.id }.toSet(), timeline.events.map { it.id }.toSet())
        assertEquals(expected.size, timeline.events.size)
        assertEquals(if (liveCount < 20) 1 else 0, cacheReads)
        if (saved) assertEquals(cached.map { it.id }.toSet(), initial!!.map { it.id }.toSet())
    }

    @Test fun `settled missing or rejected snapshot permits subsequent user reset cache read`() {
        var reads = 0
        assertEquals(emptyList<NostrEvent>(), initialFeedCache(snapshotReady = true) { reads++; emptyList() })
        assertEquals(1, reads)
    }
}
