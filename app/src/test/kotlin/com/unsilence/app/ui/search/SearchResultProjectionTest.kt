package com.unsilence.app.ui.search

import app.cash.turbine.test
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotAssertionEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.protectedProfileFor
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import com.unsilence.app.data.relay.toNostrEvent
import com.unsilence.app.data.relay.wotSubjectsForFeedRows
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultProjectionTest {
    private val author = "a".repeat(64)
    private val quotedAuthor = "b".repeat(64)
    private fun store() = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
    private fun event(id: Int, content: String = "Synthetic search result", kind: Int = 1) = EventDto(
        id = id.toString(16).padStart(64, '0'), pubkey = author, kind = kind,
        content = content, createdAt = id.toLong(), tags = emptyList(), sig = "0".repeat(128),
    ).toNostrEvent("wss://relay.example")

    @Test fun `repeated local relay and topic results evaluate immutable moderation once`() = runTest {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val tags = object : AbstractList<List<String>>() {
            override val size = 1
            override fun get(index: Int): List<String> {
                reads.incrementAndGet()
                return listOf("t", "topic")
            }
        }
        val note = event(1, "A long Σ İ article. ".repeat(2_000), 30023).copy(tags = tags)
        val store = store()
        store.insert(note)
        val rows = store.feedRowsByIds(setOf(note.id))
        val mutes = MuteList(emptySet(), emptySet(), setOf("absent"), emptySet())
        val bundle = SearchResultsBundle(rows, rows, rows, emptyList(), mutes, 5)
        reads.set(0) // Count moderation only, not ingest or row construction.
        val updates = Channel<SearchResultsBundle>(Channel.UNLIMITED)
        updates.receiveAsFlow().projectSearchResults(
            "article", true, { note.takeIf { id -> it == id.id } }, { null },
            { WotLookup.Pending }, { emptyList() },
        ).test {
            repeat(20) {
                updates.send(bundle)
                val projection = awaitItem()
                assertEquals(rows, projection.notes)
                assertEquals(rows, projection.tags)
            }
            assertEquals("one policy evaluation, not one per source and signal", 1, reads.get())
        }
    }

    @Test fun `cached verdicts preserve Unicode private policy replacement and missing event fallback`() = runTest {
        val store = store()
        val initial = event(1, "ΟΣ İ")
        store.insert(initial)
        val rows = store.feedRowsByIds(setOf(initial.id))
        val current = AtomicReference<com.unsilence.app.data.memory.NostrEvent?>(initial)
        val updates = Channel<SearchResultsBundle>(Channel.UNLIMITED)
        val empty = MuteList(emptySet(), emptySet(), emptySet(), emptySet())
        val bundle = SearchResultsBundle(rows, rows, rows, emptyList(), empty)
        updates.receiveAsFlow().projectSearchResults(
            "query", false, { current.get() }, { null }, { WotLookup.Pending }, { emptyList() },
        ).test {
            suspend fun check(policy: MuteList, visible: Boolean) {
                updates.send(bundle.copy(muteList = policy))
                val result = awaitItem()
                assertEquals(visible, result.notes.isNotEmpty())
                assertEquals(visible, result.tags.isNotEmpty())
            }
            check(empty, true)
            // Exact old String.lowercase semantics: final sigma and expanded I.
            check(empty.copy(words = setOf("οσ")), true)
            check(empty.copy(words = setOf("ος")), false)
            check(empty.copy(privateWords = setOf("i\u0307")), false)
            check(empty.copy(words = setOf("absent")), true)
            current.set(initial.copy(content = "ABSENT")) // Same ID, new immutable body.
            check(empty.copy(words = setOf("absent")), false)
            current.set(null) // MES miss must evaluate the supplied row, not a stale verdict.
            check(empty.copy(words = setOf("absent")), true)
            check(empty.copy(privateWords = setOf("i\u0307")), false)
        }
    }

    @Test fun `each collection owns its verdicts and hashtag changes invalidate them`() = runTest {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val tags = object : AbstractList<List<String>>() {
            override val size = 1
            override fun get(index: Int): List<String> {
                reads.incrementAndGet()
                return listOf("t", "topic")
            }
        }
        val store = store()
        val note = event(1).copy(tags = tags)
        store.insert(note)
        val rows = store.feedRowsByIds(setOf(note.id))
        reads.set(0)
        val updates = Channel<SearchResultsBundle>(Channel.UNLIMITED)
        val bundle = SearchResultsBundle(rows, emptyList(), emptyList(), emptyList(), hashtagCap = 5)
        val projection = updates.receiveAsFlow().projectSearchResults(
            "query", false, { note }, { null }, { WotLookup.Pending }, { emptyList() },
        )
        projection.test {
            updates.send(bundle)
            assertEquals(rows, awaitItem().notes)
            updates.send(bundle)
            assertEquals(rows, awaitItem().notes)
            assertEquals(1, reads.get())
            updates.send(bundle.copy(hashtagCap = 0))
            assertTrue(awaitItem().notes.isEmpty())
            assertEquals(2, reads.get())
        }
        projection.test {
            updates.send(bundle)
            assertEquals(rows, awaitItem().notes)
            assertEquals("cover/query teardown must release the old collection cache", 3, reads.get())
        }
    }

    @Test fun `reference targets stay live across missing and replaced targets`() = runTest {
        val store = store()
        val target = event(2, "SPAM")
        val wrapper = event(1, kind = 6).copy(repostInfo = com.unsilence.app.data.model.RepostInfo(
            target.id, null, null, null, null, null,
            com.unsilence.app.data.model.RepostPayload.ReferenceOnly,
        ))
        store.insert(wrapper)
        val rows = store.feedRowsByIds(setOf(wrapper.id))
        val current = AtomicReference<com.unsilence.app.data.memory.NostrEvent?>(null)
        val updates = Channel<SearchResultsBundle>(Channel.UNLIMITED)
        val bundle = SearchResultsBundle(rows, emptyList(), rows, emptyList(),
            MuteList(emptySet(), emptySet(), setOf("spam"), emptySet()))
        updates.receiveAsFlow().projectSearchResults(
            "query", false, { if (it == wrapper.id) wrapper else current.get() }, { null },
            { WotLookup.Pending }, { emptyList() },
        ).test {
            updates.send(bundle)
            assertEquals(rows, awaitItem().notes)
            current.set(target)
            updates.send(bundle)
            assertTrue(awaitItem().notes.isEmpty())
            current.set(target.copy(content = "clean"))
            updates.send(bundle)
            assertEquals(rows, awaitItem().notes)
            current.set(null)
            updates.send(bundle)
            assertEquals(rows, awaitItem().notes)
            updates.send(bundle.copy(localNotes = emptyList(), relayNotes = emptyList()))
            assertTrue(awaitItem().notes.isEmpty()) // Cache never adds removed rows.
        }
    }

    @Test fun `projection verdict retention is bounded to one thousand events`() = runTest {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val tags = object : AbstractList<List<String>>() {
            override val size = 1
            override fun get(index: Int): List<String> {
                reads.incrementAndGet()
                return listOf("t", "topic")
            }
        }
        val store = store()
        val events = (1..1_001).map { event(it).copy(tags = tags) }.associateBy { it.id }
        store.insertBatch(events.values.toList())
        val rows = store.feedRowsByIds(events.keys).sortedBy { it.createdAt }
        reads.set(0)
        val updates = Channel<SearchResultsBundle>(Channel.UNLIMITED)
        val bundle = SearchResultsBundle(rows, emptyList(), emptyList(), emptyList(), hashtagCap = 5)
        updates.receiveAsFlow().projectSearchResults(
            "query", false, events::get, { null }, { WotLookup.Pending }, { emptyList() },
        ).test {
            updates.send(bundle)
            assertEquals(1_001, awaitItem().notes.size)
            assertEquals(1_001, reads.get())
            updates.send(bundle.copy(localNotes = listOf(rows.first())))
            assertEquals(1, awaitItem().notes.size)
            assertEquals("oldest verdict was evicted, rows were not capped", 1_002, reads.get())
        }
    }

    @Test fun `repeated moderation fixture reduces allocations without changing verdicts`() {
        val store = store()
        val events = (1..100).map { event(it, "Σ İ " + "Mixed CASE café article text. ".repeat(256), 30023) }
        store.insertBatch(events)
        val byId = events.associateBy { it.id }
        val rows = store.feedRowsByIds(byId.keys)
        val mutes = MuteList(emptySet(), emptySet(), setOf("missing-word"), emptySet(),
            privateWords = setOf("also-absent"))
        val policy = com.unsilence.app.ui.feed.FeedModerationCache().forPolicy(mutes, null)
        val api = Class.forName("com.sun.management.ThreadMXBean")
        val bean = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean").invoke(null)
        api.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
        val read = api.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        fun allocated() = read.invoke(bean, Thread.currentThread().threadId()) as Long
        repeat(5) {
            filterSearchNoteRows(rows, mutes, null, byId::get)
            filterSearchNoteRows(rows, mutes, null, byId::get, policy)
        }
        fun measure(cached: Boolean): Long {
            val before = allocated()
            val start = System.nanoTime()
            repeat(20) {
                val result = filterSearchNoteRows(rows, mutes, null, byId::get, policy.takeIf { cached })
                assertEquals(rows, result)
            }
            val duration = System.nanoTime() - start
            val bytes = allocated() - before
            println("SEARCH_MODERATION_FIXTURE cached=$cached entries=100 passes=20 chars=${events.sumOf { it.content.length }} allocatedBytes=$bytes durationNs=$duration")
            return bytes
        }
        val uncached = measure(false)
        val cached = measure(true)
        // Allocation regression, not a timing gate or phone responsiveness claim.
        assertTrue("uncached=$uncached cached=$cached", cached < uncached / 4)
    }

    @Test fun `repeated projections never parse cold articles and cached quote subjects still appear`() = runTest {
        val store = store()
        val reference = NEvent.create("c".repeat(64), quotedAuthor, null, null as NormalizedRelayUrl?)
        val article = event(1, "nostr:$reference\n" + "Synthetic article body. ".repeat(2_000), 30023)
        store.insert(article)
        val rows = store.feedRowsByIds(setOf(article.id))
        val bundle = SearchResultsBundle(rows, rows, rows, emptyList())
        val updates = Channel<SearchResultsBundle>(Channel.UNLIMITED)
        val uiThread = Thread.currentThread()
        assertNull(store.getEventModel(article.id))
        updates.receiveAsFlow().projectSearchResults(
            "article", false, store::getNostrEvent,
            cachedModelProvider = {
                assertNotSame("model lookups must run off the UI collector", uiThread, Thread.currentThread())
                store.getEventModel(it)
            },
            wotLookup = { WotLookup.Pending },
            protectedProfiles = { error("no people: do not scan protected profiles") },
        ).test {
            repeat(30) {
                updates.send(bundle)
                val result = awaitItem()
                assertEquals(rows, result.notes)
                assertEquals(setOf(author), result.wotSubjects)
                assertNull(store.getEventModel(article.id))
            }
            // The actual shared cache path used by the bounded card warmer.
            val cached = store.getOrParseEventModel(article.id)
            assertSame(cached, store.getOrParseEventModel(article.id))
            assertTrue(wotSubjectsForFeedRows(rows, store::getEventModel).contains(quotedAuthor))
            updates.send(bundle)
            assertEquals(setOf(author, quotedAuthor), awaitItem().wotSubjects)
            assertSame(cached, store.getEventModel(article.id))
        }
    }

    @Test fun `local relay and hashtag merges retain order deduplication warnings and live mutes`() = runTest {
        val store = store()
        val old = event(1)
        val recent = event(2, "blocked phrase").copy(hasContentWarning = true, contentWarningReason = "warning")
        store.insertBatch(listOf(old, recent))
        val rows = store.feedRowsByIds(setOf(old.id, recent.id))
        val updates = Channel<SearchResultsBundle>(Channel.UNLIMITED)
        val initial = SearchResultsBundle(rows, listOf(rows.last()), rows.take(1), listOf(UserEntity(author)))
        updates.receiveAsFlow().projectSearchResults(
            "search", true, store::getNostrEvent, store::getEventModel, { WotLookup.Pending }, { emptyList() },
        ).test {
            updates.send(initial)
            val result = awaitItem()
            assertEquals(listOf(recent.id, old.id), result.notes.map { it.id })
            assertEquals(result.notes, result.tags)
            assertTrue(result.notes.first().hasContentWarning)
            assertEquals("warning", result.notes.first().contentWarningReason)
            updates.send(initial.copy(muteList = MuteList(emptySet(), emptySet(), setOf("blocked phrase"), emptySet())))
            assertEquals(listOf(old.id), awaitItem().notes.map { it.id })
            updates.send(initial.copy(muteList = MuteList(setOf(author), emptySet(), emptySet(), emptySet())))
            val muted = awaitItem()
            assertTrue(muted.notes.isEmpty())
            assertTrue(muted.tags.isEmpty())
            assertTrue(muted.people.isEmpty())
            assertTrue(muted.wotSubjects.isEmpty())
        }
    }

    @Test fun `cancellation discards background projection before it reaches the collector`() = runTest {
        val store = store()
        val source = event(1)
        store.insert(source)
        val rows = store.feedRowsByIds(setOf(source.id))
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val collected = mutableListOf<SearchResultProjection>()
        val job = launch {
            flowOf(SearchResultsBundle(rows, emptyList(), emptyList(), emptyList())).projectSearchResults(
                "search", false, store::getNostrEvent,
                cachedModelProvider = {
                    entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                    null
                },
                wotLookup = { WotLookup.Pending }, protectedProfiles = { emptyList() },
            ).collect { collected.add(it) }
        }
        try {
            entered.await()
            job.cancel()
        } finally {
            release.countDown()
            job.join()
        }
        assertFalse(job.isActive)
        assertTrue(collected.isEmpty())
    }

    @Test fun `live trust updates recompute impersonation warnings on the background dispatcher`() = runTest {
        val uiThread = Thread.currentThread()
        val person = UserEntity(pubkey = author, name = "Alex")
        val protected = checkNotNull(protectedProfileFor(UserEntity(pubkey = quotedAuthor, name = "Alex")))
        val trust = AtomicReference<WotLookup>(WotLookup.Absent)
        val updates = Channel<SearchResultsBundle>(Channel.UNLIMITED)
        val bundle = SearchResultsBundle(emptyList(), emptyList(), emptyList(), listOf(person))
        updates.receiveAsFlow().projectSearchResults(
            "Alex", false, { null }, { null }, { trust.get() },
            protectedProfiles = {
                assertNotSame(uiThread, Thread.currentThread())
                listOf(protected)
            },
        ).test {
            updates.send(bundle)
            assertTrue(awaitItem().impersonationRisks.containsKey(author))
            trust.set(WotLookup.Scored(WotAssertionEntity(
                subjectPubkey = author, providerPubkey = "f".repeat(64), rank = 80,
            )))
            updates.send(bundle)
            val trusted = awaitItem()
            assertTrue(trusted.impersonationRisks.isEmpty())
            assertEquals(listOf(person), trusted.people)
        }
    }
}
