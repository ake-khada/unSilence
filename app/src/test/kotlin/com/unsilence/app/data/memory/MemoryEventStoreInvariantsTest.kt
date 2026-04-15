package com.unsilence.app.data.memory

// ── Deferred test suites (do NOT forget) ────────────────────────────────────
// EventProcessorInvariantsTest — due in A.2 (EventProcessor rewired to MemoryEventStore)
//   Tests: duplicate queue path, seenIds dedup, trimDedupCache, brace-content filter, NIP-40 expiry
// RelayPoolInvariantsTest — due in A.6 (OutboxRouter rewired) / A.7 (Room deleted)
//   Tests: PERSISTENT-only home subs, persistent prefix survives EOSE, one-shot CLOSE after EOSE,
//          concurrent reconnect dedup
// ─────────────────────────────────────────────────────────────────────────────

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryEventStoreInvariantsTest {

    private lateinit var store: MemoryEventStore

    @Before
    fun setUp() {
        store = MemoryEventStore()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun event(
        id: String = "evt-${System.nanoTime()}",
        pubkey: String = "pk-default",
        kind: Int = 1,
        content: String = "hello",
        createdAt: Long = System.currentTimeMillis() / 1000,
        tags: List<List<String>> = emptyList(),
        sig: String = "sig",
        relayUrl: String = "wss://relay.example.com",
        replyToId: String? = null,
        rootId: String? = null,
        hasContentWarning: Boolean = false,
        contentWarningReason: String? = null,
        firstSeenAt: Long = System.currentTimeMillis(),
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        kind = kind,
        content = content,
        createdAt = createdAt,
        tags = tags,
        sig = sig,
        relayUrl = relayUrl,
        replyToId = replyToId,
        rootId = rootId,
        hasContentWarning = hasContentWarning,
        contentWarningReason = contentWarningReason,
        firstSeenAt = firstSeenAt,
        relaysSeen = mutableSetOf(relayUrl),
    )

    private val defaultFilter = FeedFilter(kinds = setOf(1, 6, 30023))

    // ── Dedup ────────────────────────────────────────────────────────────────

    @Test
    fun `same event from N relays produces 1 insert`() {
        val id = "dedup-event"
        val relays = (1..19).map { "wss://relay$it.example.com" }

        // First insert should be novel
        assertTrue(store.insert(event(id = id, relayUrl = relays[0])))
        // Subsequent inserts of same ID from different relays are duplicates
        for (i in 1 until relays.size) {
            assertFalse(store.insert(event(id = id, relayUrl = relays[i])))
        }

        val events = store.eventsByIds(setOf(id))
        assertEquals(1, events.size)
        assertEquals(19, events[0].relaysSeen.size)
    }

    // ── Kind 6 repost stats ─────────────────────────────────────────────────

    @Test
    fun `kind 6 repost stats attribute to rootId not id`() {
        val parentId = "parent-1"
        val repostId = "repost-1"

        store.insert(event(id = parentId, kind = 1))
        store.insert(event(id = repostId, kind = 6, rootId = parentId))

        assertEquals(1, store.repostCount(parentId))
        assertEquals(0, store.repostCount(repostId))
    }

    // ── NIP-10 threading ────────────────────────────────────────────────────

    @Test
    fun `NIP-10 marker tags override positional tags`() {
        // Event with both marker-based and positional e-tags
        // Markers should win
        val tags = listOf(
            listOf("e", "positional-root"),
            listOf("e", "marker-root", "", "root"),
            listOf("e", "positional-reply"),
            listOf("e", "marker-reply", "", "reply"),
        )

        val evt = event(
            id = "nip10-marker",
            kind = 1,
            tags = tags,
            replyToId = "marker-reply",
            rootId = "marker-root",
        )
        store.insert(evt)

        val stored = store.eventsByIds(setOf("nip10-marker")).first()
        assertEquals("marker-reply", stored.replyToId)
        assertEquals("marker-root", stored.rootId)
    }

    @Test
    fun `NIP-10 single e-tag uses positional fallback`() {
        val evt = event(
            id = "nip10-single",
            kind = 1,
            tags = listOf(listOf("e", "single-target")),
            replyToId = "single-target",
            rootId = "single-target",
        )
        store.insert(evt)

        val stored = store.eventsByIds(setOf("nip10-single")).first()
        assertEquals("single-target", stored.rootId)
        assertEquals("single-target", stored.replyToId)
    }

    // ── Reply count ─────────────────────────────────────────────────────────

    @Test
    fun `replyCount increments on kind 1 with replyToId`() {
        val parentId = "reply-parent"
        store.insert(event(id = parentId, kind = 1))
        store.insert(event(id = "reply-1", kind = 1, replyToId = parentId))

        assertEquals(1, store.replyCount(parentId))
    }

    @Test
    fun `replyCount increments for rootId when replyToId differs`() {
        val rootId = "thread-root"
        val midId = "mid-reply"
        store.insert(event(id = rootId, kind = 1))
        store.insert(event(id = midId, kind = 1, replyToId = rootId, rootId = rootId))
        // Deep reply: replyTo=mid, root=root — root gets +1 too
        store.insert(event(id = "deep-reply", kind = 1, replyToId = midId, rootId = rootId))

        assertEquals(2, store.replyCount(rootId)) // both replies reference root
        assertEquals(1, store.replyCount(midId))  // only the deep reply targets mid
    }

    // ── Reaction count ──────────────────────────────────────────────────────

    @Test
    fun `reactionCount increments on kind 7 targeting event`() {
        val targetId = "reaction-target"
        store.insert(event(id = targetId, kind = 1))

        for (i in 1..5) {
            store.insert(
                event(
                    id = "reaction-$i",
                    kind = 7,
                    content = "+",
                    tags = listOf(listOf("e", targetId)),
                ),
            )
        }

        assertEquals(5, store.reactionCount(targetId))
    }

    // ── Zap stats ───────────────────────────────────────────────────────────

    @Test
    fun `zapStats accumulates count and sats from kind 9735`() {
        val targetId = "zap-target"
        store.insert(event(id = targetId, kind = 1))

        val satAmounts = listOf(21L, 100L, 1000L)
        for ((i, sats) in satAmounts.withIndex()) {
            store.insert(
                event(
                    id = "zap-receipt-$i",
                    kind = 9735,
                    tags = listOf(
                        listOf("e", targetId),
                        listOf("amount", (sats * 1000).toString()), // millisats
                        listOf("description", """{"tags":[["e","$targetId"]]}"""),
                    ),
                    content = "",
                ),
            )
        }

        val stats = store.zapStats(targetId)
        assertEquals(3, stats.count)
        assertEquals(1121L, stats.totalSats)
    }

    @Test
    fun `zapStats parses real bolt11 invoice for 21 sats`() {
        val targetId = "bolt11-target"
        store.insert(event(id = targetId, kind = 1))

        // Real-format bolt11: lnbc210n = 210 nano-BTC = 21 sats
        store.insert(
            event(
                id = "zap-bolt11",
                kind = 9735,
                tags = listOf(
                    listOf("e", targetId),
                    listOf("bolt11", "lnbc210n1pj9npyypp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypq"),
                ),
                content = "",
            ),
        )

        val stats = store.zapStats(targetId)
        assertEquals(1, stats.count)
        assertEquals(21L, stats.totalSats)
    }

    // ── Kind 0 profile (replaceable) ────────────────────────────────────────

    @Test
    fun `kind 0 newer createdAt wins for profile`() {
        val pk = "profile-pubkey"

        store.insert(
            event(id = "profile-old", pubkey = pk, kind = 0, content = """{"name":"old"}""", createdAt = 100),
        )
        val first = store.getProfile(pk)
        assertNotNull(first)
        assertTrue(first!!.content.contains("old"))

        store.insert(
            event(id = "profile-new", pubkey = pk, kind = 0, content = """{"name":"new"}""", createdAt = 200),
        )
        val second = store.getProfile(pk)
        assertTrue(second!!.content.contains("new"))

        // Older event should NOT replace newer
        store.insert(
            event(id = "profile-older", pubkey = pk, kind = 0, content = """{"name":"older"}""", createdAt = 50),
        )
        val third = store.getProfile(pk)
        assertTrue(third!!.content.contains("new"))
    }

    // ── Kind 3 follows (replaceable) ────────────────────────────────────────

    @Test
    fun `kind 3 latest replaceable wins by createdAt`() {
        val pk = "follows-pubkey"

        store.insert(
            event(
                id = "follows-5", pubkey = pk, kind = 3, createdAt = 100,
                tags = (1..5).map { listOf("p", "user-$it") },
            ),
        )
        assertEquals(5, store.getFollows(pk)?.size)

        store.insert(
            event(
                id = "follows-3", pubkey = pk, kind = 3, createdAt = 200,
                tags = (1..3).map { listOf("p", "user-$it") },
            ),
        )
        assertEquals(3, store.getFollows(pk)?.size)

        // Older event should NOT replace newer
        store.insert(
            event(
                id = "follows-7", pubkey = pk, kind = 3, createdAt = 50,
                tags = (1..7).map { listOf("p", "user-$it") },
            ),
        )
        assertEquals(3, store.getFollows(pk)?.size)
    }

    // ── Kind 10000 mute list (replaceable) ──────────────────────────────────

    @Test
    fun `kind 10000 latest replaceable wins by createdAt`() {
        val pk = "mute-pubkey"

        store.insert(
            event(
                id = "mute-ab", pubkey = pk, kind = 10000, createdAt = 100,
                tags = listOf(listOf("p", "A"), listOf("p", "B")),
            ),
        )
        assertEquals(setOf("A", "B"), store.getMuteList(pk)?.pubkeys)

        store.insert(
            event(
                id = "mute-c", pubkey = pk, kind = 10000, createdAt = 200,
                tags = listOf(listOf("p", "C")),
            ),
        )
        assertEquals(setOf("C"), store.getMuteList(pk)?.pubkeys)

        // Older event should NOT replace newer
        store.insert(
            event(
                id = "mute-d", pubkey = pk, kind = 10000, createdAt = 50,
                tags = listOf(listOf("p", "D")),
            ),
        )
        assertEquals(setOf("C"), store.getMuteList(pk)?.pubkeys)
    }

    // ── Kind 10002 relay list (replaceable) ─────────────────────────────────

    @Test
    fun `kind 10002 latest replaceable wins by createdAt`() {
        val pk = "relay-pubkey"

        store.insert(
            event(
                id = "relaylist-1", pubkey = pk, kind = 10002, createdAt = 100,
                tags = listOf(listOf("r", "wss://a", "")),
            ),
        )

        store.insert(
            event(
                id = "relaylist-2", pubkey = pk, kind = 10002, createdAt = 200,
                tags = listOf(listOf("r", "wss://b", "write")),
            ),
        )

        val rl = store.getRelayList(pk)
        assertNotNull(rl)
        assertEquals(listOf("wss://b"), rl!!.write)
        assertEquals(emptyList<String>(), rl.read)
    }

    @Test
    fun `kind 10002 relay list parses read and write tags`() {
        val pk = "relay-rw-pubkey"

        store.insert(
            event(
                id = "relaylist-rw", pubkey = pk, kind = 10002, createdAt = 100,
                tags = listOf(
                    listOf("r", "wss://x", "read"),
                    listOf("r", "wss://y", "write"),
                    listOf("r", "wss://z"),
                ),
            ),
        )

        val rl = store.getRelayList(pk)!!
        assertTrue(rl.read.contains("wss://x"))
        assertTrue(rl.read.contains("wss://z"))
        assertTrue(rl.write.contains("wss://y"))
        assertTrue(rl.write.contains("wss://z"))
        assertFalse(rl.read.contains("wss://y"))
        assertFalse(rl.write.contains("wss://x"))
    }

    // ── Kind 30002 parameterized replaceable ────────────────────────────────

    @Test
    fun `kind 30002 parameterized replaceable wins by createdAt per d-tag`() {
        val pk = "relayset-pubkey"

        store.insert(
            event(
                id = "set1-v1", pubkey = pk, kind = 30002, createdAt = 100,
                tags = listOf(listOf("d", "set1"), listOf("r", "wss://old")),
            ),
        )
        store.insert(
            event(
                id = "set1-v2", pubkey = pk, kind = 30002, createdAt = 200,
                tags = listOf(listOf("d", "set1"), listOf("r", "wss://new")),
            ),
        )
        store.insert(
            event(
                id = "set2-v1", pubkey = pk, kind = 30002, createdAt = 150,
                tags = listOf(listOf("d", "set2"), listOf("r", "wss://set2relay")),
            ),
        )

        // set1 should reflect the createdAt=200 version
        val set1 = store.eventsByIds(setOf("set1-v2")).firstOrNull()
        assertNotNull(set1)
        assertTrue(set1!!.tags.any { it.size >= 2 && it[0] == "r" && it[1] == "wss://new" })

        // set2 should reflect the createdAt=150 version
        val set2 = store.eventsByIds(setOf("set2-v1")).firstOrNull()
        assertNotNull(set2)
        assertTrue(set2!!.tags.any { it.size >= 2 && it[0] == "r" && it[1] == "wss://set2relay" })
    }

    // ── Feed query: sorting ─────────────────────────────────────────────────

    @Test
    fun `feedEvents sorts by createdAt descending`() {
        val timestamps = (1..100).shuffled()
        for (t in timestamps) {
            store.insert(event(id = "sort-$t", kind = 1, createdAt = t.toLong()))
        }

        val result = store.feedEvents(defaultFilter)
        val createdAts = result.map { it.createdAt }
        assertEquals(createdAts.sortedDescending(), createdAts)
    }

    // ── Feed query: limit ───────────────────────────────────────────────────

    @Test
    fun `feedEvents respects limit parameter`() {
        for (i in 1..500) {
            store.insert(event(id = "limit-$i", kind = 1, createdAt = i.toLong()))
        }

        val result = store.feedEvents(defaultFilter, limit = 300)
        assertEquals(300, result.size)
    }

    // ── Feed query: kind filter ─────────────────────────────────────────────

    @Test
    fun `feedEvents filters by kind`() {
        store.insert(event(id = "k1", kind = 1))
        store.insert(event(id = "k6", kind = 6, rootId = "k1"))
        store.insert(event(id = "k7", kind = 7, tags = listOf(listOf("e", "k1"))))
        store.insert(event(id = "k30023", kind = 30023))

        val filter = FeedFilter(kinds = setOf(1, 6))
        val result = store.feedEvents(filter)
        val kinds = result.map { it.kind }.toSet()

        assertTrue(kinds.contains(1))
        assertTrue(kinds.contains(6))
        assertFalse(kinds.contains(7))
        assertFalse(kinds.contains(30023))
    }

    // ── userEvents ──────────────────────────────────────────────────────────

    @Test
    fun `userEvents filters by pubkey`() {
        val pks = (1..5).map { "pk-$it" }
        for (pk in pks) {
            for (i in 1..3) {
                store.insert(event(id = "$pk-evt-$i", pubkey = pk, kind = 1, createdAt = i.toLong()))
            }
        }

        val result = store.userEvents("pk-3", kinds = setOf(1))
        assertEquals(3, result.size)
        assertTrue(result.all { it.pubkey == "pk-3" })
    }

    // ── threadEvents ────────────────────────────────────────────────────────

    @Test
    fun `threadEvents returns root + all descendants`() {
        val rootId = "thread-root"
        store.insert(event(id = rootId, kind = 1, createdAt = 1))
        store.insert(event(id = "r1", kind = 1, replyToId = rootId, rootId = rootId, createdAt = 2))
        store.insert(event(id = "r2", kind = 1, replyToId = rootId, rootId = rootId, createdAt = 3))
        store.insert(event(id = "r3", kind = 1, replyToId = rootId, rootId = rootId, createdAt = 4))
        store.insert(event(id = "r1-1", kind = 1, replyToId = "r1", rootId = rootId, createdAt = 5))

        val thread = store.threadEvents(rootId)
        assertEquals(5, thread.size)
    }

    // ── Feed flow reactivity ────────────────────────────────────────────────

    @Test
    fun `feed flow eventually emits after insert`() = runTest {
        store.feedFlow(defaultFilter).test {
            val initial = awaitItem()
            assertTrue(initial.isEmpty())

            val evt = event(id = "flow-test", kind = 1, createdAt = 99999)
            store.insert(evt)

            val emitted = awaitItem()
            assertTrue(emitted.any { it.id == "flow-test" })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Snapshot round-trip ─────────────────────────────────────────────────

    @Test
    fun `snapshot round-trip preserves all events and aggregates`() = runTest {
        // Insert a mix of events
        val parent = event(id = "snap-parent", kind = 1, createdAt = 100)
        store.insert(parent)
        store.insert(event(id = "snap-reply", kind = 1, replyToId = "snap-parent", createdAt = 101))
        store.insert(event(id = "snap-repost", kind = 6, rootId = "snap-parent", createdAt = 102))
        store.insert(
            event(
                id = "snap-reaction", kind = 7,
                tags = listOf(listOf("e", "snap-parent")),
                createdAt = 103,
            ),
        )
        store.insert(
            event(
                id = "snap-profile", pubkey = "snap-pk", kind = 0,
                content = """{"name":"snapper"}""", createdAt = 200,
            ),
        )
        store.insert(
            event(
                id = "snap-follows", pubkey = "snap-pk", kind = 3,
                tags = listOf(listOf("p", "f1"), listOf("p", "f2")),
                createdAt = 201,
            ),
        )

        // Save snapshot
        val tmpFile = java.io.File.createTempFile("snapshot-test", ".bin")
        try {
            store.saveSnapshot(tmpFile)

            // Restore into fresh store
            val restored = MemoryEventStore()
            restored.restoreFromSnapshot(tmpFile)

            // Verify events
            assertEquals(6, restored.eventsByIds(
                setOf("snap-parent", "snap-reply", "snap-repost", "snap-reaction", "snap-profile", "snap-follows"),
            ).size)

            // Verify aggregates
            assertEquals(1, restored.replyCount("snap-parent"))
            assertEquals(1, restored.repostCount("snap-parent"))
            assertEquals(1, restored.reactionCount("snap-parent"))

            // Verify profile
            val profile = restored.getProfile("snap-pk")
            assertNotNull(profile)
            assertTrue(profile!!.content.contains("snapper"))

            // Verify follows
            assertEquals(setOf("f1", "f2"), restored.getFollows("snap-pk"))
        } finally {
            tmpFile.delete()
        }
    }

    // ── trimToLast ──────────────────────────────────────────────────────────

    @Test
    fun `trimToLast retains exactly the most recent N events by createdAt`() {
        for (i in 1..10000) {
            store.insert(event(id = "trim-$i", kind = 1, createdAt = i.toLong()))
        }

        store.trimToLast(5000)

        // Should retain exactly 5000
        val remaining = store.feedEvents(defaultFilter, limit = 10000)
        assertEquals(5000, remaining.size)

        // Should be the most recent 5000 (createdAt 5001..10000)
        val minCreatedAt = remaining.minOf { it.createdAt }
        assertTrue(minCreatedAt >= 5001L)
    }

    // ── clear ───────────────────────────────────────────────────────────────

    @Test
    fun `clear empties all state`() {
        store.insert(event(id = "clear-1", kind = 1))
        store.insert(
            event(id = "clear-profile", pubkey = "cpk", kind = 0, content = """{"name":"x"}"""),
        )
        store.insert(
            event(
                id = "clear-follows", pubkey = "cpk", kind = 3,
                tags = listOf(listOf("p", "f1")),
            ),
        )

        store.clear()

        assertTrue(store.eventsByIds(setOf("clear-1")).isEmpty())
        assertNull(store.getProfile("cpk"))
        assertNull(store.getFollows("cpk"))
        assertEquals(0, store.replyCount("clear-1"))
    }

    // ── followingFeedEvents ─────────────────────────────────────────────────

    @Test
    fun `followingFeedEvents returns only events from followed pubkeys`() {
        val followed = setOf("alice", "bob")
        store.insert(event(id = "alice-1", pubkey = "alice", kind = 1, createdAt = 3))
        store.insert(event(id = "bob-1", pubkey = "bob", kind = 1, createdAt = 2))
        store.insert(event(id = "charlie-1", pubkey = "charlie", kind = 1, createdAt = 1))

        val filter = FeedFilter(kinds = setOf(1), followedPubkeys = followed)
        val result = store.feedEvents(filter)

        assertEquals(2, result.size)
        assertTrue(result.all { it.pubkey in followed })
    }

    // ── hasProfile ──────────────────────────────────────────────────────────

    @Test
    fun `hasProfile returns false before and true after kind 0 insert`() {
        assertFalse(store.hasProfile("new-pk"))
        store.insert(event(id = "hp-profile", pubkey = "new-pk", kind = 0, content = """{"name":"test"}"""))
        assertTrue(store.hasProfile("new-pk"))
    }

    // ── statsLastUpdated ────────────────────────────────────────────────────

    @Test
    fun `statsLastUpdated updates on engagement insert`() {
        val targetId = "stats-ts-target"
        store.insert(event(id = targetId, kind = 1))

        assertEquals(0L, store.statsLastUpdated(targetId))

        store.insert(
            event(
                id = "stats-reaction", kind = 7,
                tags = listOf(listOf("e", targetId)),
            ),
        )

        assertTrue(store.statsLastUpdated(targetId) > 0L)
    }

    // ── writeRelaysFor / readRelaysFor ───────────────────────────────────────

    @Test
    fun `writeRelaysFor and readRelaysFor use relay list`() {
        val pk = "relay-routing-pk"
        store.insert(
            event(
                id = "routing-rl", pubkey = pk, kind = 10002, createdAt = 100,
                tags = listOf(
                    listOf("r", "wss://write-only", "write"),
                    listOf("r", "wss://read-only", "read"),
                    listOf("r", "wss://both"),
                ),
            ),
        )

        val write = store.writeRelaysFor(pk)
        val read = store.readRelaysFor(pk)

        assertTrue(write.contains("wss://write-only"))
        assertTrue(write.contains("wss://both"))
        assertFalse(write.contains("wss://read-only"))

        assertTrue(read.contains("wss://read-only"))
        assertTrue(read.contains("wss://both"))
        assertFalse(read.contains("wss://write-only"))
    }

    // ── Snapshot benchmark (informational — prints metrics) ──────────────────

    @Test
    fun `snapshot 1000 events - measure size and time`() = runTest {
        // Insert 1000 events with realistic-sized content and tags
        for (i in 1..1000) {
            val tags = mutableListOf<List<String>>()
            if (i % 3 == 0) tags.add(listOf("e", "ref-${i % 50}", "", "reply"))
            if (i % 5 == 0) tags.add(listOf("p", "pk-${i % 20}"))
            tags.add(listOf("t", "nostr"))

            store.insert(
                event(
                    id = "bench-$i",
                    pubkey = "pk-${i % 20}",
                    kind = if (i % 10 == 0) 6 else 1,
                    content = "This is test note #$i with some realistic content length that a typical Nostr note might have, including mentions of @npub1abc and links to https://example.com/page/$i",
                    createdAt = 1700000000L + i,
                    tags = tags,
                    relayUrl = "wss://relay${i % 5}.example.com",
                    replyToId = if (i % 3 == 0) "ref-${i % 50}" else null,
                    rootId = if (i % 3 == 0) "ref-${i % 50}" else null,
                ),
            )
        }
        // Add some profiles and follows
        for (i in 0 until 20) {
            store.insert(
                event(
                    id = "bench-profile-$i", pubkey = "pk-$i", kind = 0,
                    content = """{"name":"user$i","display_name":"User $i","about":"Bio for user $i","picture":"https://example.com/avatar/$i.jpg","nip05":"user$i@example.com"}""",
                    createdAt = 1700000000L + i,
                ),
            )
        }

        val tmpFile = java.io.File.createTempFile("bench-snapshot", ".bin")
        try {
            // Measure save
            val saveStart = System.nanoTime()
            store.saveSnapshot(tmpFile)
            val saveMs = (System.nanoTime() - saveStart) / 1_000_000.0

            val fileSizeKB = tmpFile.length() / 1024.0

            // Measure restore
            val restored = MemoryEventStore()
            val restoreStart = System.nanoTime()
            restored.restoreFromSnapshot(tmpFile)
            val restoreMs = (System.nanoTime() - restoreStart) / 1_000_000.0

            // Print metrics (visible in test output)
            println("=== Snapshot benchmark (1000 events + 20 profiles) ===")
            println("  File size:    %.1f KB".format(fileSizeKB))
            println("  Save time:    %.1f ms".format(saveMs))
            println("  Restore time: %.1f ms".format(restoreMs))
            println("  Projected 5k: %.1f KB".format(fileSizeKB * 5))
            println("======================================================")

            // Verify round-trip correctness
            assertEquals(1020, restored.eventsByIds(
                (1..1000).map { "bench-$it" }.toSet() + (0 until 20).map { "bench-profile-$it" }.toSet(),
            ).size)
        } finally {
            tmpFile.delete()
        }
    }
}
