package com.unsilence.app.ui.profile

import com.unsilence.app.data.memory.NostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production fetch/merge stage used by the profile ViewModels. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePagingTest {
    private class Page {
        val result = CompletableDeferred<List<NostrEvent>>()
        val calls = mutableListOf<Triple<String, Long, Int>>()
        suspend fun fetch(key: String, until: Long, limit: Int): List<NostrEvent> {
            calls += Triple(key, until, limit)
            return result.await()
        }
    }

    @Test fun `old notes page cannot merge into the new longform tab`() = runTest {
        val notes = listOf(event("note", 200))
        val articles = listOf(event("article", 300, kind = 30023))
        val events = MutableStateFlow(notes)
        var generation = 1L
        val captured = generation
        val page = Page()
        val job = loadOlderProfilePage("notes", events, { generation == captured }, page::fetch)!!
        runCurrent()
        generation++
        events.value = articles
        page.result.complete(listOf(event("older-note", 100)))
        job.join()
        assertEquals(articles, events.value)
        assertEquals(listOf(Triple("notes", 200L, 100)), page.calls)
    }

    @Test fun `cover drops a delayed page and uncover can fetch and append normally`() = runTest {
        val initial = listOf(event("top", 200))
        val older = listOf(event("older", 100))
        val events = MutableStateFlow(initial)
        var active = true
        val page = Page()
        val job = loadOlderProfilePage("profile", events, { active }, page::fetch)!!
        runCurrent()
        active = false
        page.result.complete(older)
        job.join()
        assertEquals(initial, events.value)
        active = true
        val retry = Page().apply { result.complete(older) }
        loadOlderProfilePage("profile", events, { active }, retry::fetch)!!.join()
        assertEquals(initial + older, events.value)
        assertEquals(page.calls, retry.calls)
        assertEquals(1, retry.calls.size)
    }

    @Test fun `cover then uncover before completion does not accept the previous generation`() = runTest {
        val initial = listOf(event("top", 200))
        val events = MutableStateFlow(initial)
        var active = true
        var generation = 1L
        val captured = generation
        val page = Page()
        val job = loadOlderProfilePage("profile", events, { active && generation == captured }, page::fetch)!!
        runCurrent()
        active = false
        generation++
        active = true
        page.result.complete(listOf(event("older", 100)))
        job.join()
        assertEquals(initial, events.value)
        assertEquals(1, page.calls.size)
    }

    @Test fun `cleared timeline is not resurrected by an obsolete page`() = runTest {
        val events = MutableStateFlow(listOf(event("top", 200)))
        var generation = 1L
        val captured = generation
        val page = Page()
        val job = loadOlderProfilePage("profile", events, { generation == captured }, page::fetch)!!
        runCurrent()
        generation++
        events.value = emptyList()
        page.result.complete(listOf(event("older", 100)))
        job.join()
        assertTrue(events.value.isEmpty())
        assertEquals(1, page.calls.size)
    }

    @Test fun `current profile appends deduped rows without trimming existing paged history`() = runTest {
        val initial = (2000 downTo 1001).map { event("row-$it", it.toLong()) }
        val events = MutableStateFlow(initial)
        val page = Page()
        val job = loadOlderProfilePage("profile", events, { true }, page::fetch)!!
        runCurrent()
        page.result.complete(listOf(initial.last(), event("older", 1000)))
        job.join()
        assertEquals(initial + event("older", 1000), events.value)
        assertSame(initial.first(), events.value.first())
        assertEquals(listOf(Triple("profile", 1001L, 100)), page.calls)
    }

    @Test fun `cursor is captured before launch and includes filtered out reply tail`() = runTest {
        val events = MutableStateFlow(listOf(event("root", 200), event("reply", 100).copy(replyToId = "parent")))
        val page = Page()
        val job = loadOlderProfilePage("profile", events, { true }, page::fetch)!!
        events.value = listOf(event("newer", 300)) + events.value
        runCurrent()
        page.result.complete(emptyList())
        job.join()
        assertEquals(listOf(Triple("profile", 100L, 100)), page.calls)
        assertEquals(3, events.value.size)
    }

    @Test fun `missing handle or empty timeline sends no page request`() = runTest {
        val page = Page()
        assertNull(loadOlderProfilePage(null, MutableStateFlow(listOf(event("top", 200))), { true }, page::fetch))
        assertNull(loadOlderProfilePage("profile", MutableStateFlow(emptyList()), { true }, page::fetch))
        runCurrent()
        assertTrue(page.calls.isEmpty())
    }

    @Test fun `cancelled caller cannot append and does not prevent a later request`() = runTest {
        val initial = listOf(event("top", 200))
        val events = MutableStateFlow(initial)
        val page = Page()
        val job = loadOlderProfilePage("profile", events, { true }, page::fetch)!!
        runCurrent()
        job.cancelAndJoin()
        page.result.complete(listOf(event("stale", 100)))
        assertEquals(initial, events.value)
        val fresh = Page().apply { result.complete(listOf(event("fresh", 90))) }
        loadOlderProfilePage("profile", events, { true }, fresh::fetch)!!.join()
        assertEquals(initial + event("fresh", 90), events.value)
        assertEquals(1, fresh.calls.size)
    }

    private fun event(id: String, createdAt: Long, kind: Int = 1) = NostrEvent(
        id = id, pubkey = "author", kind = kind, content = id, createdAt = createdAt,
        tags = emptyList(), sig = "", relayUrl = "wss://profile.example", replyToId = null,
        rootId = null, hasContentWarning = false, contentWarningReason = null,
        firstSeenAt = 0, relaysSeen = mutableSetOf(),
    )
}
