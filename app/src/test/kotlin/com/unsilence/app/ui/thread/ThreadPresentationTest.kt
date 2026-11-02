package com.unsilence.app.ui.thread

import com.unsilence.app.data.memory.FeedRow
import app.cash.turbine.test
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import javax.inject.Provider
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ThreadPresentationTest {
    private val root = FeedRow(
        "root", "author", 1, "Cached quoted post", 1L, "[]", "", null, null,
        false, null, 0L, null, null, null, null, 0, 0, 0, 0,
    )

    @Test fun `cached root is present before lifecycle collection or requests begin`() {
        val states = MutableStateFlow(ThreadUiState())
        val presentation = threadPresentation(root.id, states, { root })
        assertEquals(root, presentation.value.focusedNote)
        assertFalse(presentation.value.loading)
        assertTrue("Presentation must not activate or mutate the VM", states.value.loading)
        assertEquals(0, states.subscriptionCount.value)
    }

    @Test fun `first loading emission cannot replace the cached card with skeletons`() = runTest {
        val presentation = threadPresentation(root.id, MutableStateFlow(ThreadUiState()), { root })
        assertEquals(root, presentation.first().focusedNote)
        assertFalse(presentation.first().loading)
    }

    @Test fun `uncached event and cached reply retain existing root resolution`() {
        val states = MutableStateFlow(ThreadUiState())
        assertTrue(threadPresentation("missing", states, { null }).value.loading)
        for (reply in listOf(root.copy(rootId = "parent"), root.copy(replyToId = "parent"))) {
            val initial = threadPresentation(reply.id, states, { reply }).value
            assertTrue(initial.loading)
            assertNull(initial.focusedNote)
        }
    }

    @Test fun `settled projection wins including deletion and reply focus`() {
        val focused = ThreadUiState(root, emptyList(), false, "reply")
        val states = MutableStateFlow(focused)
        var reads = 0
        val presentation = threadPresentation(root.id, states, { reads++; root })
        assertSame(focused, presentation.value)
        states.value = ThreadUiState(loading = false)
        assertNull(presentation.value.focusedNote)
        assertEquals("No seed lookup after projection settles", 0, reads)
    }

    @Test fun `content warning and original payload are preserved in seed`() {
        val protected = root.copy(hasContentWarning = true, contentWarningReason = "fixture")
        assertEquals(protected, threadPresentation(root.id, MutableStateFlow(ThreadUiState()), { protected }).value.focusedNote)
    }

    @Test fun `collected provisional states keep the card then honor settled focus and removal`() = runTest {
        val states = MutableStateFlow(ThreadUiState())
        threadPresentation(root.id, states, { root }).test {
            assertEquals(root, awaitItem().focusedNote)
            states.value = ThreadUiState(focusedReplyId = "pending", loading = true)
            assertFalse(awaitItem().loading)
            val projected = ThreadUiState(root, emptyList(), false, "reply")
            states.value = projected
            assertEquals(projected, awaitItem())
            val removed = ThreadUiState(loading = false)
            states.value = removed
            assertEquals(removed, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `quote-only local root projects off caller thread without network or conversation collection`() = runTest {
        val timeline = stubTimelineServiceProvider()
        var presentationOnly = false
        val store = MemoryEventStore(object : MuteKeyProvider {}, Provider {
            check(!presentationOnly) { "Presentation must not request a timeline" }
            timeline.get()
        })
        val event = NostrEvent(
            root.id, root.pubkey, 1, root.content, 1L, emptyList(), "sig", "",
            null, null, false, null, 1_000L, java.util.concurrent.ConcurrentHashMap.newKeySet(),
        )
        store.insert(event)
        presentationOnly = true
        assertNull(store.cachedFeedRow(root.id))
        val caller = Thread.currentThread()
        var preparations = 0
        val states = MutableStateFlow(ThreadUiState())
        val flow = threadPresentation(root.id, states, store::cachedFeedRow) {
            assertNotSame(caller, Thread.currentThread())
            preparations++
            store.feedRowsByIds(setOf(it))
        }
        assertTrue("No synchronous row synthesis", flow.value.loading)
        assertEquals(0, preparations)
        flow.test {
            val first = awaitItem()
            assertFalse(first.loading)
            assertEquals(root.id, first.focusedNote?.id)
            assertEquals(1, preparations)
            assertTrue("No VM activation or mutation", states.value.loading)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
