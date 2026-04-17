package com.unsilence.app.data.relay

import app.cash.turbine.test
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.FeedFilter
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Consumer-level bridge tests for A.4.3.
 *
 * These tests exercise the exact code paths that were broken before A.4.3
 * (consumers reading from Room instead of MemoryEventStore). Each test
 * uses a real MemoryEventStore and exercises the adapter methods that
 * the rewired consumers now call.
 *
 * Proves: the MemoryEventStore APIs return correct types and data that
 * consumers expect, through the same call sequences the consumers use.
 */
class ConsumerBridgeTest {

    private lateinit var store: MemoryEventStore

    @Before
    fun setUp() {
        store = MemoryEventStore()
    }

    private fun event(
        id: String,
        kind: Int = 1,
        pubkey: String = "pk-default",
        content: String = "hello",
        createdAt: Long = System.currentTimeMillis() / 1000,
        tags: List<List<String>> = emptyList(),
        relayUrl: String = "wss://relay.example.com",
        replyToId: String? = null,
        rootId: String? = null,
    ) = NostrEvent(
        id = id, pubkey = pubkey, kind = kind, content = content,
        createdAt = createdAt, tags = tags, sig = "sig", relayUrl = relayUrl,
        replyToId = replyToId, rootId = rootId, hasContentWarning = false,
        contentWarningReason = null, firstSeenAt = System.currentTimeMillis(),
        relaysSeen = mutableSetOf(relayUrl),
    )

    // ── NoteActionsViewModel.lookupEvent: memory hit ───────────────────────

    @Test
    fun `lookupEvent returns memory hit immediately via getEventEntity`() = runTest {
        // This is the exact sequence NoteActionsViewModel.lookupEvent executes:
        // 1. memoryEventStore.getEventEntity(eventId) → fast path
        val testEvent = event(id = "e1", kind = 1, content = "test note")
        store.insert(testEvent)

        val result = store.getEventEntity("e1")

        assertNotNull("Memory hit should return immediately", result)
        assertEquals("e1", result!!.id)
        assertEquals("test note", result.content)
        assertEquals(1, result.kind)
    }

    // ── NoteActionsViewModel.lookupEvent: relay fetch fallback ─────────────

