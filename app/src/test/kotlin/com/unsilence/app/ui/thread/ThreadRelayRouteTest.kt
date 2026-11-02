package com.unsilence.app.ui.thread

import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.relay.OutboxRelayResolver
import com.unsilence.app.data.relay.RelayMetadataSource
import org.junit.Assert.*
import org.junit.Test

class ThreadRelayRouteTest {
    private val root = EventEntity("root", "alice", 1, "root", 1, "[]")
    private val reply = EventEntity("reply", "bob", 1, "reply", 2, "[]", rootId = "root", replyToId = "root")
    private val metadata = object : RelayMetadataSource {
        override fun readRelaysFor(pubkey: String) = listOf("wss://$pubkey.inbox.test")
        override fun writeRelaysFor(pubkey: String) = listOf("wss://$pubkey.write.test")
    }
    private fun route(events: List<EventEntity>, tapped: String = "reply") = resolveThreadRelayRoute(
        "root", tapped, events.associateBy { it.id }::get,
        metadata::writeRelaysFor, OutboxRelayResolver(metadata),
        listOf("wss://viewer.read.test"), emptySet(), listOf("wss://hint.test"),
    )

    @Test fun `tapping a reply routes compatibility queries by the actual root author`() {
        val route = route(listOf(root, reply))
        assertTrue("root author's writer missing", "wss://alice.write.test" in route.compatibilityRelays)
        assertFalse("reply author is not the root", "wss://bob.write.test" in route.compatibilityRelays)
    }

    @Test fun `unknown root never borrows the tapped reply author identity`() {
        val route = route(listOf(reply))
        assertEquals(listOf("wss://hint.test", "wss://viewer.read.test"), route.compatibilityRelays)
    }

    @Test fun `learning root author updates the route without changing root id`() {
        val before = route(listOf(reply))
        val after = route(listOf(root, reply))
        assertEquals(before.rootId, after.rootId)
        assertNotEquals(before, after)
    }

    @Test fun `declared inboxes are reply-only while root compatibility remains unchanged`() {
        val selected = route(listOf(root), tapped = "root")
        assertEquals(listOf("wss://hint.test", "wss://alice.write.test", "wss://viewer.read.test"), selected.compatibilityRelays)
        assertEquals(listOf("wss://alice.inbox.test", "wss://hint.test", "wss://alice.write.test", "wss://viewer.read.test"), selected.replyRelays)
    }

    @Test fun `unknown root can use tapped inbox without pretending it is root author`() {
        val selected = route(listOf(reply))
        assertTrue("wss://bob.inbox.test" in selected.replyRelays)
        assertFalse("wss://bob.write.test" in selected.replyRelays)
        assertFalse("wss://alice.inbox.test" in selected.replyRelays)
    }
}
