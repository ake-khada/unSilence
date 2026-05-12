package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import androidx.core.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Invariant tests for SnapshotScheduler save/restore behavior.
 *
 * Tests use a real MemoryEventStore and a real AtomicFile backed by a temp
 * directory. ProcessLifecycleOwner is not exercised — these tests verify
 * the save/restore/concurrency logic, not lifecycle wiring.
 */
class SnapshotSchedulerInvariantsTest {

    private lateinit var tmpDir: File
    private lateinit var store: MemoryEventStore
    private lateinit var scheduler: SnapshotScheduler

    @Before
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "snapshot-test-${System.nanoTime()}")
        tmpDir.mkdirs()
        store = MemoryEventStore(object : MuteKeyProvider {})
        scheduler = SnapshotScheduler(store, AtomicFile(File(tmpDir, "test.snapshot")))
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun event(
        id: String,
        pubkey: String = "pk-default",
        kind: Int = 1,
        content: String = "test content",
        createdAt: Long = 1700000000L,
        tags: List<List<String>> = emptyList(),
        relayUrl: String = "wss://relay.example.com",
        replyToId: String? = null,
        rootId: String? = null,
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        kind = kind,
        content = content,
        createdAt = createdAt,
        tags = tags,
        tagsJson = "[]",
        sig = "sig",
        relayUrl = relayUrl,
        replyToId = replyToId,
        rootId = rootId,
        hasContentWarning = false,
        contentWarningReason = null,
        firstSeenAt = System.currentTimeMillis(),
        relaysSeen = mutableSetOf(relayUrl),
    )

    // ── Test 1: Missing file — restoreIfPresent is a no-op ─────────────────

    @Test
    fun `restoreIfPresent with no snapshot file is a no-op`() = runTest {
        // No snapshot saved — file does not exist
        scheduler.restoreIfPresent()

        // Store should remain empty
        assertTrue(store.eventsByIds(setOf("anything")).isEmpty())
        assertNull(store.getProfile("anything"))
    }

    // ── Test 2: Valid round-trip via saveNow + restoreIfPresent ─────────────

    @Test
    fun `saveNow then restoreIfPresent restores all events and aggregates`() = runTest {
        // Populate store
        store.insert(event(id = "sched-1", kind = 1, createdAt = 100))
        store.insert(event(id = "sched-2", kind = 1, createdAt = 101, replyToId = "sched-1"))
        store.insert(
            event(id = "sched-profile", pubkey = "sched-pk", kind = 0,
                content = """{"name":"scheduler-test"}""", createdAt = 200),
        )
        store.insert(
            event(id = "sched-follows", pubkey = "sched-pk", kind = 3,
                tags = listOf(listOf("p", "f1"), listOf("p", "f2")), createdAt = 201),
        )
        store.insert(
            event(id = "sched-reaction", kind = 7,
                tags = listOf(listOf("e", "sched-1")), createdAt = 102),
        )

        // Save
        scheduler.saveNow()

        // Restore into fresh store + scheduler
        val restoredStore = MemoryEventStore(object : MuteKeyProvider {})
        val restoredScheduler = SnapshotScheduler(
            restoredStore, AtomicFile(File(tmpDir, "test.snapshot")),
        )
        restoredScheduler.restoreIfPresent()

        // Verify events (kind-3 is persisted in ---FOLLOWS--- section, not eventsById)
        assertEquals(4, restoredStore.eventsByIds(
            setOf("sched-1", "sched-2", "sched-profile", "sched-reaction"),
        ).size)

        // Verify aggregates
        assertEquals(1, restoredStore.replyCount("sched-1"))
        assertEquals(1, restoredStore.reactionCount("sched-1"))

        // Verify profile
        val profile = restoredStore.getProfile("sched-pk")
        assertNotNull(profile)
        assertTrue(profile!!.content.contains("scheduler-test"))

        // Verify follows
        assertEquals(setOf("f1", "f2"), restoredStore.getFollows("sched-pk"))
    }

    // ── Test 3: saveNow creates a valid file on disk ───────────────────────

    @Test
    fun `saveNow writes snapshot file to disk`() = runTest {
        store.insert(event(id = "disk-1", kind = 1, createdAt = 100))

        scheduler.saveNow()

        val snapshotFile = File(tmpDir, "test.snapshot")
        assertTrue("Snapshot file should exist", snapshotFile.exists())
        assertTrue("Snapshot file should be non-empty", snapshotFile.length() > 0)

        // V3 binary format — first 4 bytes are the "USNS" magic.
        val magic = snapshotFile.inputStream().use { input ->
            val buf = ByteArray(4)
            val n = input.read(buf)
            assertEquals("Snapshot file should have at least 4 bytes", 4, n)
            String(buf, Charsets.US_ASCII)
        }
        assertEquals("USNS", magic)
    }

    // ── Test 4: Corrupt file does not crash, store stays empty ─────────────

    @Test
    fun `restoreIfPresent with corrupt file does not crash`() = runTest {
        // Write garbage to the snapshot file
        val snapshotFile = File(tmpDir, "test.snapshot")
        snapshotFile.writeText("SNAPSHOT_V1\nthis\tis\tnot\ta\tvalid\tevent\tline\n")

        scheduler.restoreIfPresent()

        // Store should remain empty — malformed lines are skipped
        assertTrue(store.eventsByIds(setOf("anything")).isEmpty())
    }

    // ── Test 5: Concurrent saves don't corrupt ─────────────────────────────

    @Test
    fun `concurrent saveNow calls produce valid snapshot`() = runTest {
        // Populate store with enough data to make saves non-trivial
        for (i in 1..500) {
            store.insert(event(id = "conc-$i", kind = 1, createdAt = i.toLong()))
        }

        // Launch 10 concurrent saves
        val jobs = (1..10).map {
            launch { scheduler.saveNow() }
        }
        jobs.forEach { it.join() }

        // Restore and verify — must be a valid snapshot regardless of ordering
        val restoredStore = MemoryEventStore(object : MuteKeyProvider {})
        val restoredScheduler = SnapshotScheduler(
            restoredStore, AtomicFile(File(tmpDir, "test.snapshot")),
        )
        restoredScheduler.restoreIfPresent()

        // All 500 events should be present (Mutex serializes saves,
        // each save writes the full store, so the last one wins)
        assertEquals(500, restoredStore.eventsByIds(
            (1..500).map { "conc-$it" }.toSet(),
        ).size)
    }

    // ── Test 6: Interrupted write preserves previous snapshot ───────────────

    @Test
    fun `AtomicFile preserves previous snapshot on write failure`() = runTest {
        // Save a valid snapshot first
        store.insert(event(id = "safe-1", kind = 1, createdAt = 100))
        scheduler.saveNow()

        // Simulate a failed write: start a write, write partial data, then fail it
        val atomicFile = AtomicFile(File(tmpDir, "test.snapshot"))
        val stream = atomicFile.startWrite()
        stream.write("SNAPSHOT_V1\ncorrupted partial data".toByteArray())
        atomicFile.failWrite(stream)

        // Restore — should get the original valid snapshot, not the partial write
        val restoredStore = MemoryEventStore(object : MuteKeyProvider {})
        val restoredScheduler = SnapshotScheduler(
            restoredStore, AtomicFile(File(tmpDir, "test.snapshot")),
        )
        restoredScheduler.restoreIfPresent()

        assertEquals(1, restoredStore.eventsByIds(setOf("safe-1")).size)
    }

    // ── Test 7: scheduler is idle before ON_START ────────────────────────

    @Test
    fun `scheduler is idle before ON_START fires`() {
        // After construction, periodicJob should be null — no eager scheduling.
        // attach() registers the lifecycle observer but does NOT start periodic saves.
        // Only ON_START (which we don't fire in this test) should start it.
        assertNull(
            "periodicJob should be null before ON_START fires",
            scheduler.periodicJob,
        )
    }

    // ── Test 8: Concurrent modification during save must not corrupt file ──
    //
    // Production bug: saveSnapshotBinary did `writeInt(chm.size)` followed by
    // `for (e in chm) ...` for six ConcurrentHashMaps (replyCounts,
    // repostCounts, reactionCounts, zapStatsByEventId, trustScoresByUrl,
    // relayMonitorsByUrl). CHM iteration is weakly-consistent with respect
    // to .size — a concurrent insert mid-iteration can produce more entries
    // than the count we just wrote, leaving the reader misaligned. Field log
    // showed `IOException: Invalid string length: 1631139890` on restore.
    //
    // Fix: snapshot each CHM to an immutable map BEFORE writing the count
    // and iterating. This test exercises the race by mutating the maps
    // while save is running. Without the fix, this fails randomly within a
    // few iterations. With the fix, the file always round-trips.

    @Test
    fun `saveSnapshotBinary survives concurrent modification of aggregates`() = runTest {
        // Populate the store with enough data to make the save non-trivial.
        for (i in 1..100) {
            store.insert(event(id = "ev-$i", kind = 1, createdAt = i.toLong()))
            // Reactions populate reactionCounts + zapStatsByEventId paths.
            store.insert(
                event(
                    id = "react-$i",
                    pubkey = "actor-$i",
                    kind = 7,
                    tags = listOf(listOf("e", "ev-$i")),
                    createdAt = (i + 1000).toLong(),
                ),
            )
        }

        // Repeat the race a handful of times. With the bug present, even
        // one of these typically fails — the writer's iteration count
        // diverges from the pre-recorded size.
        repeat(20) { iter ->
            val bytes = ByteArrayOutputStream()
            // Save and concurrently insert more reactions / events. Both
            // jobs run on the IO dispatcher to allow real concurrency.
            val saveJob = async(Dispatchers.IO) {
                DataOutputStream(bytes).use { out ->
                    store.saveSnapshotBinary(out)
                }
            }
            val mutateJob = launch(Dispatchers.IO) {
                for (i in 0 until 200) {
                    val seed = iter * 1000 + i
                    store.insert(
                        event(
                            id = "concurrent-$seed",
                            kind = 7,
                            pubkey = "actor-c-$seed",
                            tags = listOf(listOf("e", "ev-${seed % 100 + 1}")),
                            createdAt = (10_000 + seed).toLong(),
                        ),
                    )
                }
            }
            saveJob.await()
            mutateJob.join()

            // Restore from the bytes produced during the race. With the fix,
            // this must always succeed — the snapshot is internally
            // consistent regardless of what was added during the save.
            val restored = MemoryEventStore(object : MuteKeyProvider {})
            withContext(Dispatchers.IO) {
                DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use { input ->
                    restored.restoreSnapshotBinary(input)
                }
            }
            // Sanity: at minimum, the events written before the save started
            // should round-trip. (Concurrent inserts may or may not appear,
            // depending on iteration timing — that's allowed.)
            assertTrue(
                "iter=$iter: round-trip should preserve at least the pre-save events",
                restored.eventsByIds(setOf("ev-1", "ev-50", "ev-100")).size == 3,
            )
        }
    }
}