    @Test
    fun `lookupEvent falls back to eventEntityFlow when not in memory`() = runTest {
        // Simulates the slow path: event not in memory initially,
        // arrives via EventProcessor → insert() → feed signal bump → flow emits
        store.eventEntityFlow("e1").test {
            // Initial: null (event not yet in store)
            val initial = awaitItem()
            assertNull("Should be null before insert", initial)

            // Simulate relay fetch landing the event in memory
            store.insert(event(id = "e1", kind = 1, content = "fetched"))

            val resolved = awaitItem()
            assertNotNull("Should resolve after insert", resolved)
            assertEquals("fetched", resolved!!.content)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── NoteActionsViewModel.lookupEvent: timeout ──────────────────────────

    @Test
    fun `lookupEvent getEventEntity returns null for missing event`() = runTest {
        // When event never arrives, getEventEntity returns null immediately.
        // NoteActionsViewModel wraps eventEntityFlow in withTimeoutOrNull(5000)
        // which returns null on timeout. This test proves the null path works.
        val result = store.getEventEntity("nonexistent")
        assertNull("Should return null for missing event", result)
    }

    // ── NoteActionsViewModel.lookupProfile ──────────────────────────────────

    @Test
    fun `lookupProfile returns memory-backed UserEntity`() = runTest {
        // This is the exact call: memoryEventStore.getUserEntity(pubkey)
        store.insert(event(
            id = "p1", kind = 0, pubkey = "alice",
            content = """{"name":"Alice","picture":"https://example.com/a.jpg","display_name":"Alice W","nip05":"alice@example.com"}""",
        ))

        val user = store.getUserEntity("alice")

        assertNotNull(user)
        assertEquals("alice", user!!.pubkey)
        assertEquals("Alice", user.name)
        assertEquals("https://example.com/a.jpg", user.picture)
        assertEquals("Alice W", user.displayName)
        assertEquals("alice@example.com", user.nip05)
    }

    // ── ProfileResolver.filterUnresolved ────────────────────────────────────

    @Test
    fun `filterUnresolved treats locally fresh profiles as fresh`() = runTest {
        // Insert a profile with ancient event createdAt — but the LOCAL cache
        // timestamp (profileUpdatedAt) should be "now" since we just inserted.
        store.insert(event(
            id = "p1", kind = 0, pubkey = "alice",
            content = """{"name":"Alice"}""",
            createdAt = 1500000000L,  // ancient kind-0 createdAt (~mid-2017)
        ))

        // Reproduce the exact filterUnresolved logic from ProfileResolver:
        // freshnessThreshold = now - 6h (in millis)
        val STALE_THRESHOLD_SECONDS = 6 * 3600L
        val freshnessThreshold = System.currentTimeMillis() - STALE_THRESHOLD_SECONDS * 1000
        val pubkeys = setOf("alice", "bob")

        val unresolved = pubkeys.filterTo(mutableSetOf()) { pk ->
            store.getProfileLastUpdated(pk) < freshnessThreshold
        }

        assertFalse(
            "Alice was just inserted (locally fresh) — must NOT be unresolved " +
            "even though her event.createdAt is ancient",
            "alice" in unresolved,
        )
        assertTrue("Bob is unknown — must be unresolved", "bob" in unresolved)
    }

    // ── ThreadViewModel.threadFeedRowFlow ───────────────────────────────────

    @Test
    fun `threadFeedRowFlow emits memory-backed thread as FeedRows`() = runTest {
        // Insert a thread: root + reply
        store.insert(event(id = "root", kind = 1, pubkey = "alice", content = "root note", createdAt = 1))
        store.insert(event(id = "r1", kind = 1, pubkey = "bob", replyToId = "root", rootId = "root", content = "reply", createdAt = 2))

        // Also insert profiles so FeedRow has author data
        store.insert(event(id = "p-alice", kind = 0, pubkey = "alice", content = """{"name":"Alice","picture":"https://a.jpg"}"""))
        store.insert(event(id = "p-bob", kind = 0, pubkey = "bob", content = """{"name":"Bob","picture":"https://b.jpg"}"""))

        // This is the exact flow ThreadViewModel subscribes to
        store.threadFeedRowFlow("root").test {
            val rows = awaitItem()
            val ids = rows.map { it.id }.toSet()

            assertTrue("root included", "root" in ids)
            assertTrue("r1 included", "r1" in ids)
            assertEquals("Thread should have exactly 2 rows", 2, rows.size)

            // Verify FeedRow has author data from profiles
            val rootRow = rows.first { it.id == "root" }
            assertEquals("Alice", rootRow.authorName)
            assertEquals("https://a.jpg", rootRow.authorPicture)

            val replyRow = rows.first { it.id == "r1" }
            assertEquals("Bob", replyRow.authorName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── EmbeddedQuoteCard: reactive profile lookup ─────────────────────────

    @Test
    fun `userEntityFlow resolves quoted event author when profile arrives later`() = runTest {
        // This is the exact scenario for embedded quotes:
        // 1. Quoted event is already in memory (content shows)
        // 2. Quoted event's author profile is NOT yet in memory
        // 3. lookupProfile returns null initially
        // 4. Profile arrives later via hydrateRefs → EventProcessor
        // 5. userEntityFlow must emit the resolved profile

        // Step 1: Insert the quoted event (kind 1)
        store.insert(event(id = "q1", kind = 1, pubkey = "quoted-author", content = "quoted content"))

        // Step 2: Verify event is in memory but profile is not
        assertNotNull("Quoted event should be in memory", store.getEventEntity("q1"))
        assertNull("Profile should NOT be in memory yet", store.getUserEntity("quoted-author"))

        // Step 3: Subscribe to userEntityFlow (this is what the fixed lookupProfile will use)
        store.userEntityFlow("quoted-author").test {
            val initial = awaitItem()
            assertNull("Should be null before profile arrives", initial)

            // Step 4: Profile arrives (simulates hydrateRefs → relay fetch → EventProcessor)
            store.insert(event(
                id = "p-quoted", kind = 0, pubkey = "quoted-author",
                content = """{"name":"Quoted Author","picture":"https://qa.jpg","nip05":"qa@example.com"}""",
            ))

            // Step 5: Flow should emit the resolved profile
            val resolved = awaitItem()
            assertNotNull("Profile should resolve after insert", resolved)
            assertEquals("Quoted Author", resolved!!.name)
            assertEquals("https://qa.jpg", resolved.picture)
            assertEquals("qa@example.com", resolved.nip05)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
