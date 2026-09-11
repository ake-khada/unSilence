package com.unsilence.app.data.relay

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.auth.SignatureVerifier
import com.unsilence.app.data.memory.DELETION_TOMBSTONE_CAP
import com.unsilence.app.data.memory.MAX_DELETION_TARGETS
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Runs the production drainers; no synchronous substitute can expose their batch-boundary bugs. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventProcessorDrainerTest {
    private lateinit var store: MemoryEventStore
    private lateinit var processor: EventProcessor
    private val verificationCount = AtomicInteger()

    private suspend fun TestScope.startProcessor() {
        store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        // Isolate queue/routing behavior; SignatureVerifierTest covers real cryptographic verification.
        processor = EventProcessor(store, object : SignatureVerifier() {
            override fun verify(event: NostrEvent): Boolean {
                verificationCount.incrementAndGet()
                return true
            }
        })
        processor.setTestScope(backgroundScope)
        processor.start()
        // The standard test dispatcher holds all three real drainers until runCurrent().
        // backgroundScope cancels their infinite receive/timeout loops when the test ends.
    }

    @After
    fun tearDown() {
        if (::processor.isInitialized) processor.stop()
    }

    @Test
    fun `hot drainer retains all 101 queued events`() = assertBurstRetained(kind = 1, count = 101)

    @Test
    fun `cold drainer retains all 201 queued events`() = assertBurstRetained(kind = 7, count = 201)

    @Test
    fun `control drainer retains all 501 queued events`() = assertBurstRetained(kind = 10002, count = 501)

    private fun assertBurstRetained(kind: Int, count: Int) = runTest {
        startProcessor()
        val ids = (1..count).map(::eventId).toSet()
        for (seed in 1..count) processor.process(rawEvent(seed, kind), FIRST_RELAY)
        assertTrue("Events must be queued before the real drainer runs", store.eventsByIds(ids).isEmpty())

        runCurrent()

        val stored = store.eventsByIds(ids)
        assertEquals("Every queued event must reach MES", count, stored.size)
        assertEquals(ids, stored.map(NostrEvent::id).toSet())
    }

    @Test
    fun `full hot channel releases dedup so another relay can retry`() =
        assertOverflowRecoverable(kind = 1, capacity = 500)

    @Test
    fun `full cold channel releases dedup so another relay can retry`() =
        assertOverflowRecoverable(kind = 7, capacity = 500)

    @Test
    fun `full control channel releases dedup so another relay can retry`() =
        assertOverflowRecoverable(kind = 10002, capacity = 2000)

    private fun assertOverflowRecoverable(kind: Int, capacity: Int) = runTest {
        startProcessor()
        for (seed in 1..capacity) processor.process(rawEvent(seed, kind), FIRST_RELAY)
        val overflowSeed = capacity + 1
        val overflowId = eventId(overflowSeed)
        val overflow = rawEvent(overflowSeed, kind)

        processor.process(overflow, FIRST_RELAY)
        assertFalse("A failed enqueue must not retain the dedup reservation", processor.seenIds.containsKey(overflowId))
        runCurrent()
        assertNull("The full channel must have dropped this copy", store.getNostrEvent(overflowId))

        processor.process(overflow, SECOND_RELAY)
        assertTrue("The later copy must be accepted", processor.seenIds.containsKey(overflowId))
        runCurrent()

        val ids = (1..overflowSeed).map(::eventId).toSet()
        assertEquals(ids, store.eventsByIds(ids).map(NostrEvent::id).toSet())
        assertTrue(SECOND_RELAY in store.getNostrEvent(overflowId)!!.relaysSeen)
    }

    @Test
    fun `kind 5 arriving after its target removes the stored note`() = runTest {
        startProcessor()
        processor.process(rawEvent(1), FIRST_RELAY)
        runCurrent()
        assertNotNull(store.getNostrEvent(eventId(1)))

        processor.process(deletion(2, targetSeed = 1), SECOND_RELAY)
        runCurrent()

        assertNull("Remote deletion payloads must not be retained", store.getNostrEvent(eventId(2)))
        assertNull(store.getNostrEvent(eventId(1)))
        assertTrue(store.feedRowsByIds(setOf(eventId(1))).isEmpty())
    }

    @Test
    fun `kind 5 arriving before its target suppresses the later note`() = runTest {
        startProcessor()
        processor.process(deletion(2, targetSeed = 1), SECOND_RELAY)
        runCurrent()
        val target = processor.parseAndVerify(rawEvent(1), FIRST_RELAY)!!
        assertTrue("The control drainer must record the tombstone first", store.isDeleted(target))
        assertNull(store.getNostrEvent(eventId(2)))

        processor.process(rawEvent(1), FIRST_RELAY)
        runCurrent()

        assertNull(store.getNostrEvent(eventId(1)))
        assertTrue(store.feedRowsByIds(setOf(eventId(1))).isEmpty())
    }

    @Test
    fun `another author cannot delete a note through the control lane`() = runTest {
        startProcessor()
        processor.process(rawEvent(1), FIRST_RELAY)
        runCurrent()

        processor.process(rawEvent(2, kind = 5, tags = deletionTags(1)), SECOND_RELAY)
        runCurrent()

        assertNull(store.getNostrEvent(eventId(2)))
        assertNotNull(store.getNostrEvent(eventId(1)))
    }

    @Test
    fun `repeated deletion reaches the real drainer after tombstone eviction`() = runTest {
        startProcessor()
        val target = processor.parseAndVerify(rawEvent(1), FIRST_RELAY)!!
        val deletion = deletion(2, targetSeed = 1)
        processor.process(deletion, FIRST_RELAY)
        runCurrent()
        assertTrue(store.isDeleted(target))

        // Remote deletion payloads are not retained. Evict the compact tombstone,
        // then require the repeated request to restore it through the real drainer.
        (3..DELETION_TOMBSTONE_CAP + 2).chunked(MAX_DELETION_TARGETS).forEachIndexed { index, seeds ->
            val tags = seeds.joinToString(prefix = "[", postfix = "]") { """["e","${eventId(it)}"]""" }
            val request = rawEvent(100_000 + index, kind = 5, tags = tags)
            store.insert(processor.parseAndVerify(request, FIRST_RELAY)!!)
        }
        assertFalse(store.isDeleted(target))
        assertTrue(store.insert(target))

        processor.process(deletion, SECOND_RELAY)
        runCurrent()

        assertTrue(store.isDeleted(target))
        assertNull(store.getNostrEvent(target.id))
    }

    @Test
    fun `deletion payloads bypass verified cache and each arrival is verified`() = runTest {
        startProcessor()
        val deletion = deletion(2, targetSeed = 1)
        processor.process(deletion, FIRST_RELAY)
        runCurrent()
        val firstVerificationCount = verificationCount.get()

        processor.process(deletion, SECOND_RELAY)
        runCurrent()

        assertEquals(firstVerificationCount + 1, verificationCount.get())
        assertFalse(processor.seenIds.containsKey(eventId(2)))
        assertNull(store.getNostrEvent(eventId(2)))
        assertTrue(store.relayHintsForEvent(eventId(1)).isEmpty())
    }

    private fun deletion(seed: Int, targetSeed: Int): String =
        rawEvent(seed, kind = 5, pubkey = author(targetSeed), tags = deletionTags(targetSeed))

    private fun deletionTags(targetSeed: Int): String = """[["e","${eventId(targetSeed)}"],["k","1"]]"""

    private fun rawEvent(
        seed: Int,
        kind: Int = 1,
        pubkey: String = author(seed),
        tags: String = if (kind == 10002) """[["r","$FIRST_RELAY"]]""" else "[]",
    ): String = """["EVENT","drainer-test",{"id":"${eventId(seed)}","pubkey":"$pubkey","kind":$kind,"content":"event $seed","created_at":${1700000000L + seed},"tags":$tags,"sig":"${"c".repeat(128)}"}]"""

    private fun eventId(seed: Int): String = seed.toString(16).padStart(64, '0')
    private fun author(seed: Int): String = seed.toString(16).padStart(64, 'b')

    private companion object {
        const val FIRST_RELAY = "wss://first.example.com"
        const val SECOND_RELAY = "wss://second.example.com"
    }
}
