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
