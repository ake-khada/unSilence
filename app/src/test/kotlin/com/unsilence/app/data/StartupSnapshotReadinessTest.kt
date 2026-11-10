package com.unsilence.app.data

import com.unsilence.app.data.init.InitGate
import androidx.core.util.AtomicFile
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.SnapshotScheduler
import com.unsilence.app.data.memory.SnapshotFollowsOutcome
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.SnapshotRestoreOutcome
import com.unsilence.app.data.relay.AccountMetadataFetchResult
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import com.unsilence.app.data.relay.toNostrEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class StartupSnapshotReadinessTest {
    @get:Rule val temp = TemporaryFolder()
    private val owner = "a".repeat(64)
    private val followed = "b".repeat(64)

    @Test fun `fresh restore finishes without waiting for an unavailable indexer`() = runTest {
        var requests = 0
        val bootstrap = launch {
            bootstrapAccountMetadata(backgroundScope, true, { delay(5_000) }, {
                delay(500)
                SnapshotRestoreOutcome.RESTORED
            }, {}, { true }, { requests++; AccountMetadataFetchResult() })
        }
        runCurrent()
        advanceTimeBy(499)
        runCurrent()
        assertFalse(bootstrap.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue("The unused network readiness waiter must not hold bootstrap", bootstrap.isCompleted)
        assertEquals(0, requests)
    }

    @Test fun `stale snapshot cannot hold discovery behind a suspended restore`() = runTest {
        val release = CompletableDeferred<Unit>()
        var requests = 0
        var restored = false
        val bootstrap = launch {
            bootstrapAccountMetadata(backgroundScope, false, {}, {
                release.await()
                restored = true
                SnapshotRestoreOutcome.RESTORED
            }, {}, { false }, { requests++; AccountMetadataFetchResult() })
        }
        runCurrent()
        assertEquals("Stale metadata must discover while restore is still suspended", 1, requests)
        assertTrue(bootstrap.isCompleted)
        assertFalse(restored)
        release.complete(Unit)
        runCurrent()
        assertTrue("Discovery must not cancel the local restore", restored)
    }

    @Test fun `slow fresh restore falls back within connection deadline and later completes normally`() = runTest {
        val release = CompletableDeferred<Unit>()
        val gate = InitGate()
        val session = gate.beginSession(owner)
        var requests = 0
        var outcome: SnapshotRestoreOutcome? = null
        val bootstrap = launch {
            bootstrapAccountMetadata(backgroundScope, true, { delay(5_000) }, {
                gate.signalSnapshotFollows(session, SnapshotFollowsOutcome.NONEMPTY)
                release.await()
                outcome = SnapshotRestoreOutcome.RESTORED
                gate.signalSnapshotReady(session)
                SnapshotRestoreOutcome.RESTORED
            }, {}, { true }, { requests++; AccountMetadataFetchResult() })
        }
        runCurrent()
        advanceTimeBy(4_999)
        assertEquals(0, requests)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("Restore and indexer waits must share one bounded window", 1, requests)
        assertTrue(bootstrap.isCompleted)
        assertNull("Timeout is not a rejected snapshot", outcome)
        assertFalse("Timeout is not full-restore readiness", gate.snapshotReady)
        release.complete(Unit)
        runCurrent()
        assertEquals(SnapshotRestoreOutcome.RESTORED, outcome)
        assertTrue(gate.snapshotReady)
        assertEquals("Late restore must not repeat discovery", 1, requests)
    }
    private fun store() = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider()).apply {
        ownPubkey = owner
    }
    private suspend fun snapshot(includeRelays: Boolean = true, follows: List<String> = listOf(followed)) = temp.newFile().also { file ->
        val source = store()
        source.insert(EventDto("1".repeat(64), owner, 3, "", 1_700_000_000,
            follows.map { listOf("p", it) }, "c".repeat(128)).toNostrEvent("wss://fixture.example"))
        if (includeRelays) source.insert(EventDto("2".repeat(64), owner, 10002, "", 1_700_000_001,
            listOf(listOf("r", "wss://own.example")), "c".repeat(128)).toNostrEvent("wss://fixture.example"))
        file.outputStream().use { source.saveSnapshotBinary(it) }
    }

    @Test fun `fresh local snapshot restores before unavailable indexer finishes waiting`() = runTest {
        val file = snapshot()
        val destination = store()
        launch {
            bootstrapAccountMetadata(this, snapshotFresh = true,
                connectIndexers = { delay(5_000) },
                restoreSnapshot = { DataInputStream(file.inputStream()).use { destination.restoreSnapshotBinary(it) }; SnapshotRestoreOutcome.RESTORED },
                prepareDiscovery = {},
                hasFreshMetadata = { destination.getFollows(owner)?.isNotEmpty() == true && destination.getReadWriteRelayConfigs(owner).isNotEmpty() },
                fetchMetadata = { AccountMetadataFetchResult() })
        }
        runCurrent()
        assertEquals("Local follows must not wait for the unavailable indexer", setOf(followed), destination.getFollows(owner))
    }

    @Test fun `fresh valid follows and relays skip account metadata request`() = runTest {
        val file = snapshot()
        val destination = store()
        var requests = 0
        launch {
            bootstrapAccountMetadata(this, snapshotFresh = true,
                connectIndexers = { delay(5_000) },
                restoreSnapshot = { DataInputStream(file.inputStream()).use { destination.restoreSnapshotBinary(it) }; SnapshotRestoreOutcome.RESTORED },
                prepareDiscovery = {},
                hasFreshMetadata = { destination.getFollows(owner)?.isNotEmpty() == true && destination.getReadWriteRelayConfigs(owner).isNotEmpty() },
                fetchMetadata = { requests++; AccountMetadataFetchResult() })
        }
        advanceUntilIdle()
        assertEquals(setOf(followed), destination.getFollows(owner))
        assertEquals(1, destination.getReadWriteRelayConfigs(owner).size)
        assertEquals("Fresh restored metadata needs no network discovery", 0, requests)
    }
    @Test fun `follows section releases local selection before any relay event restore`() = runTest {
        val file = snapshot()
        val destination = store()
        val gate = InitGate()
        val session = gate.beginSession(owner)
        val selection = launch { gate.awaitInitialFollows() }
        runCurrent()
        var observed: SnapshotFollowsOutcome? = null
        DataInputStream(file.inputStream()).use { input ->
            destination.restoreSnapshotBinary(input) { outcome ->
                observed = outcome
                gate.signalSnapshotFollows(session, outcome)
                runCurrent()
                assertTrue("Initial feed selection released before events", selection.isCompleted)
                assertFalse("Network discovery has not completed", gate.followsReady)
                assertFalse("Full restore has not completed", gate.snapshotReady)
                assertEquals(setOf(followed), destination.getFollows(owner))
                assertTrue("Relay metadata is in the later events section", destination.getReadWriteRelayConfigs(owner).isEmpty())
            }
        }
        assertEquals(SnapshotFollowsOutcome.NONEMPTY, observed)
        assertTrue(destination.getReadWriteRelayConfigs(owner).isNotEmpty())
    }

    @Test fun `missing relay metadata empty follows and stale snapshot still discover`() = runTest {
        for ((includeRelays, follows, fresh) in listOf(
            Triple(false, listOf(followed), true),
            Triple(true, emptyList(), true),
            Triple(true, listOf(followed), false),
        )) {
            val file = snapshot(includeRelays, follows)
            val destination = store()
            var requests = 0
            val phases = mutableListOf<String>()
            bootstrapAccountMetadata(this, snapshotFresh = fresh,
                connectIndexers = { phases += "network" },
                restoreSnapshot = {
                    DataInputStream(file.inputStream()).use { destination.restoreSnapshotBinary(it) { phases += "follows:$it" } }
                    SnapshotRestoreOutcome.RESTORED
                },
                prepareDiscovery = {},
                hasFreshMetadata = { fresh && destination.getFollows(owner)?.isNotEmpty() == true && destination.getReadWriteRelayConfigs(owner).isNotEmpty() },
                fetchMetadata = { phases += "request"; requests++; AccountMetadataFetchResult() })
            assertEquals(1, requests)
            if (fresh) assertTrue("Fresh local section settled before metadata request: $phases",
                phases.indexOfFirst { it.startsWith("follows:") } < phases.indexOf("request"))
        }
    }

    @Test fun `missing rejected and incomplete snapshots always retain discovery`() = runTest {
        for (outcome in listOf(SnapshotRestoreOutcome.MISSING, SnapshotRestoreOutcome.REJECTED)) {
            var requests = 0
            bootstrapAccountMetadata(this, true, {}, { outcome }, {}, { true }, { requests++; AccountMetadataFetchResult() })
            assertEquals("Freshness alone cannot skip discovery after $outcome", 1, requests)
        }
    }

    @Test fun `scheduler reports missing empty and rejected files without hanging`() = runTest {
        val missing = java.io.File(temp.root, "not-present")
        val rejected = temp.newFile().apply { writeText("invalid snapshot") }
        val empty = snapshot(follows = emptyList())
        for ((file, expectedFollows, expectedRestore) in listOf(
            Triple(missing, SnapshotFollowsOutcome.MISSING, SnapshotRestoreOutcome.MISSING),
            Triple(rejected, SnapshotFollowsOutcome.UNAVAILABLE, SnapshotRestoreOutcome.REJECTED),
            Triple(empty, SnapshotFollowsOutcome.EMPTY, SnapshotRestoreOutcome.RESTORED),
        )) {
            val observations = mutableListOf<SnapshotFollowsOutcome>()
            val scheduler = SnapshotScheduler(store(), AtomicFile(file))
            val result = scheduler.restoreIfPresent(observations::add)
            assertEquals(expectedRestore, result)
            assertEquals(listOf(expectedFollows), observations)
        }
    }

    @Test fun `follows ready is not proof of full restore success`() = runTest {
        val file = snapshot()
        // The complete follows section remains intact; the trailing event/tail is truncated.
        file.writeBytes(file.readBytes().dropLast(20).toByteArray())
        val destination = store()
        val observations = mutableListOf<SnapshotFollowsOutcome>()
        val result = SnapshotScheduler(destination, AtomicFile(file)).restoreIfPresent(observations::add)
        assertEquals(listOf(SnapshotFollowsOutcome.NONEMPTY), observations)
        assertEquals(SnapshotRestoreOutcome.REJECTED, result)
        assertNull("Partial restore must be quarantined and cleared", destination.getFollows(owner))
    }

    @Test fun `empty or unavailable local sections still await discovery and old sessions cannot release it`() = runTest {
        for (outcome in SnapshotFollowsOutcome.entries.filter { it != SnapshotFollowsOutcome.NONEMPTY }) {
            val gate = InitGate()
            val old = gate.beginSession("old")
            val session = gate.beginSession(owner)
            val selection = launch { gate.awaitInitialFollows() }
            runCurrent()
            gate.signalSnapshotFollows(old, SnapshotFollowsOutcome.NONEMPTY)
            runCurrent()
            assertFalse(selection.isCompleted)
            gate.signalSnapshotFollows(session, outcome)
            runCurrent()
            assertFalse(selection.isCompleted)
            gate.signalFollowsReady(session)
            runCurrent()
            assertTrue(selection.isCompleted)
        }
    }

    @Test fun `legacy reader returns rejection without throwing and accepts a complete text snapshot`() = runTest {
        for (text in listOf("", "SNAPSHOT_V99\nsome garbage\n")) {
            val destination = store()
            val observations = mutableListOf<SnapshotFollowsOutcome>()
            assertFalse(destination.restoreSnapshotFrom(text.reader().buffered(), observations::add))
            assertEquals(listOf(SnapshotFollowsOutcome.UNAVAILABLE), observations)
            assertNull(destination.getFollows(owner))
        }
        val source = store()
        DataInputStream(snapshot().inputStream()).use { source.restoreSnapshotBinary(it) }
        val text = java.io.StringWriter()
        text.buffered().use { source.saveSnapshotTo(it) }
        val destination = store()
        val observations = mutableListOf<SnapshotFollowsOutcome>()
        assertTrue(destination.restoreSnapshotFrom(text.toString().reader().buffered(), observations::add))
        assertEquals(listOf(SnapshotFollowsOutcome.NONEMPTY), observations)
        assertEquals(setOf(followed), destination.getFollows(owner))
        assertEquals(1, destination.getReadWriteRelayConfigs(owner).size)
    }

}
