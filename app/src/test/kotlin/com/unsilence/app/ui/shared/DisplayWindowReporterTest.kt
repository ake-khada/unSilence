package com.unsilence.app.ui.shared

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import org.junit.Assert.*
import org.junit.Test

class DisplayWindowReporterTest {
    @Test
    fun `shared reporter protects repost identity only while surface is active`() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val target = event("target")
        val wrapper = event("wrapper").copy(kind = 6, rootId = target.id, tags = listOf(listOf("e", target.id)))
        store.insert(target)
        store.insert(wrapper)
        val row = store.synthesizeFeedRow(wrapper)
        val reporter = DisplayWindowReporter(store)
        reporter.setActive(true)
        reporter.report(listOf(row), setOf(row.id))
        store.evictOldContentEventsForTest(mapOf(1 to 0, 6 to 0))
        assertNotNull(store.getNostrEvent(target.id))
        assertNotNull(store.getNostrEvent(wrapper.id))
        reporter.setActive(false)
        // A late asynchronous warm completion cannot resurrect the hidden window.
        reporter.report(listOf(row), setOf(row.id))
        store.evictOldContentEventsForTest(mapOf(1 to 0, 6 to 0))
        assertNull(store.getNostrEvent(target.id))
        assertNull(store.getNostrEvent(wrapper.id))
    }

    @Test
    fun `visible report touches original LRU but lookahead does not`() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val visible = event("visible")
        val lookahead = event("lookahead")
        store.insert(visible)
        store.insert(lookahead)
        repeat(4_998) { store.insert(event("other-$it")) }
        val reporter = DisplayWindowReporter(store)
        reporter.setActive(true)
        reporter.report(listOf(store.synthesizeFeedRow(visible), store.synthesizeFeedRow(lookahead)), setOf(visible.id))
        reporter.setActive(false)
        assertTrue(store.insert(event("newcomer")))
        assertNotNull(store.getNostrEvent(visible.id))
        assertNull(store.getNostrEvent(lookahead.id))
    }

    private fun event(id: String) = NostrEvent(
        id = id, pubkey = "unfollowed", kind = 1, content = "fixture", createdAt = 1L,
        tags = emptyList(), sig = "", relayUrl = "wss://relay.example", replyToId = null, rootId = null,
        hasContentWarning = false, contentWarningReason = null, firstSeenAt = 1L,
        relaysSeen = mutableSetOf("wss://relay.example"),
    )
}
