package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.MemoryEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Invariant tests for EventProcessor behavior after the A.2 rewrite
 * (EventProcessor writes to MemoryEventStore instead of Room).
 *
 * Each test uses a real MemoryEventStore. The EventProcessor's drainers
 * are stopped via setTestScope(); tests call drainForTest() to push
 * channel contents through flushBatch into MemoryEventStore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventProcessorInvariantsTest {

    private lateinit var store: MemoryEventStore
    private lateinit var processor: EventProcessor

    @Before
    fun setUp() {
        store = MemoryEventStore()
        val outboxRouter = dagger.Lazy<OutboxRouter> { error("OutboxRouter not needed in this test") }
        processor = EventProcessor(store, outboxRouter)
        // Stop drainers — tests use drainForTest() for synchronous channel drain
        processor.setTestScope(CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Build a valid raw Nostr EVENT message that EventProcessor can parse.
     * The id is a 64-char hex string derived from [seed].
     * Content is JSON-escaped for safe embedding in the EVENT JSON.
     */
    private fun rawEvent(
        seed: Int,
        kind: Int = 1,
        content: String = "hello nostr",
        relayUrl: String = "wss://relay.example.com",
        tags: String = "[]",
        createdAt: Long = 1700000000L + seed,
    ): Pair<String, String> {
        val id = seed.toString().padStart(64, 'a')
        val pubkey = "b".repeat(64)
        val sig = "c".repeat(128)
        // JSON-escape content: backslashes first, then quotes, then newlines
        val escaped = content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
        val raw = """["EVENT","sub-1",{"id":"$id","pubkey":"$pubkey","kind":$kind,"content":"$escaped","created_at":$createdAt,"tags":$tags,"sig":"$sig"}]"""
        return raw to relayUrl
    }

    private fun eventId(seed: Int): String = seed.toString().padStart(64, 'a')

    // ── Test 1: Duplicate event relay provenance ────────────────────────────

    @Test
    fun `duplicate event arrival uses queue path not per-event coroutine`() = runTest {
        val (raw, _) = rawEvent(1)
        val relayUrls = (1..100).map { "wss://relay$it.example.com" }

        // Process same event from 100 different relays.
        // First goes through channel→flushBatch→insert.
        // Remaining 99 hit seenIds dedup → addRelaySeen (may buffer in pendingRelays
        // until insert is called during drainForTest).
        for (url in relayUrls) {
            processor.process(raw, url)
        }
        processor.drainForTest()

        // Only 1 event stored
        val events = store.eventsByIds(setOf(eventId(1)))
        assertEquals("Expected exactly 1 event in store", 1, events.size)

        // All 100 relays recorded in relaysSeen
        val relaysSeen = events.first().relaysSeen
        assertEquals("Expected 100 relays in relaysSeen", 100, relaysSeen.size)
        for (url in relayUrls) {
            assertTrue("Missing relay: $url", relaysSeen.contains(url))
        }
    }

    // ── Test 2: seenIds dedup prevents reprocessing ─────────────────────────

    @Test
    fun `seenIds dedup prevents reprocessing across flushBatch cycles`() = runTest {
        val (raw, relay) = rawEvent(2)

        // Process same raw message 10 times from same relay
        repeat(10) {
            processor.process(raw, relay)
        }
        processor.drainForTest()

        // Exactly 1 event in store (seenIds prevented re-parse + re-insert)
        val events = store.eventsByIds(setOf(eventId(2)))
        assertEquals("Expected exactly 1 event in store", 1, events.size)
    }

    // ── Test 3: trimDedupCache evicts when over 10000 ───────────────────────

    @Test
    fun `trimDedupCache evicts when seenIds exceeds 10000`() = runTest {
        // Insert 11000 unique events, draining every 200 to avoid channel
        // capacity overflow (channels cap at 500, trySend drops silently).
        for (i in 1..11000) {
            val (raw, relay) = rawEvent(i)
            processor.process(raw, relay)
            if (i % 200 == 0) processor.drainForTest()
        }
        processor.drainForTest()

        // seenIds should have been trimmed: between 9000 and 10000
        val size = processor.seenIds.size
        assertTrue(
            "Expected seenIds.size between 9000 and 10000, got $size",
            size in 9000..10000,
        )

        // All 11000 events should be in MemoryEventStore regardless of trim
        val allEvents = store.eventsByIds((1..11000).map { eventId(it) }.toSet())
        assertEquals("Expected all 11000 events in store", 11000, allEvents.size)
    }

    // ── Test 4: Spam filter for kind 1 starting with "{" ────────────────────

    @Test
    fun `content starting with brace is filtered for kind 1`() = runTest {
        // Spam: kind 1 with JSON content → rejected
        val (spamRaw, spamRelay) = rawEvent(seed = 10, kind = 1, content = """{"spam":true}""")
        processor.process(spamRaw, spamRelay)

        // Control: kind 1 with normal text → accepted
        val (normalRaw, normalRelay) = rawEvent(seed = 11, kind = 1, content = "normal post")
        processor.process(normalRaw, normalRelay)

        // Control: kind 0 with JSON content → accepted (spam filter is kind-1 only)
        val (profileRaw, profileRelay) = rawEvent(seed = 12, kind = 0, content = """{"name":"alice"}""")
        processor.process(profileRaw, profileRelay)

        processor.drainForTest()

        // Spam event rejected
        val spamEvents = store.eventsByIds(setOf(eventId(10)))
        assertTrue("Spam event should be rejected", spamEvents.isEmpty())

        // Normal kind-1 accepted
        val normalEvents = store.eventsByIds(setOf(eventId(11)))
        assertEquals("Normal kind-1 should be stored", 1, normalEvents.size)

        // Kind-0 profile with JSON accepted
        val profileEvents = store.eventsByIds(setOf(eventId(12)))
        assertEquals("Kind-0 profile with JSON content should be stored", 1, profileEvents.size)
    }

    // ── Test 5: NIP-40 expiry filter ────────────────────────────────────────

    @Test
    fun `expired events per NIP-40 are filtered`() = runTest {
        val pastExpiration = (System.currentTimeMillis() / 1000L) - 3600  // 1 hour ago
        val futureExpiration = (System.currentTimeMillis() / 1000L) + 3600  // 1 hour from now

        // Expired event → rejected
        val (expiredRaw, expiredRelay) = rawEvent(
            seed = 20,
            tags = """[["expiration","$pastExpiration"]]""",
        )
        processor.process(expiredRaw, expiredRelay)

        // Future expiration → accepted
        val (futureRaw, futureRelay) = rawEvent(
            seed = 21,
            tags = """[["expiration","$futureExpiration"]]""",
        )
        processor.process(futureRaw, futureRelay)

        // No expiration tag → accepted
        val (noExpiryRaw, noExpiryRelay) = rawEvent(seed = 22)
        processor.process(noExpiryRaw, noExpiryRelay)

        processor.drainForTest()

        // Expired event rejected
        val expiredEvents = store.eventsByIds(setOf(eventId(20)))
        assertTrue("Expired event should be rejected", expiredEvents.isEmpty())

        // Future expiration accepted
        val futureEvents = store.eventsByIds(setOf(eventId(21)))
        assertEquals("Future-expiration event should be stored", 1, futureEvents.size)

        // No expiration accepted
        val noExpiryEvents = store.eventsByIds(setOf(eventId(22)))
        assertEquals("Event with no expiration should be stored", 1, noExpiryEvents.size)
    }
}
