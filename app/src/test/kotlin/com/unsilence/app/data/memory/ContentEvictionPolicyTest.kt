package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.NostrFilter
import com.unsilence.app.data.relay.TimelineRef
import com.unsilence.app.data.relay.TimelineService
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentEvictionPolicyTest {
    private fun event(
        id: String,
        pubkey: String,
        createdAt: Long,
        kind: Int = 1,
        tags: List<List<String>> = emptyList(),
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        kind = kind,
        content = "content-$id",
        createdAt = createdAt,
        tags = tags,
        tagsJson = "[]",
        sig = "sig",
        relayUrl = "wss://relay.example",
        replyToId = null,
        rootId = null,
        hasContentWarning = false,
        contentWarningReason = null,
        firstSeenAt = createdAt,
        relaysSeen = mutableSetOf("wss://relay.example"),
    )

    @Test
    fun `followed author survives ordinary candidates past cap`() {
        val provider = stubTimelineServiceProvider()
        val store = MemoryEventStore(object : MuteKeyProvider {}, provider)
        store.ownPubkey = "owner"
        store.updateFollows("owner", setOf("followed"), createdAt = 1L)
        store.insert(event("followed-old", "followed", 1L))
        store.insert(event("ordinary-old", "ordinary-a", 2L))
        store.insert(event("ordinary-new", "ordinary-b", 3L))

        store.evictOldContentEventsForTest(mapOf(1 to 2))

        assertNotNull(store.getNostrEvent("followed-old"))
        assertEquals(
            1,
            listOf("ordinary-old", "ordinary-new").count { store.getNostrEvent(it) != null },
        )
        val metrics = store.snapshotEvictionMetrics()
        assertEquals(0L, metrics.tier1)
        assertEquals(0L, metrics.tier2)
        assertEquals(1L, metrics.tier3)
    }

    @Test
    fun `timeline referenced event survives unreferenced candidates past cap`() {
        val provider = stubTimelineServiceProvider()
        val timelineService = provider.get()
        val store = MemoryEventStore(object : MuteKeyProvider {}, provider)
        val referenced = event("timeline-old", "author-a", 1L)
        store.insert(referenced)
        store.insert(event("ordinary-old", "author-b", 2L))
        store.insert(event("ordinary-new", "author-c", 3L))
        restoreTimeline(timelineService, listOf(TimelineRef(referenced.id, referenced.createdAt)))

        store.evictOldContentEventsForTest(mapOf(1 to 2))

        assertNotNull(store.getNostrEvent("timeline-old"))
        assertEquals(
            1,
            listOf("ordinary-old", "ordinary-new").count { store.getNostrEvent(it) != null },
        )
        val metrics = store.snapshotEvictionMetrics()
        assertEquals(0L, metrics.tier1)
        assertEquals(0L, metrics.tier2)
        assertEquals(1L, metrics.tier3)
    }

    @Test
    fun `own and mentioned events remain outside bounded tiers`() {
        val provider = stubTimelineServiceProvider()
        val store = MemoryEventStore(object : MuteKeyProvider {}, provider)
        val owner = "a".repeat(64)
        store.ownPubkey = owner
        store.insert(event("own", owner, 1L))
        store.insert(event("mention", "someone", 2L, tags = listOf(listOf("p", owner))))
        store.insert(event("profile-ref", "referenced-author", 3L))
        store.profileAnchoredIds.add("profile-ref")
        store.insert(event("ordinary-old", "other-a", 4L))
        store.insert(event("ordinary-new", "other-b", 5L))

        store.evictOldContentEventsForTest(mapOf(1 to 1))
        // A second pass must replace the anchor sample, not add another set
        // of visits for the same unique anchored events.
        store.evictOldContentEventsForTest(mapOf(1 to 1))

        assertNotNull(store.getNostrEvent("own"))
        assertNotNull(store.getNostrEvent("mention"))
        assertNotNull(store.getNostrEvent("profile-ref"))
        assertEquals(
            1,
            listOf("ordinary-old", "ordinary-new").count { store.getNostrEvent(it) != null },
        )
        val metrics = store.snapshotEvictionMetrics()
        assertEquals(1L, metrics.anchoredOwn)
        assertEquals(1L, metrics.anchoredMentioned)
        assertEquals(1L, metrics.anchoredProfileRefs)
        assertEquals(1L, metrics.evicted)
        assertEquals(2L, metrics.passes)
        assertEquals(mapOf(1 to 1L), metrics.evictedByKind)
    }

    @Test
    fun `three times over cap with mixed tiers still converges exactly to cap`() {
        val provider = stubTimelineServiceProvider()
        val timelineService = provider.get()
        val store = MemoryEventStore(object : MuteKeyProvider {}, provider)
        store.ownPubkey = "owner"
        store.updateFollows("owner", setOf("followed-0", "followed-1"), createdAt = 1L)
        repeat(9) { index ->
            val author = if (index < 2) "followed-$index" else "author-$index"
            store.insert(event("event-$index", author, index.toLong()))
        }
        restoreTimeline(
            timelineService,
            refs = listOf(TimelineRef("event-2", 2L), TimelineRef("event-3", 3L)),
        )

        store.evictOldContentEventsForTest(mapOf(1 to 3))

        val retained = (0 until 9).count { store.getNostrEvent("event-$it") != null }
        assertEquals(3, retained)
        assertNotNull(store.getNostrEvent("event-0"))
        assertNotNull(store.getNostrEvent("event-1"))
        assertEquals(
            1,
            listOf("event-2", "event-3").count { store.getNostrEvent(it) != null },
        )
        val metrics = store.snapshotEvictionMetrics()
        assertEquals(0L, metrics.tier1)
        assertEquals(1L, metrics.tier2)
        assertEquals(5L, metrics.tier3)
    }

    @Test
    fun `selector materializes mutable maps before comparator runs`() {
        val entries = (0 until 40).map { EventEntry("event-$it", it.toLong()) }
        val authors = CountingMap(entries.associate { it.id to "author-${it.id}" })
        val touches = CountingMap(entries.associate { it.id to it.createdAt })

        val selected = selectContentEvictionCandidates(
            entries = entries,
            cap = 10,
            authorsByEventId = authors,
            followedPubkeys = emptySet(),
            timelineReferencedIds = emptySet(),
            lastTouchedAt = touches,
        )

        assertEquals(30, selected.size)
        assertEquals(entries.size, authors.reads)
        assertEquals(entries.size, touches.reads)
        assertTrue(selected.all { it.tier == ContentEvictionTier.ORDINARY })
    }

    @Test
    fun `bounded tiers evict ordinary then timeline before followed`() {
        val entries = listOf(
            EventEntry("followed", 1L),
            EventEntry("timeline", 2L),
            EventEntry("ordinary", 3L),
        )

        val selected = selectContentEvictionCandidates(
            entries = entries,
            cap = 1,
            authorsByEventId = mapOf(
                "followed" to "followed-author",
                "timeline" to "timeline-author",
                "ordinary" to "ordinary-author",
            ),
            followedPubkeys = setOf("followed-author"),
            timelineReferencedIds = setOf("followed", "timeline"),
            lastTouchedAt = entries.associate { it.id to it.createdAt },
        )

        assertEquals(
            listOf(ContentEvictionTier.ORDINARY, ContentEvictionTier.TIMELINE_REFERENCED),
            selected.map { it.tier },
        )
    }

    @Test
    fun `restored caps retain original content bounds`() {
        assertEquals(5_000, CONTENT_EVENT_KIND_CAPS.getValue(1))
        assertEquals(1_000, CONTENT_EVENT_KIND_CAPS.getValue(6))
        assertEquals(1_000, CONTENT_EVENT_KIND_CAPS.getValue(16))
        assertEquals(1_000, CONTENT_EVENT_KIND_CAPS.getValue(7))
        assertEquals(250, CONTENT_EVENT_KIND_CAPS.getValue(9735))
        assertEquals(500, CONTENT_EVENT_KIND_CAPS.getValue(20))
        assertEquals(500, CONTENT_EVENT_KIND_CAPS.getValue(21))
        assertEquals(500, CONTENT_EVENT_KIND_CAPS.getValue(22))
        assertEquals(500, CONTENT_EVENT_KIND_CAPS.getValue(34235))
        assertEquals(500, CONTENT_EVENT_KIND_CAPS.getValue(34236))
        assertEquals(500, CONTENT_EVENT_KIND_CAPS.getValue(30023))
    }

    @Test
    fun `eviction metrics split actual removals by kind`() {
        val provider = stubTimelineServiceProvider()
        val store = MemoryEventStore(object : MuteKeyProvider {}, provider)
        repeat(3) { index ->
            store.insert(event("note-$index", "note-author-$index", index.toLong()))
            store.insert(event("reaction-$index", "reaction-author-$index", index.toLong(), kind = 7))
        }

        store.evictOldContentEventsForTest(mapOf(1 to 1, 7 to 1))

        val metrics = store.snapshotEvictionMetrics()
        assertEquals(4L, metrics.evicted)
        assertEquals(mapOf(1 to 2L, 7 to 2L), metrics.evictedByKind)
    }

    @Test
    fun `clear resets interval eviction work and latest anchor sample`() {
        val provider = stubTimelineServiceProvider()
        val store = MemoryEventStore(object : MuteKeyProvider {}, provider)
        store.ownPubkey = "owner"
        store.insert(event("own", "owner", 1L))
        store.insert(event("ordinary", "other", 2L))
        store.evictOldContentEventsForTest(mapOf(1 to 0))

        store.clear()

        val metrics = store.snapshotEvictionMetrics()
        assertEquals(0L, metrics.passes)
        assertEquals(0L, metrics.evicted)
        assertEquals(0L, metrics.tier1)
        assertEquals(0L, metrics.tier2)
        assertEquals(0L, metrics.tier3)
        assertTrue(metrics.evictedByKind.isEmpty())
        assertEquals(0L, metrics.anchoredOwn)
        assertEquals(0L, metrics.anchoredMentioned)
        assertEquals(0L, metrics.anchoredViewed)
        assertEquals(0L, metrics.anchoredProfileRefs)
        assertEquals(0L, metrics.liveTimelineRefs)
    }

    @Test
    fun `live timeline refs are capped per timeline and unioned`() {
        val provider = stubTimelineServiceProvider()
        val timelineService = provider.get()
        val firstRefs = (0 until 600).map { TimelineRef("first-$it", 1_000L - it) }
        val secondRefs = listOf(TimelineRef("first-0", 1_000L), TimelineRef("second", 999L))
        restoreTimeline(timelineService, firstRefs, relayUrl = "wss://first.example")
        restoreTimeline(timelineService, secondRefs, relayUrl = "wss://second.example")

        val referenced = timelineService.liveReferencedIds()

        assertEquals(501, referenced.size)
        assertTrue("first-499" in referenced)
        assertTrue("first-500" !in referenced)
        assertTrue("second" in referenced)
    }

    private fun restoreTimeline(
        timelineService: TimelineService,
        refs: List<TimelineRef>,
        relayUrl: String = "wss://timeline.example",
    ) {
        val urls = listOf(relayUrl)
        val filter = NostrFilter(kinds = listOf(1), authors = listOf("author"), limit = 500)
        val key = timelineService.generateTimelineKey(urls, filter)
        timelineService.restoreFromSnapshot(
            mapOf(key to TimelineService.Timeline(refs = refs, filter = filter, urls = urls)),
        )
    }

    private class CountingMap<K, V>(private val delegate: Map<K, V>) : Map<K, V> by delegate {
        var reads: Int = 0
            private set

        override operator fun get(key: K): V? {
            reads++
            return delegate[key]
        }
    }
}
