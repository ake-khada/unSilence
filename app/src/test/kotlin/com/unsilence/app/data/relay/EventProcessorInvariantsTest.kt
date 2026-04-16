package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
/** Records all prefetch dispatches for assertion in tests. */
private class RecordingPrefetchDispatcher : PrefetchDispatcher {
    data class Dispatch(val relayUrl: String, val eventIds: List<String>)
    val dispatches = mutableListOf<Dispatch>()
    val allFetchedIds: Set<String> get() = dispatches.flatMap { it.eventIds }.toSet()

    override fun dispatch(relayUrl: String, eventIds: List<String>) {
        dispatches.add(Dispatch(relayUrl, eventIds))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EventProcessorInvariantsTest {

    private lateinit var store: MemoryEventStore
    private lateinit var processor: EventProcessor
    private lateinit var recorder: RecordingPrefetchDispatcher

    @Before
    fun setUp() {
        store = MemoryEventStore()
        val outboxRouter = dagger.Lazy<OutboxRouter> { error("OutboxRouter not needed in this test") }
        processor = EventProcessor(store, outboxRouter)
        recorder = RecordingPrefetchDispatcher()
        processor.prefetchDispatcher = recorder
        // Stop drainers — tests use drainForTest() for synchronous channel drain.
        // CoroutineExceptionHandler swallows kindHandler errors from the stub OutboxRouter.
        val handler = CoroutineExceptionHandler { _, _ -> }
        processor.setTestScope(CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher() + handler))
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

    // ── Test 5: Kind 3 updates follows via direct path (not channeled) ─────

    @Test
    fun `kind 3 updates MemoryEventStore follows without channeling`() = runTest {
        // Kind 3 contact list with two followed pubkeys
        val followedPk1 = "d".repeat(64)
        val followedPk2 = "e".repeat(64)
        val (raw, relay) = rawEvent(
            seed = 30,
            kind = 3,
            content = "",
            tags = """[["p","$followedPk1"],["p","$followedPk2"]]""",
        )
        processor.process(raw, relay)
        processor.drainForTest()

        // Follows should be populated
        val authorPk = "b".repeat(64)  // rawEvent uses "b" * 64 as pubkey
        val follows = store.getFollows(authorPk)
        assertNotNull("getFollows should return non-null after kind-3", follows)
        assertEquals("Should have 2 followed pubkeys", 2, follows!!.size)
        assertTrue("Should contain followedPk1", followedPk1 in follows)
        assertTrue("Should contain followedPk2", followedPk2 in follows)

        // Kind 3 should NOT be in the main event store (not channeled)
        val storedEvents = store.eventsByIds(setOf(eventId(30)))
        assertTrue("Kind-3 event should NOT be in main store", storedEvents.isEmpty())
    }

    // ── Test 6: NIP-40 expiry filter ────────────────────────────────────────

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

    // ═══════════════════════════════════════════════════════════════════════
    // A.5 Prefetch tests — referenced event pre-fetching from source relay
    // ═══════════════════════════════════════════════════════════════════════

    /** Generate a valid 64-char hex event ID from a numeric seed. */
    private fun refId(seed: Int): String = seed.toString().padStart(64, 'a')

    /** Build a raw kind-1 reply event referencing [parentId] via NIP-10 reply marker. */
    private fun replyEvent(
        seed: Int,
        parentId: String,
        relayUrl: String = "wss://relay.example.com",
    ): Pair<String, String> = rawEvent(
        seed = seed,
        kind = 1,
        tags = """[["e","$parentId","","reply"]]""",
        relayUrl = relayUrl,
    )

    /** Build a raw event with multiple e-tag refs. */
    private fun eventWithETags(
        seed: Int,
        eTagIds: List<String>,
        kind: Int = 1,
        relayUrl: String = "wss://relay.example.com",
    ): Pair<String, String> {
        val tagsJson = eTagIds.joinToString(",") { """["e","$it"]""" }
        return rawEvent(seed = seed, kind = kind, tags = "[$tagsJson]", relayUrl = relayUrl)
    }

    // ── Test 7: kind-1 reply triggers prefetch of parent ──────────────────

    @Test
    fun `kind 1 reply triggers prefetch of parent from source relay`() = runTest {
        val parentId = refId(9001)
        val (raw, relay) = replyEvent(seed = 100, parentId = parentId)
        processor.process(raw, relay)
        processor.drainForTest()
        processor.drainPrefetchForTest()

        assertTrue(
            "Should prefetch parent from source relay",
            recorder.dispatches.any { it.relayUrl == relay && parentId in it.eventIds },
        )
        assertTrue(
            "Enqueued counter should be incremented",
            processor.prefetchEnqueuedCount.get() >= 1,
        )
    }

    // ── Test 8: multiple e-tags all prefetched ────────────────────────────

    @Test
    fun `event with multiple e-tags prefetches all referenced events`() = runTest {
        val ref1 = refId(9011)
        val ref2 = refId(9012)
        val ref3 = refId(9013)
        val (raw, relay) = eventWithETags(seed = 101, eTagIds = listOf(ref1, ref2, ref3))
        processor.process(raw, relay)
        processor.drainForTest()
        processor.drainPrefetchForTest()

        val fetched = recorder.allFetchedIds
        assertTrue("ref1 should be prefetched", ref1 in fetched)
        assertTrue("ref2 should be prefetched", ref2 in fetched)
        assertTrue("ref3 should be prefetched", ref3 in fetched)
    }

    // ── Test 9: prefetch deduplicates within session ──────────────────────

    @Test
    fun `prefetch deduplicates same event ID across multiple referencing events`() = runTest {
        val sharedParent = refId(9021)
        // 5 replies referencing the same parent from different relays
        for (i in 0 until 5) {
            val (raw, relay) = replyEvent(
                seed = 200 + i,
                parentId = sharedParent,
                relayUrl = "wss://relay$i.example.com",
            )
            processor.process(raw, relay)
        }
        processor.drainForTest()
        processor.drainPrefetchForTest()

        val fetchesForParent = recorder.dispatches.count { sharedParent in it.eventIds }
        assertEquals("Should only fetch shared parent once", 1, fetchesForParent)
        assertTrue(
            "Dedup counter should reflect 4 skipped duplicates",
            processor.prefetchDedupedCount.get() >= 4,
        )
    }

    // ── Test 10: prefetch skips events already in MemoryEventStore ────────

    @Test
    fun `prefetch skips events already in MemoryEventStore`() = runTest {
        val cachedId = refId(9031)
        // Pre-insert the target event
        store.insert(NostrEvent(
            id = cachedId, pubkey = "b".repeat(64), kind = 1, content = "cached",
            createdAt = 1700000000L, tags = emptyList(), sig = "c".repeat(128),
            relayUrl = "wss://r.example.com", replyToId = null, rootId = null,
            hasContentWarning = false, contentWarningReason = null,
            firstSeenAt = System.currentTimeMillis(), relaysSeen = mutableSetOf("wss://r.example.com"),
        ))

        val (raw, relay) = replyEvent(seed = 300, parentId = cachedId)
        processor.process(raw, relay)
        processor.drainForTest()
        processor.drainPrefetchForTest()

        val fetchesForCached = recorder.dispatches.count { cachedId in it.eventIds }
        assertEquals("Should NOT fetch event already in store", 0, fetchesForCached)
        assertTrue(
            "Skipped-cached counter should reflect the skip",
            processor.prefetchSkippedAlreadyCachedCount.get() >= 1,
        )
    }

    // ── Test 11: prefetch skips malformed IDs and blank relay URLs ────────

    @Test
    fun `prefetch skips malformed event IDs and blank relay URLs`() = runTest {
        // Malformed ID (not 64 hex chars)
        val (raw1, _) = rawEvent(
            seed = 400,
            tags = """[["e","not-64-hex"]]""",
            relayUrl = "wss://relay.example.com",
        )
        processor.process(raw1, "wss://relay.example.com")

        // Blank relay URL — event ID in e-tag is valid but relay is blank
        val validId = refId(9041)
        val (raw2, _) = rawEvent(
            seed = 401,
            tags = """[["e","$validId"]]""",
            relayUrl = "",
        )
        processor.process(raw2, "")

        // Invalid relay URL (no domain dot — normalizeRelayUrl returns null)
        val (raw3, _) = rawEvent(
            seed = 402,
            tags = """[["e","$validId"]]""",
            relayUrl = "localhost",
        )
        processor.process(raw3, "localhost")

        processor.drainForTest()
        processor.drainPrefetchForTest()

        assertEquals("All three should be skipped — no dispatches", 0, recorder.dispatches.size)
    }

    // ── Test 12: prefetch generalizes across kinds ────────────────────────

    @Test
    fun `prefetch generalizes across kinds not hardcoded to specific kinds`() = runTest {
        val repostTarget = refId(9051)
        val zapTarget = refId(9052)
        val reactionTarget = refId(9053)

        // Kind 6 repost with e-tag
        val (raw6, relay6) = rawEvent(
            seed = 500, kind = 6,
            content = "",
            tags = """[["e","$repostTarget"],["p","${"b".repeat(64)}"]]""",
        )
        processor.process(raw6, relay6)

        // Kind 7 reaction with e-tag
        val (raw7, relay7) = rawEvent(
            seed = 501, kind = 7,
            content = "+",
            tags = """[["e","$reactionTarget"],["p","${"b".repeat(64)}"]]""",
        )
        processor.process(raw7, relay7)

        // Kind 9734 zap request with e-tag
        val (raw9734, relay9734) = rawEvent(
            seed = 502, kind = 9734,
            content = "",
            tags = """[["e","$zapTarget"],["p","${"b".repeat(64)}"]]""",
        )
        processor.process(raw9734, relay9734)

        processor.drainForTest()
        processor.drainPrefetchForTest()

        val fetched = recorder.allFetchedIds
        assertTrue("Repost target should be prefetched", repostTarget in fetched)
        assertTrue("Reaction target should be prefetched", reactionTarget in fetched)
        assertTrue("Zap target should be prefetched", zapTarget in fetched)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // A.6 Outbox-aware prefetch — referenced events fetched from author's
    // NIP-65 write relays when the author's kind-10002 is cached
    // ═══════════════════════════════════════════════════════════════════════

    /** Seed a kind-10002 relay list into MemoryEventStore for [pubkey]. */
    private fun seedRelayList(pubkey: String, writeRelays: List<String>) {
        val rTags = writeRelays.map { listOf("r", it, "write") }
        store.insert(NostrEvent(
            id = "rl-$pubkey".padEnd(64, '0'),
            pubkey = pubkey,
            kind = 10002,
            content = "",
            createdAt = 1700000000L,
            tags = rTags,
            sig = "c".repeat(128),
            relayUrl = "wss://indexer.example.com",
            replyToId = null,
            rootId = null,
            hasContentWarning = false,
            contentWarningReason = null,
            firstSeenAt = System.currentTimeMillis(),
            relaysSeen = mutableSetOf("wss://indexer.example.com"),
        ))
    }

    /** Build a reply event with both e-tag and p-tag (referencing parent author). */
    private fun replyEventWithPTag(
        seed: Int,
        parentId: String,
        parentAuthorPubkey: String,
        relayUrl: String = "wss://relay.example.com",
    ): Pair<String, String> = rawEvent(
        seed = seed,
        kind = 1,
        tags = """[["e","$parentId","","reply"],["p","$parentAuthorPubkey"]]""",
        relayUrl = relayUrl,
    )

    // ── Test 14: reply with p-tag triggers outbox prefetch ───────────────

    @Test
    fun `reply with p-tag and cached write relays triggers outbox prefetch`() = runTest {
        val parentAuthor = "d".repeat(64)
        val parentId = refId(9071)
        seedRelayList(parentAuthor, listOf("wss://author-outbox.example.com"))

        val (raw, relay) = replyEventWithPTag(
            seed = 700, parentId = parentId, parentAuthorPubkey = parentAuthor,
        )
        processor.process(raw, relay)
        processor.drainForTest()
        processor.drainPrefetchForTest()

        // Source relay prefetch should still fire
        assertTrue(
            "Source relay prefetch should fire",
            recorder.dispatches.any { it.relayUrl == relay && parentId in it.eventIds },
        )
        // Outbox relay prefetch should ALSO fire
        assertTrue(
            "Outbox relay prefetch should fire for author's write relay",
            recorder.dispatches.any {
                it.relayUrl == "wss://author-outbox.example.com" && parentId in it.eventIds
            },
        )
        assertTrue(
            "Outbox dispatched counter should be incremented",
            processor.outboxPrefetchDispatchedCount.get() >= 1,
        )
    }

    // ── Test 15: no outbox dispatch when write relays not cached ─────────

    @Test
    fun `no outbox dispatch when author write relays are not cached`() = runTest {
        val unknownAuthor = "f".repeat(64) // No kind-10002 seeded
        val parentId = refId(9081)

        val (raw, relay) = replyEventWithPTag(
            seed = 710, parentId = parentId, parentAuthorPubkey = unknownAuthor,
        )
        processor.process(raw, relay)
        processor.drainForTest()
        processor.drainPrefetchForTest()

        // Source relay prefetch fires
        assertTrue(
            "Source relay prefetch should fire",
            recorder.dispatches.any { it.relayUrl == relay && parentId in it.eventIds },
        )
        // No outbox dispatch (no cached relay list)
        val outboxDispatches = recorder.dispatches.filter { it.relayUrl != relay }
        assertTrue(
            "No outbox dispatch without cached write relays",
            outboxDispatches.none { parentId in it.eventIds },
        )
        assertEquals(
            "Outbox dispatched counter should be 0",
            0, processor.outboxPrefetchDispatchedCount.get(),
        )
    }

    // ── Test 16: outbox skips source relay (no duplicate) ────────────────

    @Test
    fun `outbox prefetch skips source relay to avoid duplicate dispatch`() = runTest {
        val parentAuthor = "d".repeat(64)
        val parentId = refId(9091)
        val sourceRelay = "wss://relay.example.com"
        // Author's write relay IS the same as source relay
        seedRelayList(parentAuthor, listOf(sourceRelay, "wss://other-outbox.example.com"))

        val (raw, _) = replyEventWithPTag(
            seed = 720, parentId = parentId, parentAuthorPubkey = parentAuthor,
            relayUrl = sourceRelay,
        )
        processor.process(raw, sourceRelay)
        processor.drainForTest()
        processor.drainPrefetchForTest()

        // Source relay dispatch exists
        val sourceDispatches = recorder.dispatches.filter {
            it.relayUrl == sourceRelay && parentId in it.eventIds
        }
        // Outbox dispatches should NOT include the source relay
        val outboxDispatches = recorder.dispatches.filter {
            it.relayUrl == "wss://other-outbox.example.com" && parentId in it.eventIds
        }
        assertTrue("Outbox should dispatch to other-outbox", outboxDispatches.isNotEmpty())

        // Total dispatches for parentId from source relay should be exactly 1
        // (from source-relay prefetch, not duplicated by outbox)
        assertEquals(
            "Source relay should appear exactly once for parentId",
            1, sourceDispatches.size,
        )
    }

    // ── Test 18: kind 10002 populates writeRelaysFor via direct insert ──

    @Test
    fun `kind 10002 populates MemoryEventStore writeRelaysFor via direct insert`() = runTest {
        val author = "d".repeat(64)
        val (raw, relay) = rawEvent(
            seed = 740,
            kind = 10002,
            content = "",
            tags = """[["r","wss://author-write.example.com","write"],["r","wss://author-read.example.com","read"]]""",
        )
        // Override pubkey to match author
        val rawFixed = raw.replace("b".repeat(64), author)
        processor.process(rawFixed, relay)
        processor.drainForTest()

        val writeRelays = store.writeRelaysFor(author)
        assertTrue(
            "writeRelaysFor should contain the write relay",
            "wss://author-write.example.com" in writeRelays,
        )
        assertTrue(
            "writeRelaysFor should NOT contain the read relay",
            "wss://author-read.example.com" !in writeRelays,
        )
    }

    // ── Test 17: outbox budget caps at 5 relays ─────────────────────────

    @Test
    fun `outbox prefetch is capped at 5 unique relay URLs`() = runTest {
        val parentAuthor = "d".repeat(64)
        val parentId = refId(9101)
        // Author has 10 write relays — only 5 should be dispatched
        val writeRelays = (1..10).map { "wss://outbox$it.example.com" }
        seedRelayList(parentAuthor, writeRelays)

        val (raw, relay) = replyEventWithPTag(
            seed = 730, parentId = parentId, parentAuthorPubkey = parentAuthor,
        )
        processor.process(raw, relay)
        processor.drainForTest()
        processor.drainPrefetchForTest()

        val outboxRelays = recorder.dispatches
            .filter { it.relayUrl != relay && parentId in it.eventIds }
            .map { it.relayUrl }
            .distinct()
        assertTrue(
            "Outbox relays should be capped at 5, got ${outboxRelays.size}",
            outboxRelays.size <= 5,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // A.6.2 — Bridged repost author fallback: kind-6 wrappers without
    // p-tags use the wrapper's own pubkey as outbox author fallback
    // ═══════════════════════════════════════════════════════════════════════

    // ── Test 19: kind-6 without p-tags uses wrapper pubkey for outbox ───

    @Test
    fun `kind 6 repost without p-tags uses wrapper pubkey for outbox`() = runTest {
        val wrapperAuthor = "e".repeat(64)
        val repostTarget = refId(9111)
        seedRelayList(wrapperAuthor, listOf("wss://wrapper-outbox.example.com"))

        // Repost event with e-tag but NO p-tag
        val (raw, relay) = rawEvent(
            seed = 800, kind = 6,
            content = "",
            tags = """[["e","$repostTarget"]]""",
        )
        val rawFixed = raw.replace("b".repeat(64), wrapperAuthor)
        processor.process(rawFixed, relay)
        processor.drainForTest()
        processor.drainPrefetchForTest()

        // Outbox should use wrapper author as fallback
        assertTrue(
            "Outbox should dispatch to wrapper author's write relay",
            recorder.dispatches.any {
                it.relayUrl == "wss://wrapper-outbox.example.com" &&
                repostTarget in it.eventIds
            },
        )
    }

    // ── Test 20: kind-6 WITH p-tags uses p-tag author, not wrapper ──────

    @Test
    fun `kind 6 repost WITH p-tags uses p-tag author not wrapper`() = runTest {
        val wrapperAuthor = "e".repeat(64)
        val targetAuthor = "f".repeat(64)
        val repostTarget = refId(9121)
        seedRelayList(wrapperAuthor, listOf("wss://wrapper-outbox.example.com"))
        seedRelayList(targetAuthor, listOf("wss://target-outbox.example.com"))

        val (raw, relay) = rawEvent(
            seed = 810, kind = 6,
            content = "",
            tags = """[["e","$repostTarget"],["p","$targetAuthor"]]""",
        )
        val rawFixed = raw.replace("b".repeat(64), wrapperAuthor)
        processor.process(rawFixed, relay)
        processor.drainForTest()
        processor.drainPrefetchForTest()

        // Should use target-outbox (p-tag author), NOT wrapper-outbox
        assertTrue(
            "Outbox should dispatch to target author's write relay",
            recorder.dispatches.any {
                it.relayUrl == "wss://target-outbox.example.com" &&
                repostTarget in it.eventIds
            },
        )
        assertTrue(
            "Outbox should NOT dispatch to wrapper author's write relay " +
            "when p-tag author exists",
            recorder.dispatches.none {
                it.relayUrl == "wss://wrapper-outbox.example.com" &&
                repostTarget in it.eventIds
            },
        )
    }

    // ── Test 21: kind-1 without p-tags does NOT fall back to wrapper ────

    @Test
    fun `kind 1 reply without p-tags does NOT fall back to wrapper pubkey`() = runTest {
        val author = "e".repeat(64)
        val parentId = refId(9131)
        seedRelayList(author, listOf("wss://author-outbox.example.com"))

        val (raw, relay) = rawEvent(
            seed = 820, kind = 1,
            tags = """[["e","$parentId","","reply"]]""",  // no p-tag
        )
        val rawFixed = raw.replace("b".repeat(64), author)
        processor.process(rawFixed, relay)
        processor.drainForTest()
        processor.drainPrefetchForTest()

        // No outbox dispatch for kind 1 without p-tags
        assertEquals(
            "Kind 1 without p-tags should not trigger outbox",
            0, processor.outboxPrefetchDispatchedCount.get(),
        )
    }

    // ── Test 22: kind-6 wrapper fallback doesn't double-dispatch ────────

    @Test
    fun `kind 6 wrapper fallback does not double-dispatch to source relay`() = runTest {
        val wrapperAuthor = "e".repeat(64)
        val repostTarget = refId(9141)
        val sharedRelay = "wss://shared.example.com"
        seedRelayList(wrapperAuthor, listOf(sharedRelay, "wss://other-outbox.example.com"))

        val (raw, _) = rawEvent(
            seed = 830, kind = 6,
            content = "",
            tags = """[["e","$repostTarget"]]""",
            relayUrl = sharedRelay,
        )
        val rawFixed = raw.replace("b".repeat(64), wrapperAuthor)
        processor.process(rawFixed, sharedRelay)
        processor.drainForTest()
        processor.drainPrefetchForTest()

        // Source relay dispatch exists (from source-relay prefetch)
        val sharedDispatches = recorder.dispatches.filter {
            it.relayUrl == sharedRelay && repostTarget in it.eventIds
        }
        assertEquals(
            "Shared relay should appear exactly once (source-relay prefetch, " +
            "not duplicated by outbox wrapper-author fallback)",
            1, sharedDispatches.size,
        )

        // Outbox should still dispatch to the other write relay
        assertTrue(
            "Outbox should dispatch to non-source write relay",
            recorder.dispatches.any {
                it.relayUrl == "wss://other-outbox.example.com" &&
                repostTarget in it.eventIds
            },
        )
    }

    // ── Test 13: end-to-end — prefetched event resolves via getEventEntity ─

    @Test
    fun `prefetched event resolves in MemoryEventStore after relay delivers it`() = runTest {
        val parentId = refId(9061)
        val (replyRaw, relay) = replyEvent(seed = 600, parentId = parentId)
        processor.process(replyRaw, relay)
        processor.drainForTest()
        processor.drainPrefetchForTest()

        // Verify prefetch was dispatched
        assertTrue(
            "Prefetch should have been dispatched",
            recorder.dispatches.any { parentId in it.eventIds },
        )

        // Simulate the parent event arriving from the source relay
        val (parentRaw, _) = rawEvent(seed = 601, relayUrl = relay)
        // Override the ID to match parentId
        val parentRawFixed = parentRaw.replace(eventId(601), parentId)
        processor.process(parentRawFixed, relay)
        processor.drainForTest()

        // The parent should now be in MemoryEventStore
        val parent = store.getEventEntity(parentId)
        assertNotNull("Parent should be in MemoryEventStore after relay delivers it", parent)

        // No additional fetch needed — the event is already cached
        val fetchesBefore = recorder.dispatches.size
        // A second reply referencing the same parent should NOT trigger another prefetch
        val (reply2Raw, relay2) = replyEvent(seed = 602, parentId = parentId, relayUrl = "wss://other.relay.com")
        processor.process(reply2Raw, relay2)
        processor.drainForTest()
        processor.drainPrefetchForTest()

        // Should be deduped (prefetchedRefs) or skipped (already cached)
        val newFetches = recorder.dispatches.size - fetchesBefore
        assertEquals("No new fetch for already-resolved parent", 0, newFetches)
    }
}
