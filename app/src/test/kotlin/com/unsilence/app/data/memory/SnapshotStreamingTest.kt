package com.unsilence.app.data.memory

import androidx.core.util.AtomicFile
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

class SnapshotStreamingTest {
    @get:Rule val temp = TemporaryFolder()

    private fun store() = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())

    private fun event(id: String, content: String = "hello") = NostrEvent(
        id = id,
        pubkey = "author",
        kind = 1,
        content = content,
        createdAt = 1_700_000_000L,
        tags = emptyList(),
        sig = "sig",
        relayUrl = "wss://relay.example",
        replyToId = null,
        rootId = null,
        hasContentWarning = false,
        contentWarningReason = null,
        firstSeenAt = System.currentTimeMillis(),
        relaysSeen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
            add("wss://relay.example")
        },
    )

    @Test
    fun `empty V16 through V19 retain exact wire bytes and owner offset convention`() = runTest {
        for (version in 16..19) {
            val file = temp.newFile("v$version.bin")
            val owner = "owner-🌻"
            val source = store().apply { ownPubkey = owner }
            val sizes = file.outputStream().use { source.saveSnapshotBinary(it, version) }
            val followsBytes = if (version >= 17) 17 else 16
            val expected = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeBytes("USNS")
                    out.writeInt(version)
                    out.writeInt(32)
                    out.writeInt(32 + followsBytes)
                    out.writeInt(32 + followsBytes + 4)
                    out.writeInt(32 + followsBytes + 4 + 40)
                    out.writeInt(0)
                    out.writeInt(32 + followsBytes + 4 + 40 + 8)
                    val ownerBytes = owner.toByteArray(Charsets.UTF_8)
                    out.writeInt(ownerBytes.size)
                    out.write(ownerBytes)
                    repeat(4) { out.writeInt(0) } // follows + three own engaged sets
                    if (version >= 17) out.writeBoolean(false) // own raw kind-3
                    out.writeInt(0) // events
                    repeat(10) { out.writeInt(0) } // aggregate slots
                    repeat(2) { out.writeInt(0) } // relay health
                    out.writeInt(0) // timelines
                    repeat(2) { out.writeInt(0) } // anons + relay identities
                    if (version >= 18) out.writeInt(0) // pending mute journal
                    if (version >= 19) out.writeInt(0) // NIP-05 cache
                }
            }.toByteArray()
            assertArrayEquals("V$version bytes", expected, file.readBytes())
            assertEquals(expected.size, sizes.totalBytes)
            assertEquals(32 + 4 + owner.toByteArray(Charsets.UTF_8).size, sizes.headerBytes)
            assertEquals(followsBytes, sizes.followsBytes)
            assertEquals(4, sizes.eventsBytes)
            assertEquals(40, sizes.aggregatesBytes)
            assertEquals(8, sizes.relayHealthBytes)
            assertEquals(4, sizes.timelinesBytes)
            assertEquals(8 + (if (version >= 18) 4 else 0) + (if (version >= 19) 4 else 0), sizes.tailBytes)
        }
    }

    @Test
    fun `V16 through V19 write an empty legacy JSON slot and round trip binary tags`() = runTest {
        for (version in 16..19) {
            for (tags in listOf(emptyList(), snapshotTags)) {
                val original = taggedEvent().copy(tags = tags)
                val source = store().apply { insert(original) }
                val file = temp.newFile()
                val sizes = file.outputStream().use { source.saveSnapshotBinary(it, version) }
                val bytes = file.readBytes()
                assertEquals(version, ByteBuffer.wrap(bytes).getInt(4))
                val eventsOffset = sizes.headerBytes + sizes.followsBytes
                val expected = eventSection(original, legacyJson = "")
                assertArrayEquals(
                    "V$version event fields and empty compatibility slot",
                    expected,
                    bytes.copyOfRange(eventsOffset, eventsOffset + sizes.eventsBytes),
                )
                assertEquals(bytes.size, sizes.totalBytes)
                assertRestoredEvent(bytes, original)
            }
        }
    }

    @Test
    fun `legacy filled JSON slot restores binary tags and subsequent fields`() = runTest {
        for (version in 16..19) {
            val original = taggedEvent()
            val file = temp.newFile()
            val sizes = file.outputStream().use { store().saveSnapshotBinary(it, version) }
            val empty = file.readBytes()
            val eventsOffset = sizes.headerBytes + sizes.followsBytes
            assertEquals(Int.SIZE_BYTES, sizes.eventsBytes)
            // Independently encode the old event layout, not the production event writer.
            val legacyEvents = eventSection(original, legacyJson = tagsToJson(original.tags))
            val bytes = empty.copyOfRange(0, eventsOffset) + legacyEvents +
                empty.copyOfRange(eventsOffset + sizes.eventsBytes, empty.size)
            val header = ByteBuffer.wrap(bytes)
            val addedBytes = legacyEvents.size - sizes.eventsBytes
            for (field in listOf(16, 20, 28)) { // aggregates, relay health, timelines
                header.putInt(field, header.getInt(field) + addedBytes)
            }
            header.putInt(24, 1) // declared event count
            assertRestoredEvent(bytes, original)
        }
    }

    private val snapshotTags = listOf(
        emptyList(),
        listOf(""),
        listOf("e", "parent", "", "reply"),
        listOf("e", "root", "wss://relay.example", "root"),
        listOf("content-warning", "reason"),
        listOf("opaque", "quotes \" and \\ slashes", "\u0000\t\r\n", "日本語 🌻"),
    )

    private fun taggedEvent() = event("tagged", "unchanged content 🌻").copy(
        tags = snapshotTags,
        replyToId = "parent",
        rootId = "root",
        hasContentWarning = true,
        contentWarningReason = "reason",
    )

    /** Frozen event layout shared by V16–V19, including all four optional fields. */
    private fun eventSection(event: NostrEvent, legacyJson: String): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                fun string(value: String) {
                    val encoded = value.toByteArray(Charsets.UTF_8)
                    out.writeInt(encoded.size)
                    out.write(encoded)
                }
                out.writeInt(1) // one event
                out.writeLong(event.createdAt)
                out.writeInt(event.kind)
                listOf(event.id, event.pubkey, event.sig, event.content, event.relayUrl).forEach(::string)
                out.writeInt(event.tags.size)
                for (tag in event.tags) {
                    out.writeInt(tag.size)
                    tag.forEach(::string)
                }
                string(legacyJson)
                out.writeByte(0x0f)
                string(requireNotNull(event.replyToId))
                string(requireNotNull(event.rootId))
                string(requireNotNull(event.contentWarningReason))
                out.writeLong(event.firstSeenAt)
                out.writeInt(event.relaysSeen.size)
                event.relaysSeen.forEach(::string)
            }
        }.toByteArray()

    private suspend fun assertRestoredEvent(bytes: ByteArray, original: NostrEvent) {
        val restored = store()
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            restored.restoreSnapshotBinary(input)
            assertEquals("All sections consumed", -1, input.read())
        }
        assertEquals(original, restored.getNostrEvent(original.id))
        // UI entities still receive JSON built from the authoritative binary tags.
        assertEquals(tagsToJson(original.tags), restored.getEventEntity(original.id)?.tags)
    }

    @Test
    fun `snapshot beyond 64 MiB streams bounded writes and round trips`() = runTest {
        val source = store()
        // Share input content: this fixture is large on disk, not a 70 MiB input array.
        val content = "x".repeat(64 * 1024)
        repeat(1_100) { source.insert(event("large-$it", content)) }
        val file = temp.newFile("large.bin")
        var bytesWritten = 0L
        var largestWrite = 0
        val sizes = object : FileOutputStream(file) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                assertTrue("No section-sized array may reach the file", length <= 64 * 1024)
                largestWrite = maxOf(largestWrite, length)
                bytesWritten += length
                super.write(bytes, offset, length)
            }
        }.use { source.saveSnapshotBinary(it) }

        assertTrue(sizes.eventsBytes > 64 * 1024 * 1024)
        assertEquals(1_100, sizes.eventCount)
        assertEquals(file.length(), sizes.totalBytes.toLong())
        assertEquals("Each section is written only once", file.length(), bytesWritten)
        assertEquals(64 * 1024, largestWrite)
        val restored = store()
        DataInputStream(file.inputStream().buffered()).use { restored.restoreSnapshotBinary(it) }
        assertEquals(1_100, restored.eventsByIds((0 until 1_100).mapTo(HashSet()) { "large-$it" }).size)
        assertEquals(content, restored.getNostrEvent("large-1099")?.content)
    }

    @Test
    fun `header patch flushes pending bytes without closing or moving the file cursor`() = runTest {
        val file = temp.newFile()
        file.outputStream().use { stream ->
            stream.write(byteArrayOf(99))
            val writer = SnapshotOutput(stream, currentCoroutineContext())
            writer.data.writeInt(0) // reserve test header
            writer.data.writeInt(42) // deliberately remains buffered
            writer.finish(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)))
            assertTrue(stream.channel.isOpen)
            assertEquals(9L, stream.channel.position())
            stream.write(77) // caller, not wrapper, still owns the open stream
        }
        assertArrayEquals(byteArrayOf(99, 1, 2, 3, 4, 0, 0, 0, 42, 77), file.readBytes())
    }

    @Test
    fun `body IO failure preserves previous snapshot and permits retry`() = runTest {
        verifyFailure(Failure.BODY_IO)
    }

    @Test
    fun `body allocation Error preserves previous snapshot and permits retry`() = runTest {
        verifyFailure(Failure.BODY_OOM)
    }

    @Test
    fun `header patch failure preserves previous snapshot and permits retry`() = runTest {
        verifyFailure(Failure.HEADER_IO)
    }

    @Test
    fun `cancellation during body write preserves previous snapshot and permits retry`() = runTest {
        verifyFailure(Failure.CANCEL_BODY)
    }

    @Test
    fun `cancellation after final flush cannot commit an incomplete header`() = runTest {
        verifyFailure(Failure.CANCEL_HEADER)
    }

    @Test
    fun `stop deadline during serialization preserves the last committed snapshot`() = runTest {
        verifyStopDeadline(Failure.SLOW_BODY, SnapshotStopOutcome.TIMED_OUT)
    }

    @Test
    fun `stop deadline during atomic finish reports the commit honestly`() = runTest {
        verifyStopDeadline(Failure.SLOW_COMMIT, SnapshotStopOutcome.COMMITTED_AFTER_DEADLINE)
    }

    private suspend fun verifyStopDeadline(failure: Failure, expected: SnapshotStopOutcome) {
        val file = temp.newFile()
        val atomic = FaultingAtomicFile(file)
        val source = store().apply { insert(event("previous")) }
        val scheduler = SnapshotScheduler(source, atomic)
        scheduler.saveNow()
        scheduler.restoreIfPresent()
        val previous = file.readBytes()
        source.insert(event("new"))
        atomic.failure = failure
        // A real dispatcher gives withTimeout a wall clock, not runTest's virtual clock.
        val result = withContext(Dispatchers.Default) { scheduler.saveBeforeStopDeadline(100) }
        assertEquals(expected, result)
        if (expected == SnapshotStopOutcome.TIMED_OUT) {
            assertEquals(1, atomic.commits)
            assertEquals(1, atomic.failures)
            assertArrayEquals(previous, file.readBytes())
        } else {
            assertEquals(2, atomic.commits)
            assertEquals(0, atomic.failures)
            val restored = store()
            DataInputStream(atomic.openRead()).use { restored.restoreSnapshotBinary(it) }
            assertNotNull(restored.getNostrEvent("new"))
        }
        atomic.failure = null
        val retry = withContext(Dispatchers.Default) { scheduler.saveBeforeStopDeadline() }
        assertEquals(SnapshotStopOutcome.COMPLETED, retry)
    }

    private suspend fun verifyFailure(failure: Failure) {
        val file = temp.newFile()
        val atomic = FaultingAtomicFile(file)
        val source = store().apply { insert(event("previous")) }
        val scheduler = SnapshotScheduler(source, atomic)
        scheduler.saveNow()
        val previous = file.readBytes()
        source.insert(event("new", "new content".repeat(2_000)))
        atomic.failure = failure
        val cancellationExpected = failure == Failure.CANCEL_BODY || failure == Failure.CANCEL_HEADER
        kotlinx.coroutines.coroutineScope {
            val saving = launch {
                atomic.savingJob = currentCoroutineContext()[Job]
                scheduler.saveNow()
            }
            saving.join()
            assertEquals(cancellationExpected, saving.isCancelled)
        }
        assertEquals(1, atomic.failures)
        assertEquals(1, atomic.commits)
        assertArrayEquals(previous, file.readBytes())
        assertFalse(File(file.path + ".new").exists())

        atomic.failure = null
        scheduler.saveNow()
        assertEquals(2, atomic.commits)
        val restored = store()
        DataInputStream(atomic.openRead()).use { restored.restoreSnapshotBinary(it) }
        assertNotNull(restored.getNostrEvent("previous"))
        assertNotNull(restored.getNostrEvent("new"))
    }

    private enum class Failure { BODY_IO, BODY_OOM, HEADER_IO, CANCEL_BODY, CANCEL_HEADER, SLOW_BODY, SLOW_COMMIT }

    private class FaultingAtomicFile(base: File) : AtomicFile(base) {
        var failure: Failure? = null
        var savingJob: Job? = null
        var failures = 0
        var commits = 0

        override fun startWrite(): FileOutputStream {
            val stream = super.startWrite()
            if (failure == null) return stream
            stream.close()
            // AndroidX AtomicFile stages writes in the .new sibling.
            val staging = File(baseFile.path + ".new")
            check(staging.exists())
            return object : FileOutputStream(staging) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (failure == Failure.SLOW_BODY) Thread.sleep(300)
                    if (failure == Failure.BODY_IO || failure == Failure.BODY_OOM) {
                        super.write(bytes, offset, minOf(length, 32))
                        if (failure == Failure.BODY_OOM) throw OutOfMemoryError("injected allocation failure")
                        throw IOException("injected body write failure")
                    }
                    super.write(bytes, offset, length)
                    if (failure == Failure.CANCEL_BODY) savingJob!!.cancel()
                }

                override fun flush() {
                    super.flush()
                    if (failure == Failure.HEADER_IO) channel.close()
                    if (failure == Failure.CANCEL_HEADER) savingJob!!.cancel()
                }
            }
        }

        override fun failWrite(stream: FileOutputStream?) {
            failures++
            super.failWrite(stream)
        }

        override fun finishWrite(stream: FileOutputStream?) {
            if (failure == Failure.SLOW_COMMIT) Thread.sleep(300)
            commits++
            super.finishWrite(stream)
        }
    }
}
