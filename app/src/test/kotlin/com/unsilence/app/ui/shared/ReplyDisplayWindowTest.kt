package com.unsilence.app.ui.shared

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import org.junit.Assert.*
import org.junit.Test

class ReplyDisplayWindowTest {
    @Test
    fun `thread window retains revealed replies and their late engagement`() {
        assertRevealedWindow(article = false)
    }

    @Test
    fun `article comment window retains revealed replies and visible article context`() {
        assertRevealedWindow(article = true)
    }

    private fun assertRevealedWindow(article: Boolean) {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val root = event("root").copy(kind = if (article) 30023 else 1)
        val reply = event("revealed")
        val hidden = event("hidden")
        val muted = event("muted")
        listOf(root, reply, hidden, muted).forEach(store::insert)
        val rootRow = store.synthesizeFeedRow(root)
        val replies = listOf(
            ModeratedReplyRow(store.synthesizeFeedRow(reply), 0, spamClusterId = "opened"),
            ModeratedReplyRow(store.synthesizeFeedRow(hidden), 0, spamClusterId = "closed"),
            ModeratedReplyRow(store.synthesizeFeedRow(muted), 0, muted = true),
        )
        val items = replyListItems(replies, setOf("opened"))
        assertTrue("The rendered list contains the revealed body", items.any {
            it is ReplyListItem.Reply && it.depthRow.row.id == reply.id
        })
        val rows = (if (article) emptyList() else listOf(rootRow)) + displayedReplyRows(items)
        val reporter = DisplayWindowReporter(store)
        reporter.setActive(true)
        reporter.prepareWarmWindow(
            rows, 0, rows.lastIndex, setOf(root.id, reply.id), 0,
            contextRows = if (article) listOf(rootRow) else emptyList(),
        )
        val reaction = event("late-reaction").copy(kind = 7, tags = listOf(listOf("e", reply.id)))
        store.insert(reaction)
        store.evictOldContentEventsForTest(mapOf(1 to 0, 7 to 0, 30023 to 0))
        assertNotNull("Revealed body must survive while displayed", store.getNostrEvent(reply.id))
        assertNotNull("Engagement arriving after reveal is protected", store.getNostrEvent(reaction.id))
        assertNotNull("Focused root / visible article remains protected", store.getNostrEvent(root.id))
        assertNull("Collapsed replies are not protected", store.getNostrEvent(hidden.id))
        assertNull("Muted placeholders do not protect hidden bodies", store.getNostrEvent(muted.id))

        // Recollapse replaces the projection; it is not a whole-conversation lease.
        val collapsed = displayedReplyRows(replyListItems(replies, emptySet()))
        reporter.prepareWarmWindow(
            (if (article) emptyList() else listOf(rootRow)) + collapsed,
            0, 0, setOf(root.id), 0,
            contextRows = if (article) listOf(rootRow) else emptyList(),
        )
        store.evictOldContentEventsForTest(mapOf(1 to 0, 7 to 0))
        assertNull(store.getNostrEvent(reply.id))
        assertNull(store.getNostrEvent(reaction.id))
        reporter.setActive(false)
        store.evictOldContentEventsForTest(mapOf(1 to 0, 30023 to 0))
        assertNull(store.getNostrEvent(root.id))
    }

    @Test
    fun `revealing a large cluster preserves ordering and the bounded nearby window`() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val replies = (0..99).map { index ->
            val note = event("reply-$index").also(store::insert)
            ModeratedReplyRow(store.synthesizeFeedRow(note), index % 3, spamClusterId = "cluster")
        }
        val rows = displayedReplyRows(replyListItems(replies, setOf("cluster")))
        assertEquals(replies.map { it.row.id }, rows.map { it.id })
        val reporter = DisplayWindowReporter(store)
        reporter.setActive(true)
        reporter.prepareWarmWindow(rows, 40, 41, setOf("reply-40", "reply-41"), 100)
        store.evictOldContentEventsForTest(mapOf(1 to 0))
        assertEquals((38..47).map { "reply-$it" }.toSet(), store.displayPriorities().keys)
        assertNull("Off-window replies remain evictable", store.getNostrEvent("reply-99"))
        reporter.setActive(false)
        assertTrue(store.displayPriorities().isEmpty())
    }

    @Test
    fun `mute and removal override an already revealed cluster`() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val row = store.synthesizeFeedRow(event("reply"))
        val muted = ModeratedReplyRow(row, 0, muted = true, spamClusterId = "cluster")
        assertTrue(displayedReplyRows(replyListItems(listOf(muted), setOf("cluster"))).isEmpty())
        assertTrue(displayedReplyRows(replyListItems(emptyList(), setOf("cluster"))).isEmpty())
    }

    private fun event(id: String) = NostrEvent(
        id = id, pubkey = "unfollowed", kind = 1, content = "fixture", createdAt = 1L,
        tags = emptyList(), sig = "", relayUrl = "wss://relay.example", replyToId = null, rootId = null,
        hasContentWarning = false, contentWarningReason = null, firstSeenAt = 1L,
        relaysSeen = mutableSetOf("wss://relay.example"),
    )
}
