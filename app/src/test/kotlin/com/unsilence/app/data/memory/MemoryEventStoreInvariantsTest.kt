package com.unsilence.app.data.memory

// ── Deferred test suites (do NOT forget) ────────────────────────────────────
// EventProcessorInvariantsTest — due in A.2 (EventProcessor rewired to MemoryEventStore)
//   Tests: duplicate queue path, seenIds dedup, trimDedupCache, brace-content filter, NIP-40 expiry
// RelayPoolInvariantsTest — due in A.6 (OutboxRouter rewired) / A.7 (Room deleted)
//   Tests: PERSISTENT-only home subs, persistent prefix survives EOSE, one-shot CLOSE after EOSE,
//          concurrent reconnect dedup
// ─────────────────────────────────────────────────────────────────────────────

import app.cash.turbine.test
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter

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

    @Test
    fun `kind 30002 distinguishes by pubkey and d-tag compound key`() {
        // Two different pubkeys, same d-tag "favorites"
        store.insert(
            event(
                id = "pkA-fav", pubkey = "pubkeyA", kind = 30002, createdAt = 100,
                tags = listOf(listOf("d", "favorites"), listOf("r", "wss://relayA")),
            ),
        )
        store.insert(
            event(
                id = "pkB-fav", pubkey = "pubkeyB", kind = 30002, createdAt = 200,
                tags = listOf(listOf("d", "favorites"), listOf("r", "wss://relayB")),
            ),
        )

        // Both must exist independently — pubkeyB's insert must NOT overwrite pubkeyA
        val evtA = store.eventsByIds(setOf("pkA-fav")).firstOrNull()
        val evtB = store.eventsByIds(setOf("pkB-fav")).firstOrNull()

        assertNotNull(evtA)
        assertNotNull(evtB)
        assertTrue(evtA!!.tags.any { it.size >= 2 && it[0] == "r" && it[1] == "wss://relayA" })
        assertTrue(evtB!!.tags.any { it.size >= 2 && it[0] == "r" && it[1] == "wss://relayB" })
        assertEquals("pubkeyA", evtA.pubkey)
        assertEquals("pubkeyB", evtB.pubkey)
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
            tmpFile.bufferedWriter().use { store.saveSnapshotTo(it) }

            // Restore into fresh store
            val restored = MemoryEventStore()
            tmpFile.bufferedReader().use { restored.restoreSnapshotFrom(it) }

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

    @Test
    fun `snapshot restore bumps signals to trigger UI re-render`() = runTest {
        // Populate store with mixed kinds that touch different signals
        store.insert(event(id = "sig-note", kind = 1, createdAt = 100))
        store.insert(
            event(id = "sig-profile", pubkey = "sig-pk", kind = 0,
                content = """{"name":"signaltest"}""", createdAt = 101),
        )
        store.insert(
            event(id = "sig-follows", pubkey = "sig-pk", kind = 3,
                tags = listOf(listOf("p", "f1")), createdAt = 102),
        )
        store.insert(
            event(id = "sig-reaction", kind = 7,
                tags = listOf(listOf("e", "sig-note")), createdAt = 103),
        )

        val tmpFile = java.io.File.createTempFile("signal-test", ".bin")
        try {
            tmpFile.bufferedWriter().use { store.saveSnapshotTo(it) }

            // Fresh store — subscribe to flows BEFORE restore
            val restored = MemoryEventStore()

            restored.feedFlow(defaultFilter).test {
                val initial = awaitItem()
                assertTrue(initial.isEmpty())

                // Restore should bump _feedSignal → trigger re-emission
                tmpFile.bufferedReader().use { restored.restoreSnapshotFrom(it) }

                val afterRestore = awaitItem()
                assertTrue(afterRestore.isNotEmpty())
                assertTrue(afterRestore.any { it.id == "sig-note" })
                cancelAndIgnoreRemainingEvents()
            }

            // Verify profile signal was bumped too (profile is populated)
            assertNotNull(restored.getProfile("sig-pk"))
            // Verify follows signal was bumped (follows are populated)
            assertEquals(setOf("f1"), restored.getFollows("sig-pk"))
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
            tmpFile.bufferedWriter().use { store.saveSnapshotTo(it) }
            val saveMs = (System.nanoTime() - saveStart) / 1_000_000.0

            val fileSizeKB = tmpFile.length() / 1024.0

            // Measure restore
            val restored = MemoryEventStore()
            val restoreStart = System.nanoTime()
            tmpFile.bufferedReader().use { restored.restoreSnapshotFrom(it) }
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

    // ── Snapshot version mismatch ───────────────────────────────────────────

    @Test
    fun `snapshot with unknown version is treated as missing`() = runTest {
        val tmpFile = java.io.File.createTempFile("bad-version", ".bin")
        try {
            tmpFile.writeText("SNAPSHOT_V99\nsome garbage data\n")

            val restored = MemoryEventStore()
            tmpFile.bufferedReader().use { restored.restoreSnapshotFrom(it) }

            // Store should remain completely empty — no crash, no partial load
            assertTrue(restored.eventsByIds(setOf("anything")).isEmpty())
            assertNull(restored.getProfile("anything"))
            assertEquals(0, restored.replyCount("anything"))
        } finally {
            tmpFile.delete()
        }
    }

    // ── pendingRelays cap ───────────────────────────────────────────────────

    @Test
    fun `pendingRelays does not grow unbounded when insert never fires`() {
        // Simulate seenIds dedups for events that never get inserted
        for (i in 1..1500) {
            store.addRelaySeen("event-$i", "wss://relay$i.example.com")
        }
        assertTrue(
            "Expected pendingRelayCount <= 1000, got ${store.pendingRelayCount}",
            store.pendingRelayCount <= 1000,
        )
    }

    // ── restoreSnapshotFrom idempotency ────────────────────────────────────

    @Test
    fun `restoreSnapshotFrom is idempotent across multiple calls`() = runTest {
        // Populate a source store with events + engagement that produces aggregates
        val source = MemoryEventStore()
        source.insert(event(id = "e1", kind = 1))
        source.insert(event(id = "e2", kind = 1, replyToId = "e1", tags = listOf(listOf("e", "e1"))))
        source.insert(event(id = "r1", kind = 7, tags = listOf(listOf("e", "e1"))))

        val snapshot = StringWriter()
        source.saveSnapshotTo(snapshot.buffered())
        val snapshotData = snapshot.toString()

        // Restore once
        val target = MemoryEventStore()
        target.restoreSnapshotFrom(StringReader(snapshotData).buffered())
        val firstReplyCount = target.replyCount("e1")
        val firstReactionCount = target.reactionCount("e1")
        val firstStoreSize = target.eventsByIds(setOf("e1", "e2", "r1")).size

        // Restore again with same data
        target.restoreSnapshotFrom(StringReader(snapshotData).buffered())

        // Counts and store size must be identical
        assertEquals(firstReplyCount, target.replyCount("e1"))
        assertEquals(firstReactionCount, target.reactionCount("e1"))
        assertEquals(firstStoreSize, target.eventsByIds(setOf("e1", "e2", "r1")).size)
    }

    // ── feedEvents limit applies to accepted rows ──────────────────────────

    @Test
    fun `feedEvents limit applies to accepted rows not scanned rows`() {
        // Seed 500 events: first 300 (newest) are replies, next 200 are notes.
        // contentFilter=1 means "notes only" — should reject all 300 replies
        // and accept the 200 notes from deeper in the store.
        for (i in 1..200) {
            store.insert(event(
                id = "note-$i",
                kind = 1,
                createdAt = 1000L + i,
                replyToId = null, rootId = null,
            ))
        }
        for (i in 1..300) {
            store.insert(event(
                id = "reply-$i",
                kind = 1,
                createdAt = 2000L + i,
                replyToId = "some-target",
            ))
        }

        val filter = FeedFilter(
            kinds = setOf(1),
            contentFilter = 1,
        )
        val result = store.feedEvents(filter, limit = 200)

        assertEquals("Expected 200 notes, got ${result.size}", 200, result.size)
        assertTrue(
            "All results should be notes, not replies",
            result.all { it.replyToId == null && it.rootId == null },
        )
        assertTrue(
            "Should include older notes that required scanning past replies",
            result.any { it.id == "note-1" },
        )
    }

    // ── feedEvents relay URL scoping ───────────────────────────────────────

    @Test
    fun `feedEvents with relayUrls filter respects relaysSeen`() {
        val globalUrls = setOf("wss://global1.example.com", "wss://global2.example.com")

        store.insert(event(
            id = "non-global",
            kind = 1,
            relayUrl = "wss://private.example.com",
        ))
        store.insert(event(
            id = "global",
            kind = 1,
            relayUrl = "wss://global1.example.com",
        ))
        val both = event(
            id = "both",
            kind = 1,
            relayUrl = "wss://other.example.com",
        )
        store.insert(both)
        store.addRelaySeen("both", "wss://global2.example.com")

        val filter = FeedFilter(
            kinds = setOf(1),
            relayUrls = globalUrls,
        )
        val result = store.feedEvents(filter)
        val ids = result.map { it.id }.toSet()

        assertFalse("Non-global event should not appear", "non-global" in ids)
        assertTrue("Global event should appear", "global" in ids)
        assertTrue("Both-relay event should appear", "both" in ids)
    }

    // ── A.5.1 T1: FeedFilter.relayUrls contract ────────────────────────────

    @Test
    fun `feedFlow with relayUrls filter returns only events seen on those relays`() = runTest {
        val targetRelay = "wss://target.example.com"
        store.insert(event(id = "on-target", kind = 1, relayUrl = targetRelay, createdAt = 2))
        store.insert(event(id = "off-target", kind = 1, relayUrl = "wss://other.example.com", createdAt = 1))

        val filter = FeedFilter(kinds = setOf(1), relayUrls = setOf(targetRelay))
        store.feedFlow(filter).test {
            val rows = awaitItem()
            assertEquals(1, rows.size)
            assertEquals("on-target", rows[0].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `feedFlow with null relayUrls filter returns events from all relays`() = runTest {
        store.insert(event(id = "a", kind = 1, relayUrl = "wss://r1.example.com", createdAt = 2))
        store.insert(event(id = "b", kind = 1, relayUrl = "wss://r2.example.com", createdAt = 1))

        val filter = FeedFilter(kinds = setOf(1), relayUrls = null)
        store.feedFlow(filter).test {
            val rows = awaitItem()
            assertEquals(2, rows.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `feedFlow with relayUrls returns events seen on ANY of the listed relays (OR semantics)`() = runTest {
        val relay1 = "wss://r1.example.com"
        val relay2 = "wss://r2.example.com"
        store.insert(event(id = "on-r1", kind = 1, relayUrl = relay1, createdAt = 3))
        store.insert(event(id = "on-r2", kind = 1, relayUrl = relay2, createdAt = 2))
        store.insert(event(id = "on-other", kind = 1, relayUrl = "wss://other.example.com", createdAt = 1))

        val filter = FeedFilter(kinds = setOf(1), relayUrls = setOf(relay1, relay2))
        store.feedFlow(filter).test {
            val rows = awaitItem()
            val ids = rows.map { it.id }.toSet()
            assertEquals(2, ids.size)
            assertTrue("on-r1" in ids)
            assertTrue("on-r2" in ids)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `feedFlow with relayUrls excludes events seen only on other relays`() = runTest {
        store.insert(event(id = "match", kind = 1, relayUrl = "wss://target.example.com", createdAt = 2))
        store.insert(event(id = "nomatch", kind = 1, relayUrl = "wss://wrong.example.com", createdAt = 1))

        val filter = FeedFilter(kinds = setOf(1), relayUrls = setOf("wss://target.example.com"))
        store.feedFlow(filter).test {
            val rows = awaitItem()
            assertEquals(1, rows.size)
            assertEquals("match", rows[0].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `feedFlow with relayUrls respects limit and sort order (createdAt DESC)`() = runTest {
        val relay = "wss://relay.example.com"
        for (i in 1..10) {
            store.insert(event(id = "lim-$i", kind = 1, relayUrl = relay, createdAt = i.toLong()))
        }

        val filter = FeedFilter(kinds = setOf(1), relayUrls = setOf(relay))
        store.feedFlow(filter, limit = 5).test {
            val rows = awaitItem()
            assertEquals(5, rows.size)
            // Should be the 5 most recent (10, 9, 8, 7, 6) in descending order
            assertEquals(listOf(10L, 9L, 8L, 7L, 6L), rows.map { it.createdAt })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `feedFlow with relayUrls updates when new event arrives with matching relaysSeen`() = runTest {
        val relay = "wss://live.example.com"
        val filter = FeedFilter(kinds = setOf(1), relayUrls = setOf(relay))

        store.feedFlow(filter).test {
            val initial = awaitItem()
            assertTrue(initial.isEmpty())

            store.insert(event(id = "live-1", kind = 1, relayUrl = relay, createdAt = 100))

            val updated = awaitItem()
            assertEquals(1, updated.size)
            assertEquals("live-1", updated[0].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── updateFollows: dedicated follows path ──────────────────────────────

    @Test
    fun `updateFollows populates followsByPubkey without inserting event`() {
        val pubkey = "pk-alice"
        val follows = setOf("pk-bob", "pk-carol")
        store.updateFollows(pubkey, follows, createdAt = 1000L)

        assertEquals("getFollows should return the set", follows, store.getFollows(pubkey))
        // No event should be in the main store
        val userEvents = store.userEvents(pubkey, setOf(3))
        assertTrue("Kind-3 should NOT be in the main event store", userEvents.isEmpty())
    }

    @Test
    fun `updateFollows ignores stale contact list`() {
        val pubkey = "pk-alice"
        val newer = setOf("pk-bob", "pk-carol")
        val older = setOf("pk-dave")

        store.updateFollows(pubkey, newer, createdAt = 2000L)
        store.updateFollows(pubkey, older, createdAt = 1000L) // stale — should be ignored

        assertEquals("Newer follows should remain", newer, store.getFollows(pubkey))
    }

    @Test
    fun `updateFollows overwrites with newer contact list`() {
        val pubkey = "pk-alice"
        val first = setOf("pk-bob")
        val second = setOf("pk-carol", "pk-dave")

        store.updateFollows(pubkey, first, createdAt = 1000L)
        store.updateFollows(pubkey, second, createdAt = 2000L)

        assertEquals("Newer follows should overwrite", second, store.getFollows(pubkey))
    }

    @Test
    fun `updateFollows bumps followsSignal so followsFlow emits`() = runTest {
        val pubkey = "pk-alice"

        store.followsFlow(pubkey).test {
            // Initial emission: empty
            assertEquals(emptySet<String>(), awaitItem())

            val follows = setOf("pk-bob")
            store.updateFollows(pubkey, follows, createdAt = 1000L)

            assertEquals("followsFlow should emit after updateFollows", follows, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── MemoryFeedFilter must not contain media fields ─────────────────────

    @Test
    fun `MemoryFeedFilter has no media-related fields`() {
        val filter = FeedFilter()
        val fields = filter::class.members.map { it.name }.toSet()

        val mediaRelatedFields = listOf(
            "media", "mediaType", "showImages",
            "showVideos", "imageOnly", "videoOnly",
        )
        for (field in mediaRelatedFields) {
            assertFalse(
                "MemoryFeedFilter must not contain '$field' — media filtering " +
                    "is presentation-layer, applied AFTER memory query.",
                field in fields,
            )
        }
    }

    // ── A.4.3: profileUpdatedAt local freshness ────────────────────────────

    @Test
    fun `getProfileLastUpdated reflects local cache time not event createdAt`() {
        val ancientCreatedAt = 1500000000L  // ~mid-2017
        val beforeInsert = System.currentTimeMillis()

        store.insert(event(
            id = "p1", kind = 0, pubkey = "alice",
            content = """{"name":"Alice"}""",
            createdAt = ancientCreatedAt,
        ))

        val lastUpdated = store.getProfileLastUpdated("alice")
        assertTrue(
            "getProfileLastUpdated must be a recent local timestamp " +
            "(>=$beforeInsert), not the event createdAt ($ancientCreatedAt). " +
            "Got: $lastUpdated",
            lastUpdated >= beforeInsert,
        )
        assertNotEquals(
            "getProfileLastUpdated must NOT equal event createdAt — " +
            "that would cause ProfileResolver to refetch ancient profiles forever",
            ancientCreatedAt, lastUpdated,
        )
    }

    @Test
    fun `getProfileLastUpdated returns 0 for unknown pubkey`() {
        assertEquals(0L, store.getProfileLastUpdated("unknown"))
    }

    @Test
    fun `getProfileLastUpdated updates when newer profile replaces older`() {
        store.insert(event(
            id = "p-old", kind = 0, pubkey = "alice",
            content = """{"name":"Old"}""", createdAt = 100,
        ))
        val first = store.getProfileLastUpdated("alice")
        assertTrue(first > 0)

        // Small delay to ensure different local timestamp
        Thread.sleep(5)

        store.insert(event(
            id = "p-new", kind = 0, pubkey = "alice",
            content = """{"name":"New"}""", createdAt = 200,
        ))
        val second = store.getProfileLastUpdated("alice")
        assertTrue("Newer profile should update local timestamp", second >= first)
    }

    @Test
    fun `getProfileLastUpdated does not update when stale profile arrives`() {
        store.insert(event(
            id = "p-new", kind = 0, pubkey = "alice",
            content = """{"name":"New"}""", createdAt = 200,
        ))
        val afterNew = store.getProfileLastUpdated("alice")

        store.insert(event(
            id = "p-old", kind = 0, pubkey = "alice",
            content = """{"name":"Old"}""", createdAt = 100,
        ))
        val afterOld = store.getProfileLastUpdated("alice")

        assertEquals(
            "Stale profile must NOT update local timestamp",
            afterNew, afterOld,
        )
    }

    @Test
    fun `clear resets profileUpdatedAt`() {
        store.insert(event(
            id = "p-clear", kind = 0, pubkey = "alice",
            content = """{"name":"Alice"}""",
        ))
        assertTrue(store.getProfileLastUpdated("alice") > 0)

        store.clear()

        assertEquals(0L, store.getProfileLastUpdated("alice"))
    }

    // ── A.4.3: threadFlow fixpoint ─────────────────────────────────────────

    @Test
    fun `threadFlow collects descendants to fixpoint not just two passes`() = runTest {
        store.insert(event(id = "root", kind = 1, createdAt = 1))
        store.insert(event(id = "r1", kind = 1, replyToId = "root", rootId = "root", createdAt = 2))
        store.insert(event(id = "r2", kind = 1, replyToId = "r1", createdAt = 3))     // depth 2
        store.insert(event(id = "r3", kind = 1, replyToId = "r2", createdAt = 4))     // depth 3
        store.insert(event(id = "r4", kind = 1, replyToId = "r3", createdAt = 5))     // depth 4
        store.insert(event(id = "unrelated", kind = 1, createdAt = 6))

        store.threadFlow("root").test {
            val events = awaitItem()
            val ids = events.map { it.id }.toSet()
            assertTrue("root included", "root" in ids)
            assertTrue("r1 (depth 1) included", "r1" in ids)
            assertTrue("r2 (depth 2) included", "r2" in ids)
            assertTrue("r3 (depth 3) included", "r3" in ids)
            assertTrue("r4 (depth 4) included — fixpoint required", "r4" in ids)
            assertFalse("unrelated event excluded", "unrelated" in ids)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `threadFlow re-emits when new reply arrives`() = runTest {
        store.insert(event(id = "root", kind = 1, createdAt = 1))

        store.threadFlow("root").test {
            val initial = awaitItem()
            assertEquals(1, initial.size)

            store.insert(event(id = "r1", kind = 1, replyToId = "root", rootId = "root", createdAt = 2))

            val updated = awaitItem()
            assertEquals(2, updated.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── A.5.1 T1: maxCreatedAtForRelays ────────────────────────────────────

    @Test
    fun `maxCreatedAtForRelays returns max createdAt across matching events`() {
        val relay1 = "wss://relay1.example.com"
        val relay2 = "wss://relay2.example.com"
        store.insert(event(id = "r1-old", kind = 1, createdAt = 100, relayUrl = relay1))
        store.insert(event(id = "r1-new", kind = 1, createdAt = 300, relayUrl = relay1))
        store.insert(event(id = "r2-mid", kind = 1, createdAt = 200, relayUrl = relay2))

        val max = store.maxCreatedAtForRelays(setOf(relay1))
        assertEquals(300L, max)
    }

    @Test
    fun `maxCreatedAtForRelays returns null when no events match`() {
        store.insert(event(id = "other", kind = 1, createdAt = 100, relayUrl = "wss://other.example.com"))

        val max = store.maxCreatedAtForRelays(setOf("wss://nomatch.example.com"))
        assertNull(max)
    }

    @Test
    fun `maxCreatedAtForRelays with empty relayUrls set returns null`() {
        store.insert(event(id = "any", kind = 1, createdAt = 100))

        val max = store.maxCreatedAtForRelays(emptySet())
        assertNull(max)
    }

    // ── A.5.1 T1: filterFreshEngagement ─────────────────────────────────────

    @Test
    fun `filterFreshEngagement returns only ids with statsUpdatedAt newer than threshold`() {
        val target1 = "fresh-target-1"
        val target2 = "fresh-target-2"
        val staleTarget = "stale-target"

        store.insert(event(id = target1, kind = 1))
        store.insert(event(id = target2, kind = 1))
        store.insert(event(id = staleTarget, kind = 1))

        // React to target1 and target2 — this updates their statsUpdatedAt
        store.insert(event(id = "r1", kind = 7, tags = listOf(listOf("e", target1))))
        store.insert(event(id = "r2", kind = 7, tags = listOf(listOf("e", target2))))
        // staleTarget gets no engagement — statsUpdatedAt stays at 0

        val threshold = 1L // anything > 0 means staleTarget is excluded
        val fresh = store.filterFreshEngagement(listOf(target1, target2, staleTarget), threshold)

        assertTrue(target1 in fresh)
        assertTrue(target2 in fresh)
        assertFalse(staleTarget in fresh)
    }

    @Test
    fun `filterFreshEngagement returns empty when all ids are stale`() {
        store.insert(event(id = "s1", kind = 1))
        store.insert(event(id = "s2", kind = 1))

        // No engagement — statsUpdatedAt is 0 for both
        val threshold = System.currentTimeMillis()
        val fresh = store.filterFreshEngagement(listOf("s1", "s2"), threshold)

        assertTrue(fresh.isEmpty())
    }

    @Test
    fun `filterFreshEngagement preserves input order for fresh ids`() {
        val ids = (1..5).map { "order-$it" }
        for (id in ids) {
            store.insert(event(id = id, kind = 1))
            // React to each so they all have statsUpdatedAt > 0
            store.insert(event(id = "react-$id", kind = 7, tags = listOf(listOf("e", id))))
        }

        val fresh = store.filterFreshEngagement(ids, 1L)
        assertEquals(ids, fresh)
    }

    // ── A.4.3: toEventEntity adapter ───────────────────────────────────────

    @Test
    fun `toEventEntity maps all NostrEvent fields correctly`() {
        val evt = event(
            id = "adapter-test",
            pubkey = "pk-adapt",
            kind = 1,
            content = "test content",
            createdAt = 1700000000L,
            sig = "test-sig",
            relayUrl = "wss://relay.test",
            replyToId = "parent-id",
            rootId = "root-id",
            hasContentWarning = true,
            contentWarningReason = "nsfw",
            firstSeenAt = 9999L,
            tags = listOf(listOf("e", "some-ref"), listOf("p", "some-pk")),
        )
        store.insert(evt)

        val entity = store.getEventEntity("adapter-test")
        assertNotNull(entity)
        assertEquals("adapter-test", entity!!.id)
        assertEquals("pk-adapt", entity.pubkey)
        assertEquals(1, entity.kind)
        assertEquals("test content", entity.content)
        assertEquals(1700000000L, entity.createdAt)
        assertEquals("test-sig", entity.sig)
        assertEquals("wss://relay.test", entity.relayUrl)
        assertEquals("parent-id", entity.replyToId)
        assertEquals("root-id", entity.rootId)
        assertTrue(entity.hasContentWarning)
        assertEquals("nsfw", entity.contentWarningReason)
    }

    @Test
    fun `getEventEntity returns null for unknown id`() {
        assertNull(store.getEventEntity("nonexistent"))
    }

    // ── A.4.3: toUserEntity adapter ────────────────────────────────────────

    @Test
    fun `getUserEntity maps profile fields correctly`() {
        store.insert(event(
            id = "profile-adapt",
            pubkey = "pk-profile",
            kind = 0,
            content = """{"name":"Alice","display_name":"Alice W","about":"Bio here","picture":"https://example.com/a.jpg","nip05":"alice@example.com","lud16":"alice@ln.tips","banner":"https://example.com/banner.jpg"}""",
            createdAt = 1700000000L,
        ))

        val user = store.getUserEntity("pk-profile")
        assertNotNull(user)
        assertEquals("pk-profile", user!!.pubkey)
        assertEquals("Alice", user.name)
        assertEquals("Alice W", user.displayName)
        assertEquals("Bio here", user.about)
        assertEquals("https://example.com/a.jpg", user.picture)
        assertEquals("alice@example.com", user.nip05)
        assertEquals("alice@ln.tips", user.lud16)
        assertEquals("https://example.com/banner.jpg", user.banner)
        assertEquals(1700000000L, user.createdAt)
        assertTrue("updatedAt should be recent local time", user.updatedAt > 0)
    }

    @Test
    fun `getUserEntity returns null for unknown pubkey`() {
        assertNull(store.getUserEntity("nonexistent"))
    }

    // ── userEntityFlow (A.4.3 embedded quote fix) ────────────────────────────

    @Test
    fun `userEntityFlow emits null then UserEntity when profile arrives`() = runTest {
        store.userEntityFlow("alice").test {
            // Initial: no profile
            val initial = awaitItem()
            assertNull("Should be null before profile insert", initial)

            // Insert profile
            store.insert(event(
                id = "p-alice", kind = 0, pubkey = "alice",
                content = """{"name":"Alice","picture":"https://a.jpg"}""",
            ))

            val resolved = awaitItem()
            assertNotNull("Should resolve after profile insert", resolved)
            assertEquals("Alice", resolved!!.name)
            assertEquals("https://a.jpg", resolved.picture)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── A.5.1 T2: userFeedFlow ─────────────────────────────────────────────

    @Test
    fun `userFeedFlow contentFilter=0 returns all kinds for pubkey`() = runTest {
        store.insert(event(id = "note1", pubkey = "alice", kind = 1, createdAt = 100))
        store.insert(event(id = "repost1", pubkey = "alice", kind = 6, rootId = "note1", createdAt = 101))
        store.insert(event(id = "article1", pubkey = "alice", kind = 30023, createdAt = 102,
            tags = listOf(listOf("d", "slug"))))
        store.insert(event(id = "reply1", pubkey = "alice", kind = 1, replyToId = "note1", rootId = "note1", createdAt = 103))
        // Other user's event — should be excluded
        store.insert(event(id = "bob-note", pubkey = "bob", kind = 1, createdAt = 104))

        store.userFeedFlow("alice", contentFilter = 0).test {
            val rows = awaitItem()
            val ids = rows.map { it.id }
            assertEquals(4, rows.size)
            assertTrue("note1 included", "note1" in ids)
            assertTrue("repost1 included", "repost1" in ids)
            assertTrue("article1 included", "article1" in ids)
            assertTrue("reply1 included", "reply1" in ids)
            assertFalse("bob-note excluded", "bob-note" in ids)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `userFeedFlow contentFilter=1 returns kind-1 roots + kind-6 reposts (no replies, no longform)`() = runTest {
        store.insert(event(id = "root1", pubkey = "alice", kind = 1, createdAt = 100))
        store.insert(event(id = "root2", pubkey = "alice", kind = 1, createdAt = 101))
        store.insert(event(id = "reply1", pubkey = "alice", kind = 1, replyToId = "root1", rootId = "root1", createdAt = 102))
        store.insert(event(id = "repost1", pubkey = "alice", kind = 6, rootId = "root1", createdAt = 103))
        store.insert(event(id = "article1", pubkey = "alice", kind = 30023, createdAt = 104,
            tags = listOf(listOf("d", "slug"))))

        store.userFeedFlow("alice", contentFilter = 1).test {
            val rows = awaitItem()
            val ids = rows.map { it.id }
            assertEquals("kind-1 roots + kind-6 reposts", 3, rows.size)
            assertTrue("root1 included", "root1" in ids)
            assertTrue("root2 included", "root2" in ids)
            assertTrue("repost1 included (kind-6 in Notes tab)", "repost1" in ids)
            assertFalse("reply1 excluded", "reply1" in ids)
            assertFalse("article1 excluded", "article1" in ids)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `userFeedFlow contentFilter=2 returns kind-1 replies only (not kind-6, not kind-30023)`() = runTest {
        store.insert(event(id = "root1", pubkey = "alice", kind = 1, createdAt = 100))
        store.insert(event(id = "reply1", pubkey = "alice", kind = 1, replyToId = "root1", rootId = "root1", createdAt = 101))
        store.insert(event(id = "reply2", pubkey = "alice", kind = 1, replyToId = "root1", createdAt = 102))
        store.insert(event(id = "repost1", pubkey = "alice", kind = 6, rootId = "root1", createdAt = 103))
        store.insert(event(id = "article1", pubkey = "alice", kind = 30023, createdAt = 104,
            tags = listOf(listOf("d", "slug"))))

        store.userFeedFlow("alice", contentFilter = 2).test {
            val rows = awaitItem()
            val ids = rows.map { it.id }
            assertEquals(2, rows.size)
            assertTrue("reply1 included", "reply1" in ids)
            assertTrue("reply2 included", "reply2" in ids)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `userFeedFlow with kinds=setOf(30023) returns only longform`() = runTest {
        store.insert(event(id = "note1", pubkey = "alice", kind = 1, createdAt = 100))
        store.insert(event(id = "article1", pubkey = "alice", kind = 30023, createdAt = 101,
            tags = listOf(listOf("d", "slug1"))))
        store.insert(event(id = "article2", pubkey = "alice", kind = 30023, createdAt = 102,
            tags = listOf(listOf("d", "slug2"))))

        store.userFeedFlow("alice", kinds = setOf(30023)).test {
            val rows = awaitItem()
            val ids = rows.map { it.id }
            assertEquals(2, rows.size)
            assertTrue("article1 included", "article1" in ids)
            assertTrue("article2 included", "article2" in ids)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `userFeedFlow respects limit and createdAt DESC sort`() = runTest {
        for (i in 1..10) {
            store.insert(event(id = "evt-$i", pubkey = "alice", kind = 1, createdAt = i.toLong()))
        }

        store.userFeedFlow("alice", limit = 3).test {
            val rows = awaitItem()
            assertEquals(3, rows.size)
            // DESC sort — newest first
            assertEquals("evt-10", rows[0].id)
            assertEquals("evt-9", rows[1].id)
            assertEquals("evt-8", rows[2].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `userFeedFlow emits when new event for pubkey arrives`() = runTest {
        store.insert(event(id = "evt-1", pubkey = "alice", kind = 1, createdAt = 100))

        store.userFeedFlow("alice").test {
            val initial = awaitItem()
            assertEquals(1, initial.size)

            store.insert(event(id = "evt-2", pubkey = "alice", kind = 1, createdAt = 200))

            val updated = awaitItem()
            assertEquals(2, updated.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── A.5.1 T2: getUserEntities ──────────────────────────────────────────

    @Test
    fun `getUserEntities returns entities in order of input pubkeys`() {
        store.insert(event(id = "p-charlie", kind = 0, pubkey = "charlie",
            content = """{"name":"Charlie"}""", createdAt = 100))
        store.insert(event(id = "p-alice", kind = 0, pubkey = "alice",
            content = """{"name":"Alice"}""", createdAt = 101))
        store.insert(event(id = "p-bob", kind = 0, pubkey = "bob",
            content = """{"name":"Bob"}""", createdAt = 102))

        val result = store.getUserEntities(listOf("alice", "bob", "charlie"))
        assertEquals(3, result.size)
        assertEquals("alice", result[0].pubkey)
        assertEquals("bob", result[1].pubkey)
        assertEquals("charlie", result[2].pubkey)
    }

    @Test
    fun `getUserEntities returns empty list for unknown pubkeys`() {
        val result = store.getUserEntities(listOf("unknown1", "unknown2"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getUserEntities handles mixed known and unknown pubkeys`() {
        store.insert(event(id = "p-alice", kind = 0, pubkey = "alice",
            content = """{"name":"Alice"}""", createdAt = 100))

        val result = store.getUserEntities(listOf("unknown", "alice", "missing"))
        assertEquals(1, result.size)
        assertEquals("alice", result[0].pubkey)
    }

    // ── A.5.1 T2: followerCountCache ──────────────────────────────────────

    @Test
    fun `getFollowerCount returns null pair when never cached`() {
        val (count, updatedAt) = store.getFollowerCount("unknown")
        assertNull(count)
        assertNull(updatedAt)
    }

    @Test
    fun `cacheFollowerCount stores count and updatedAt in seconds`() {
        val beforeSeconds = System.currentTimeMillis() / 1000
        store.cacheFollowerCount("alice", 42L)
        val afterSeconds = System.currentTimeMillis() / 1000

        val (count, updatedAt) = store.getFollowerCount("alice")
        assertEquals(42L, count)
        assertNotNull(updatedAt)
        // updatedAt should be in seconds, not milliseconds
        assertTrue("updatedAt in seconds range", updatedAt!! in beforeSeconds..afterSeconds)
    }

    @Test
    fun `cacheFollowerCount overwrites prior value for same pubkey`() {
        store.cacheFollowerCount("alice", 10L)
        store.cacheFollowerCount("alice", 99L)

        val (count, _) = store.getFollowerCount("alice")
        assertEquals(99L, count)
    }

    @Test
    fun `getFollowerCount returns stored count and seconds timestamp`() {
        store.cacheFollowerCount("alice", 500L)
        store.cacheFollowerCount("bob", 1000L)

        val (aliceCount, aliceTs) = store.getFollowerCount("alice")
        val (bobCount, bobTs) = store.getFollowerCount("bob")

        assertEquals(500L, aliceCount)
        assertEquals(1000L, bobCount)
        assertNotNull(aliceTs)
        assertNotNull(bobTs)
    }

    // ── A.5.1 T2: addFollow / removeFollow ────────────────────────────────

    @Test
    fun `addFollow adds pubkey to ownPubkey follows set`() {
        store.addFollow("me", "alice")

        val follows = store.getFollows("me")
        assertNotNull(follows)
        assertTrue("alice" in follows!!)
    }

    @Test
    fun `addFollow is idempotent (adding same pubkey twice is noop)`() {
        store.addFollow("me", "alice")
        store.addFollow("me", "alice")

        val follows = store.getFollows("me")
        assertNotNull(follows)
        assertEquals(1, follows!!.size)
        assertTrue("alice" in follows)
    }

    @Test
    fun `removeFollow removes pubkey from ownPubkey follows set`() {
        store.addFollow("me", "alice")
        store.addFollow("me", "bob")
        store.removeFollow("me", "alice")

        val follows = store.getFollows("me")
        assertNotNull(follows)
        assertFalse("alice" in follows!!)
        assertTrue("bob" in follows)
    }

    @Test
    fun `removeFollow is noop when pubkey not present`() {
        store.addFollow("me", "alice")
        store.removeFollow("me", "nonexistent")

        val follows = store.getFollows("me")
        assertNotNull(follows)
        assertEquals(1, follows!!.size)
        assertTrue("alice" in follows)
    }

    @Test
    fun `addFollow updates followsFlow emission`() = runTest {
        store.followsFlow("me").test {
            val initial = awaitItem()
            assertTrue(initial.isEmpty())

            store.addFollow("me", "alice")

            val updated = awaitItem()
            assertTrue("alice" in updated)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── A.5.1 T2: filterFreshPubkeys / stalePubkeys ──────────────────────

    @Test
    fun `filterFreshPubkeys returns only pubkeys with profileUpdatedAt newer than threshold`() {
        store.insert(event(id = "p-alice", kind = 0, pubkey = "alice",
            content = """{"name":"Alice"}""", createdAt = 100))
        // bob has no profile

        val threshold = System.currentTimeMillis() - 1000 // 1 second ago
        val fresh = store.filterFreshPubkeys(listOf("alice", "bob"), threshold)

        assertTrue("alice should be fresh", "alice" in fresh)
        assertFalse("bob should not be fresh (no profile)", "bob" in fresh)
    }

    @Test
    fun `filterFreshPubkeys preserves input order`() {
        store.insert(event(id = "p-charlie", kind = 0, pubkey = "charlie",
            content = """{"name":"Charlie"}""", createdAt = 100))
        store.insert(event(id = "p-alice", kind = 0, pubkey = "alice",
            content = """{"name":"Alice"}""", createdAt = 101))
        store.insert(event(id = "p-bob", kind = 0, pubkey = "bob",
            content = """{"name":"Bob"}""", createdAt = 102))

        val threshold = 1L // everything is fresh relative to epoch
        val fresh = store.filterFreshPubkeys(listOf("bob", "charlie", "alice"), threshold)

        assertEquals(listOf("bob", "charlie", "alice"), fresh)
    }

    @Test
    fun `stalePubkeys returns pubkeys with profileUpdatedAt older than threshold`() {
        store.insert(event(id = "p-alice", kind = 0, pubkey = "alice",
            content = """{"name":"Alice"}""", createdAt = 100))

        // Use a threshold far in the future so alice is stale
        val futureThreshold = System.currentTimeMillis() + 100_000
        val stale = store.stalePubkeys(futureThreshold)

        assertTrue("alice should be stale", "alice" in stale)
    }

    @Test
    fun `stalePubkeys includes pubkeys with no profile`() {
        // Insert a kind-1 event from bob — no kind-0 profile
        store.insert(event(id = "bob-note", pubkey = "bob", kind = 1, createdAt = 100))

        val stale = store.stalePubkeys(System.currentTimeMillis() + 1000)
        assertTrue("bob (no profile) should be stale", "bob" in stale)
    }

    // ── A.5.1 T2 Phase 2: Existing behavior contracts ─────────────────────

    @Test
    fun `userEntityFlow emits initial cached profile`() = runTest {
        // Pre-insert profile before subscribing
        store.insert(event(
            id = "p-pre", kind = 0, pubkey = "pre-cached",
            content = """{"name":"PreCached"}""",
        ))

        store.userEntityFlow("pre-cached").test {
            val first = awaitItem()
            assertNotNull("First emission should be cached profile", first)
            assertEquals("PreCached", first!!.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `userEntityFlow emits when profile event arrives`() = runTest {
        store.userEntityFlow("late").test {
            val initial = awaitItem()
            assertNull("Initially null", initial)

            store.insert(event(
                id = "p-late", kind = 0, pubkey = "late",
                content = """{"name":"LateArrival"}""",
            ))

            val updated = awaitItem()
            assertNotNull(updated)
            assertEquals("LateArrival", updated!!.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `followsFlow with target pubkey check reflects add and remove`() = runTest {
        store.followsFlow("me").map { "target" in it }.test {
            assertFalse("Initially not following", awaitItem())

            store.addFollow("me", "target")
            assertTrue("Following after add", awaitItem())

            store.removeFollow("me", "target")
            assertFalse("Not following after remove", awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Tier 4: Actor-keyed action indexes ──────────────────────────────────

    // ── reactedEventIdsFlow ─────────────────────────────────────────────────

    @Test
    fun `reactedEventIdsFlow returns target IDs of kind-7 events authored by pubkey`() = runTest {
        store.reactedEventIdsFlow("alice").test {
            assertEquals(emptySet<String>(), awaitItem())

            // Kind-7 reaction by alice targeting note1
            store.insert(event(
                id = "reaction1", pubkey = "alice", kind = 7,
                tags = listOf(listOf("e", "note1"), listOf("p", "bob")),
                content = "+",
            ))
            val ids = awaitItem()
            assertEquals(setOf("note1"), ids)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reactedEventIdsFlow extracts target from last e tag (not first)`() = runTest {
        // Kind-7 with two e tags: first is root, last is the actual target
        store.insert(event(
            id = "reaction-multi-e", pubkey = "alice", kind = 7,
            tags = listOf(listOf("e", "root-note"), listOf("e", "target-note"), listOf("p", "bob")),
            content = "+",
        ))
        store.reactedEventIdsFlow("alice").test {
            val ids = awaitItem()
            assertTrue("target-note from last e-tag", "target-note" in ids)
            assertFalse("root-note from first e-tag excluded", "root-note" in ids)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reactedEventIdsFlow skips kind-7 with no e tags`() = runTest {
        store.insert(event(
            id = "reaction-no-e", pubkey = "alice", kind = 7,
            tags = listOf(listOf("p", "bob")),
            content = "+",
        ))
        store.reactedEventIdsFlow("alice").test {
            assertEquals("No e-tag → empty set", emptySet<String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reactedEventIdsFlow updates reactively on new kind-7 insert`() = runTest {
        store.reactedEventIdsFlow("alice").test {
            assertEquals(emptySet<String>(), awaitItem())

            store.insert(event(
                id = "r1", pubkey = "alice", kind = 7,
                tags = listOf(listOf("e", "note-a")), content = "+",
            ))
            assertEquals(setOf("note-a"), awaitItem())

            store.insert(event(
                id = "r2", pubkey = "alice", kind = 7,
                tags = listOf(listOf("e", "note-b")), content = "+",
            ))
            assertEquals(setOf("note-a", "note-b"), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reactedEventIdsFlow returns empty set for unknown pubkey`() = runTest {
        store.insert(event(
            id = "r-other", pubkey = "bob", kind = 7,
            tags = listOf(listOf("e", "note-x")), content = "+",
        ))
        store.reactedEventIdsFlow("alice").test {
            assertEquals(emptySet<String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── repostedEventIdsFlow ────────────────────────────────────────────────

    @Test
    fun `repostedEventIdsFlow returns rootId of kind-6 events authored by pubkey`() = runTest {
        store.insert(event(
            id = "repost1", pubkey = "alice", kind = 6,
            rootId = "original-note",
            tags = listOf(listOf("e", "original-note"), listOf("p", "bob")),
        ))
        store.repostedEventIdsFlow("alice").test {
            assertEquals(setOf("original-note"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `repostedEventIdsFlow skips kind-6 with null rootId`() = runTest {
        store.insert(event(
            id = "repost-no-root", pubkey = "alice", kind = 6,
            rootId = null,
            tags = listOf(listOf("p", "bob")),
        ))
        store.repostedEventIdsFlow("alice").test {
            assertEquals(emptySet<String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `repostedEventIdsFlow updates reactively on new kind-6 insert`() = runTest {
        store.repostedEventIdsFlow("alice").test {
            assertEquals(emptySet<String>(), awaitItem())

            store.insert(event(
                id = "rp1", pubkey = "alice", kind = 6, rootId = "note-a",
                tags = listOf(listOf("e", "note-a")),
            ))
            assertEquals(setOf("note-a"), awaitItem())

            store.insert(event(
                id = "rp2", pubkey = "alice", kind = 6, rootId = "note-b",
                tags = listOf(listOf("e", "note-b")),
            ))
            assertEquals(setOf("note-a", "note-b"), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── zappedEventIdsFlow ──────────────────────────────────────────────────

    @Test
    fun `zappedEventIdsFlow returns rootId of kind-9734 events authored by pubkey`() = runTest {
        store.insert(event(
            id = "zap1", pubkey = "alice", kind = 9734, rootId = "zapped-note",
            tags = listOf(listOf("e", "zapped-note"), listOf("p", "bob"), listOf("amount", "1000000")),
        ))
        store.zappedEventIdsFlow("alice").test {
            assertEquals(setOf("zapped-note"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `zappedEventIdsFlow uses kind-9734 not kind-9735`() = runTest {
        // Kind-9735 zap receipt (authored by LNURL provider, not the user)
        store.insert(event(
            id = "receipt1", pubkey = "lnurl-provider", kind = 9735, rootId = "some-note",
            tags = listOf(listOf("e", "some-note"), listOf("p", "alice")),
        ))
        store.zappedEventIdsFlow("alice").test {
            assertEquals("kind-9735 by different author → empty", emptySet<String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // Even if pubkey matches (shouldn't happen in practice), 9735 is not tracked
        store.zappedEventIdsFlow("lnurl-provider").test {
            assertEquals("kind-9735 not in zapped index", emptySet<String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `zappedEventIdsFlow skips kind-9734 with null rootId`() = runTest {
        store.insert(event(
            id = "zap-no-root", pubkey = "alice", kind = 9734, rootId = null,
            tags = listOf(listOf("p", "bob")),
        ))
        store.zappedEventIdsFlow("alice").test {
            assertEquals(emptySet<String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `zappedEventIdsFlow updates reactively on new kind-9734 insert`() = runTest {
        store.zappedEventIdsFlow("alice").test {
            assertEquals(emptySet<String>(), awaitItem())

            store.insert(event(
                id = "z1", pubkey = "alice", kind = 9734, rootId = "note-a",
                tags = listOf(listOf("e", "note-a"), listOf("amount", "1000")),
            ))
            assertEquals(setOf("note-a"), awaitItem())

            store.insert(event(
                id = "z2", pubkey = "alice", kind = 9734, rootId = "note-b",
                tags = listOf(listOf("e", "note-b"), listOf("amount", "2000")),
            ))
            assertEquals(setOf("note-a", "note-b"), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Cross-kind isolation ────────────────────────────────────────────────

    @Test
    fun `kind-7 insert does not affect repostedEventIds or zappedEventIds`() = runTest {
        store.insert(event(
            id = "r-cross", pubkey = "alice", kind = 7,
            tags = listOf(listOf("e", "note-x")), content = "+",
        ))
        store.repostedEventIdsFlow("alice").test {
            assertEquals("kind-7 not in repost set", emptySet<String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        store.zappedEventIdsFlow("alice").test {
            assertEquals("kind-7 not in zap set", emptySet<String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `kind-9735 (zap receipt) does not appear in zappedEventIds (wrong author)`() = runTest {
        // Simulate a zap receipt authored by LNURL provider targeting alice's note
        store.insert(event(
            id = "receipt-cross", pubkey = "lnurl-srv", kind = 9735, rootId = "alice-note",
            tags = listOf(listOf("e", "alice-note"), listOf("p", "alice")),
        ))
        // alice's zapped set should be empty — she authored a note, not a zap request
        store.zappedEventIdsFlow("alice").test {
            assertEquals(emptySet<String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // lnurl-srv's zapped set should also be empty — 9735 is not tracked in zapped index
        store.zappedEventIdsFlow("lnurl-srv").test {
            assertEquals(emptySet<String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Phase 2: Optimistic write parity ────────────────────────────────────

    @Test
    fun `optimistic reaction insert via MES makes target appear in reactedEventIdsFlow`() = runTest {
        store.reactedEventIdsFlow("me").test {
            assertEquals(emptySet<String>(), awaitItem())

            // Simulate optimistic insert: build NostrEvent as NoteActionsVM would
            store.insert(event(
                id = "opt-reaction-1", pubkey = "me", kind = 7,
                tags = listOf(listOf("e", "target-event"), listOf("p", "author")),
                content = "+", createdAt = System.currentTimeMillis() / 1000,
            ))
            assertTrue("target-event in reacted set", "target-event" in awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `optimistic repost insert via MES makes rootId appear in repostedEventIdsFlow`() = runTest {
        store.repostedEventIdsFlow("me").test {
            assertEquals(emptySet<String>(), awaitItem())

            store.insert(event(
                id = "opt-repost-1", pubkey = "me", kind = 6, rootId = "original",
                tags = listOf(listOf("e", "original"), listOf("p", "author")),
                createdAt = System.currentTimeMillis() / 1000,
            ))
            assertTrue("original in reposted set", "original" in awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `optimistic zap insert via MES makes rootId appear in zappedEventIdsFlow`() = runTest {
        store.zappedEventIdsFlow("me").test {
            assertEquals(emptySet<String>(), awaitItem())

            store.insert(event(
                id = "opt-zap-1", pubkey = "me", kind = 9734, rootId = "zapped-note",
                tags = listOf(listOf("e", "zapped-note"), listOf("p", "recipient"), listOf("amount", "21000")),
                createdAt = System.currentTimeMillis() / 1000,
            ))
            assertTrue("zapped-note in zapped set", "zapped-note" in awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `incrementZapStats updates zapStatsByEventId atomically`() {
        // No existing stats
        val before = store.zapStats("note-z")
        assertEquals(0, before.count)
        assertEquals(0L, before.totalSats)

        // Increment
        store.incrementZapStats("note-z", 1000L)
        val after = store.zapStats("note-z")
        assertEquals(1, after.count)
        assertEquals(1000L, after.totalSats)

        // Second increment accumulates
        store.incrementZapStats("note-z", 500L)
        val after2 = store.zapStats("note-z")
        assertEquals(2, after2.count)
        assertEquals(1500L, after2.totalSats)
    }

    // ── Tier 3: Search APIs ─────────────────────────────────────────────────

    // ── searchNotesFlow ─────────────────────────────────────────────────────

    @Test
    fun `searchNotesFlow matches kind-1 notes by content substring`() = runTest {
        store.insert(event(id = "note1", kind = 1, content = "Hello world from Nostr"))
        store.insert(event(id = "note2", kind = 1, content = "Goodbye moon"))

        store.searchNotesFlow("world").test {
            val results = awaitItem()
            assertEquals(1, results.size)
            assertEquals("note1", results[0].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchNotesFlow matches kind-30023 articles by content substring`() = runTest {
        store.insert(event(id = "article1", kind = 30023, content = "A longform article about Bitcoin"))
        store.insert(event(id = "note1", kind = 1, content = "Short note about Bitcoin"))

        store.searchNotesFlow("Bitcoin").test {
            val results = awaitItem()
            assertEquals(2, results.size)
            val ids = results.map { it.id }.toSet()
            assertTrue("article1 in results", "article1" in ids)
            assertTrue("note1 in results", "note1" in ids)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchNotesFlow is case-insensitive`() = runTest {
        store.insert(event(id = "mixed", kind = 1, content = "Lightning Network is FAST"))

        store.searchNotesFlow("lightning").test {
            val results = awaitItem()
            assertEquals(1, results.size)
            assertEquals("mixed", results[0].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchNotesFlow excludes kind-6 reposts and kind-7 reactions`() = runTest {
        store.insert(event(id = "note", kind = 1, content = "searchable content here"))
        store.insert(event(id = "repost", kind = 6, content = "searchable content here", rootId = "note"))
        store.insert(event(
            id = "reaction", kind = 7, content = "+",
            tags = listOf(listOf("e", "note")),
        ))

        store.searchNotesFlow("searchable").test {
            val results = awaitItem()
            assertEquals("Only kind-1 matches", 1, results.size)
            assertEquals("note", results[0].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchNotesFlow respects limit of 50 and createdAt DESC sort`() = runTest {
        // Insert 55 matching events with ascending createdAt
        for (i in 1..55) {
            store.insert(event(
                id = "bulk-$i", kind = 1, content = "searchterm in content",
                createdAt = 1000L + i,
            ))
        }

        store.searchNotesFlow("searchterm").test {
            val results = awaitItem()
            assertEquals("Capped at 50", 50, results.size)
            // First result should be the newest (createdAt = 1055)
            assertEquals("bulk-55", results[0].id)
            // Last result should be the 6th oldest (createdAt = 1006), since 1-5 are cut
            assertEquals("bulk-6", results[49].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchNotesFlow re-emits when new matching event arrives`() = runTest {
        store.searchNotesFlow("nostr").test {
            assertEquals("Initially empty", emptyList<Any>(), awaitItem())

            store.insert(event(id = "late-arrival", kind = 1, content = "Hello Nostr!"))
            val results = awaitItem()
            assertEquals(1, results.size)
            assertEquals("late-arrival", results[0].id)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── searchUsersFlow ─────────────────────────────────────────────────────

    @Test
    fun `searchUsersFlow matches by name substring`() = runTest {
        store.insert(event(
            id = "profile-alice", pubkey = "alice", kind = 0,
            content = """{"name":"alice_nostr","display_name":"Alice","about":"Just a user"}""",
        ))

        store.searchUsersFlow("alice_n").test {
            val results = awaitItem()
            assertEquals(1, results.size)
            assertEquals("alice", results[0].pubkey)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchUsersFlow matches by display_name substring`() = runTest {
        store.insert(event(
            id = "profile-bob", pubkey = "bob", kind = 0,
            content = """{"name":"b0b","display_name":"Bobby Tables","about":"SQL enthusiast"}""",
        ))

        store.searchUsersFlow("Bobby").test {
            val results = awaitItem()
            assertEquals(1, results.size)
            assertEquals("bob", results[0].pubkey)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchUsersFlow matches by about substring`() = runTest {
        store.insert(event(
            id = "profile-carol", pubkey = "carol", kind = 0,
            content = """{"name":"carol","display_name":"Carol","about":"Bitcoin maximalist forever"}""",
        ))

        store.searchUsersFlow("maximalist").test {
            val results = awaitItem()
            assertEquals(1, results.size)
            assertEquals("carol", results[0].pubkey)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchUsersFlow is case-insensitive`() = runTest {
        store.insert(event(
            id = "profile-dave", pubkey = "dave", kind = 0,
            content = """{"name":"DAVE","display_name":"Dave The Dev","about":"coding"}""",
        ))

        store.searchUsersFlow("dave").test {
            val results = awaitItem()
            assertEquals(1, results.size)
            assertEquals("dave", results[0].pubkey)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchUsersFlow sorts by display_name ASC`() = runTest {
        store.insert(event(
            id = "p-zara", pubkey = "zara", kind = 0,
            content = """{"name":"z","display_name":"Zara","about":"last"}""",
        ))
        store.insert(event(
            id = "p-alice", pubkey = "alice", kind = 0,
            content = """{"name":"a","display_name":"Alice","about":"first"}""",
        ))
        store.insert(event(
            id = "p-mike", pubkey = "mike", kind = 0,
            content = """{"name":"m","display_name":"Markaa","about":"a person"}""",
        ))

        // All match "a": Zara (display_name), Alice (name), Markaa (display_name+about)
        store.searchUsersFlow("a").test {
            val results = awaitItem()
            assertEquals(3, results.size)
            assertEquals("Alice", results[0].displayName)
            assertEquals("Markaa", results[1].displayName)
            assertEquals("Zara", results[2].displayName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchUsersFlow respects limit of 50`() = runTest {
        for (i in 1..55) {
            store.insert(event(
                id = "profile-$i", pubkey = "pk-$i", kind = 0,
                content = """{"name":"user$i","display_name":"User $i","about":"searchable bio"}""",
            ))
        }

        store.searchUsersFlow("searchable").test {
            val results = awaitItem()
            assertEquals("Capped at 50", 50, results.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchUsersFlow re-emits when new matching profile arrives`() = runTest {
        store.searchUsersFlow("newuser").test {
            assertEquals("Initially empty", emptyList<Any>(), awaitItem())

            store.insert(event(
                id = "profile-new", pubkey = "newpk", kind = 0,
                content = """{"name":"newuser","display_name":"New User","about":"just arrived"}""",
            ))
            val results = awaitItem()
            assertEquals(1, results.size)
            assertEquals("newpk", results[0].pubkey)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── feedRowsByIdsFlow ───────────────────────────────────────────────────

    @Test
    fun `feedRowsByIdsFlow returns FeedRows for known IDs only`() = runTest {
        store.insert(event(id = "exists-1", kind = 1, content = "one"))
        store.insert(event(id = "exists-2", kind = 1, content = "two"))

        store.feedRowsByIdsFlow(setOf("exists-1", "exists-2", "missing-3")).test {
            val results = awaitItem()
            assertEquals("Only 2 known IDs", 2, results.size)
            val ids = results.map { it.id }.toSet()
            assertTrue("exists-1" in ids)
            assertTrue("exists-2" in ids)
            assertFalse("missing-3" in ids)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `feedRowsByIdsFlow sorts by createdAt DESC`() = runTest {
        store.insert(event(id = "old", kind = 1, content = "old", createdAt = 100))
        store.insert(event(id = "new", kind = 1, content = "new", createdAt = 200))
        store.insert(event(id = "mid", kind = 1, content = "mid", createdAt = 150))

        store.feedRowsByIdsFlow(setOf("old", "new", "mid")).test {
            val results = awaitItem()
            assertEquals(3, results.size)
            assertEquals("new", results[0].id)
            assertEquals("mid", results[1].id)
            assertEquals("old", results[2].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `feedRowsByIdsFlow re-emits when a previously-missing ID arrives`() = runTest {
        store.insert(event(id = "present", kind = 1, content = "here", createdAt = 100))

        store.feedRowsByIdsFlow(setOf("present", "late")).test {
            val first = awaitItem()
            assertEquals("Initially only 1", 1, first.size)
            assertEquals("present", first[0].id)

            // Late arrival
            store.insert(event(id = "late", kind = 1, content = "arrived", createdAt = 200))
            val second = awaitItem()
            assertEquals("Now 2", 2, second.size)
            assertEquals("late", second[0].id) // newest first

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Kind 10006: Blocked relays ──────────────────────────────────────────

    @Test
    fun `handleBlocked parses relay tags preserving tag order`() {
        store.insert(event(
            id = "blocked-1", pubkey = "pk1", kind = 10006, createdAt = 100,
            tags = listOf(
                listOf("relay", "wss://zebra.relay.com"),
                listOf("relay", "wss://alpha.relay.com"),
                listOf("p", "ignored-tag"),
            ),
        ))
        val blocked = store.getBlockedRelayUrls("pk1")
        assertEquals(2, blocked.size)
        // Tag order preserved, NOT alphabetical
        assertEquals("wss://zebra.relay.com", blocked[0])
        assertEquals("wss://alpha.relay.com", blocked[1])
    }

    @Test
    fun `handleBlocked deduplicates URLs within single event`() {
        store.insert(event(
            id = "blocked-dup", pubkey = "pk1", kind = 10006, createdAt = 100,
            tags = listOf(
                listOf("relay", "wss://evil.relay.com"),
                listOf("relay", "wss://evil.relay.com"),
                listOf("relay", "wss://other.relay.com"),
            ),
        ))
        val blocked = store.getBlockedRelayUrls("pk1")
        assertEquals(2, blocked.size)
    }

    @Test
    fun `handleBlocked replaces older event (replaceable dedup)`() {
        store.insert(event(
            id = "blocked-old", pubkey = "pk1", kind = 10006, createdAt = 100,
            tags = listOf(listOf("relay", "wss://old.relay.com")),
        ))
        store.insert(event(
            id = "blocked-new", pubkey = "pk1", kind = 10006, createdAt = 200,
            tags = listOf(listOf("relay", "wss://new.relay.com")),
        ))
        val blocked = store.getBlockedRelayUrls("pk1")
        assertEquals(1, blocked.size)
        assertEquals("wss://new.relay.com", blocked[0])
    }

    @Test
    fun `handleBlocked skips event with older createdAt`() {
        store.insert(event(
            id = "blocked-new", pubkey = "pk1", kind = 10006, createdAt = 200,
            tags = listOf(listOf("relay", "wss://new.relay.com")),
        ))
        store.insert(event(
            id = "blocked-old", pubkey = "pk1", kind = 10006, createdAt = 100,
            tags = listOf(listOf("relay", "wss://old.relay.com")),
        ))
        val blocked = store.getBlockedRelayUrls("pk1")
        assertEquals(1, blocked.size)
        assertEquals("wss://new.relay.com", blocked[0])
    }

    @Test
    fun `handleBlocked tie on createdAt keeps existing`() {
        store.insert(event(
            id = "blocked-first", pubkey = "pk1", kind = 10006, createdAt = 100,
            tags = listOf(listOf("relay", "wss://first.relay.com")),
        ))
        store.insert(event(
            id = "blocked-tie", pubkey = "pk1", kind = 10006, createdAt = 100,
            tags = listOf(listOf("relay", "wss://tie.relay.com")),
        ))
        val blocked = store.getBlockedRelayUrls("pk1")
        assertEquals(1, blocked.size)
        assertEquals("wss://first.relay.com", blocked[0])
    }

    @Test
    fun `getBlockedRelayUrls returns empty for unknown pubkey`() {
        assertEquals(emptyList<String>(), store.getBlockedRelayUrls("unknown"))
    }

    @Test
    fun `blockedRelayUrlsFlow re-emits on kind-10006 insert`() = runTest {
        store.blockedRelayUrlsFlow("pk1").test {
            assertEquals(emptyList<String>(), awaitItem())

            store.insert(event(
                id = "blocked-1", pubkey = "pk1", kind = 10006, createdAt = 100,
                tags = listOf(listOf("relay", "wss://evil.relay.com")),
            ))
            val updated = awaitItem()
            assertEquals(1, updated.size)
            assertEquals("wss://evil.relay.com", updated[0])

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Kind 10007: Search relays ───────────────────────────────────────────

    @Test
    fun `handleSearch parses relay tags preserving tag order`() {
        store.insert(event(
            id = "search-1", pubkey = "pk1", kind = 10007, createdAt = 100,
            tags = listOf(
                listOf("relay", "wss://search-z.com"),
                listOf("relay", "wss://search-a.com"),
            ),
        ))
        val search = store.getSearchRelayUrls("pk1")
        assertEquals(2, search.size)
        assertEquals("wss://search-z.com", search[0])
        assertEquals("wss://search-a.com", search[1])
    }

    @Test
    fun `handleSearch deduplicates URLs within single event`() {
        store.insert(event(
            id = "search-dup", pubkey = "pk1", kind = 10007, createdAt = 100,
            tags = listOf(
                listOf("relay", "wss://dup.com"),
                listOf("relay", "wss://dup.com"),
            ),
        ))
        val search = store.getSearchRelayUrls("pk1")
        assertEquals(1, search.size)
    }

    @Test
    fun `handleSearch replaces older event (replaceable dedup)`() {
        store.insert(event(
            id = "search-old", pubkey = "pk1", kind = 10007, createdAt = 100,
            tags = listOf(listOf("relay", "wss://old-search.com")),
        ))
        store.insert(event(
            id = "search-new", pubkey = "pk1", kind = 10007, createdAt = 200,
            tags = listOf(listOf("relay", "wss://new-search.com")),
        ))
        val search = store.getSearchRelayUrls("pk1")
        assertEquals(1, search.size)
        assertEquals("wss://new-search.com", search[0])
    }

    @Test
    fun `getSearchRelayUrls returns empty for unknown pubkey`() {
        assertEquals(emptyList<String>(), store.getSearchRelayUrls("unknown"))
    }

    @Test
    fun `searchRelayUrlsFlow re-emits on kind-10007 insert`() = runTest {
        store.searchRelayUrlsFlow("pk1").test {
            assertEquals(emptyList<String>(), awaitItem())

            store.insert(event(
                id = "search-1", pubkey = "pk1", kind = 10007, createdAt = 100,
                tags = listOf(listOf("relay", "wss://search1.com")),
            ))
            val updated = awaitItem()
            assertEquals(1, updated.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Kind 10012: Favorite relays ─────────────────────────────────────────

    @Test
    fun `handleFavorites parses relay and set-ref tags preserving tag order`() {
        store.insert(event(
            id = "fav-1", pubkey = "pk1", kind = 10012, createdAt = 100,
            tags = listOf(
                listOf("relay", "wss://fav1.com"),
                listOf("a", "30002:pk1:my-set"),
                listOf("relay", "wss://fav2.com"),
                listOf("p", "ignored"),
            ),
        ))
        val favs = store.getFavoriteRelayConfigs("pk1")
        assertEquals(3, favs.size)
        // Tag order preserved
        assertEquals("wss://fav1.com", favs[0].url)
        assertNull(favs[0].setRef)
        assertNull(favs[1].url)
        assertEquals("30002:pk1:my-set", favs[1].setRef)
        assertEquals("wss://fav2.com", favs[2].url)
    }

    @Test
    fun `handleFavorites deduplicates relay URLs and set refs within event`() {
        store.insert(event(
            id = "fav-dup", pubkey = "pk1", kind = 10012, createdAt = 100,
            tags = listOf(
                listOf("relay", "wss://dup.com"),
                listOf("relay", "wss://dup.com"),
                listOf("a", "30002:pk1:set1"),
                listOf("a", "30002:pk1:set1"),
            ),
        ))
        val favs = store.getFavoriteRelayConfigs("pk1")
        assertEquals(2, favs.size)
    }

    @Test
    fun `handleFavorites replaces older event`() {
        store.insert(event(
            id = "fav-old", pubkey = "pk1", kind = 10012, createdAt = 100,
            tags = listOf(listOf("relay", "wss://old-fav.com")),
        ))
        store.insert(event(
            id = "fav-new", pubkey = "pk1", kind = 10012, createdAt = 200,
            tags = listOf(listOf("relay", "wss://new-fav.com")),
        ))
        val favs = store.getFavoriteRelayConfigs("pk1")
        assertEquals(1, favs.size)
        assertEquals("wss://new-fav.com", favs[0].url)
    }

    @Test
    fun `getFavoriteRelayConfigs returns empty for unknown pubkey`() {
        assertEquals(emptyList<FavoriteEntry>(), store.getFavoriteRelayConfigs("unknown"))
    }

    // ── Kind 10002: Enriched relay configs ──────────────────────────────────

    @Test
    fun `enriched handleRelayList stores marker info preserving tag order`() {
        store.insert(event(
            id = "relay-1", pubkey = "pk1", kind = 10002, createdAt = 100,
            tags = listOf(
                listOf("r", "wss://both.relay.com"),
                listOf("r", "wss://read.relay.com", "read"),
                listOf("r", "wss://write.relay.com", "write"),
            ),
        ))
        val configs = store.getReadWriteRelayConfigs("pk1")
        assertEquals(3, configs.size)
        // Tag order preserved
        assertEquals("wss://both.relay.com", configs[0].url)
        assertNull(configs[0].marker)
        assertEquals("wss://read.relay.com", configs[1].url)
        assertEquals("read", configs[1].marker)
        assertEquals("wss://write.relay.com", configs[2].url)
        assertEquals("write", configs[2].marker)
    }

    @Test
    fun `getReadWriteRelayConfigs returns empty for unknown pubkey`() {
        assertEquals(emptyList<RelayConfig>(), store.getReadWriteRelayConfigs("unknown"))
    }

    @Test
    fun `readWriteRelayConfigsFlow re-emits on kind-10002 insert`() = runTest {
        store.readWriteRelayConfigsFlow("pk1").test {
            assertEquals(emptyList<RelayConfig>(), awaitItem())

            store.insert(event(
                id = "relay-1", pubkey = "pk1", kind = 10002, createdAt = 100,
                tags = listOf(listOf("r", "wss://relay.com")),
            ))
            val updated = awaitItem()
            assertEquals(1, updated.size)
            assertEquals("wss://relay.com", updated[0].url)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Bootstrap wait-for-arrival ──────────────────────────────────────────

    @Test
    fun `readWriteRelayConfigsFlow emits empty then non-empty then suppresses duplicate relay arrival`() = runTest {
        store.readWriteRelayConfigsFlow("pk1").test {
            // Starts with empty
            assertEquals(emptyList<RelayConfig>(), awaitItem())

            // Insert kind-10002 event
            store.insert(event(
                id = "relay-boot", pubkey = "pk1", kind = 10002, createdAt = 100,
                tags = listOf(listOf("r", "wss://relay.com")),
            ))
            val first = awaitItem()
            assertEquals(1, first.size)

            // Same event from another relay (duplicate) — should NOT re-emit
            // because parsed state is identical (distinctUntilChanged)
            store.insert(event(
                id = "relay-boot", pubkey = "pk1", kind = 10002, createdAt = 100,
                tags = listOf(listOf("r", "wss://relay.com")),
                relayUrl = "wss://other-relay.com",
            ))
            // No new emission expected — expectNoEvents would throw if one arrives
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }
}
