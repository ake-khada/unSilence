package com.unsilence.app.data.memory

import app.cash.turbine.test
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import com.unsilence.app.data.relay.toNostrEvent
import com.unsilence.app.ui.thread.flattenThreadReplies
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.StringReader
import java.io.StringWriter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercise real wire-tag parsing; q citations never define or erase reply edges. */
class ReplyCitationTest {
    private val owner = "a".repeat(64)
    private val commenter = "b".repeat(64)
    private val relay = "wss://relay.example"
    private val citation = listOf("q", "30078:${"c".repeat(64)}:example", relay)

    private fun store(viewer: String = owner) =
        MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider()).apply {
            ownPubkey = viewer
        }

    private fun note(
        id: Int,
        tags: List<List<String>> = emptyList(),
        author: String = owner,
        kind: Int = 1,
    ): NostrEvent = EventDto(
        id = id.toString(16).padStart(64, '0'), pubkey = author, kind = kind,
        createdAt = id.toLong(), content = "Synthetic reply with citation",
        tags = tags, sig = "0".repeat(128),
    ).toNostrEvent(relay)

    private fun edge(target: NostrEvent, marker: String = "reply") =
        listOf("e", target.id, relay, marker, target.pubkey)

    @Test fun `explicit reply plus event or address citation agrees across viewers and surfaces`() = runTest {
        val root = note(1, author = commenter)
        val parent = note(2, listOf(edge(root, "root")))
        val quoted = note(3, author = commenter)
        for (quote in listOf(listOf("q", quoted.id, relay), citation)) {
            val reply = note(4, listOf(quote, edge(root, "root"), edge(parent), listOf("p", owner)), commenter)
            assertEquals(parent.id, reply.replyToId)
            assertEquals(root.id, reply.rootId)
            for (viewer in listOf(owner, "d".repeat(64))) {
                val store = store(viewer)
                store.insertBatch(listOf(root, parent, quoted, reply))
                val notification = store.getNotifications(owner).filterIsInstance<NotificationRow.Single>()
                    .single { it.id == reply.id }
                assertEquals("reply", notification.notifType)
                assertEquals(2, store.replyCount(root.id))
                assertEquals(1, store.replyCount(parent.id))
                assertEquals(0, store.replyCount(quoted.id))
                assertEquals(listOf(commenter), store.replyPubkeysForEvent(parent.id))
                val rows = store.threadFeedRowFlow(root.id).first().filter { it.id != root.id }
                val rendered = flattenThreadReplies(root.id, rows, coordinateScoped = false)
                assertEquals(listOf(parent.id, reply.id), rendered.map { it.row.id })
                assertEquals(listOf(1, 2), rendered.map { it.depth })
                assertTrue(store.threadFlow(parent.id).first().any { it.id == reply.id })
            }
        }
    }

    @Test fun `root-only marked reply keeps its edge regardless of citation position`() = runTest {
        val root = note(1)
        for (tags in listOf(listOf(citation, edge(root, "root")), listOf(edge(root, "root"), citation))) {
            val reply = note(2, tags, commenter)
            assertEquals(root.id, reply.replyToId)
            assertEquals(root.id, reply.rootId)
            val store = store()
            store.insertBatch(listOf(root, reply))
            assertEquals(1, store.replyCount(root.id))
        }
    }

    @Test fun `legacy positional reply edges survive an independent citation`() = runTest {
        val root = note(1)
        val parent = note(2, listOf(listOf("e", root.id)))
        for (edges in listOf(listOf(listOf("e", parent.id)), listOf(listOf("e", root.id), listOf("e", parent.id)))) {
            val reply = note(3, edges + listOf(citation), commenter)
            assertEquals(parent.id, reply.replyToId)
            val store = store()
            store.insertBatch(listOf(root, parent, reply))
            assertEquals(2, store.replyCount(root.id))
            assertEquals(1, store.replyCount(parent.id))
        }
    }

    @Test fun `quote-only and mention-only posts do not attach themselves or their descendants`() = runTest {
        val root = note(1)
        val parent = note(2, listOf(edge(root, "root")))
        val quoteOnly = note(3, listOf(listOf("q", root.id), listOf("q", parent.id), listOf("p", owner)), commenter)
        val mentionOnly = note(4, listOf(edge(parent, "mention"), listOf("e", root.id), citation), commenter)
        for (unrelated in listOf(quoteOnly, mentionOnly)) {
            assertNull(unrelated.replyToId)
            assertNull(unrelated.rootId)
        }
        val quoteChild = note(5, listOf(edge(quoteOnly)), commenter)
        val mentionChild = note(6, listOf(edge(mentionOnly)), commenter)
        val store = store()
        store.insertBatch(listOf(root, parent, quoteOnly, mentionOnly, quoteChild, mentionChild))
        assertEquals(setOf(parent.id), store.conversationMembership(root.id).ids)
        assertEquals(0, store.replyCount(parent.id))
        assertEquals(setOf(root.id, parent.id), store.threadFlow(root.id).first().map { it.id }.toSet())
        assertEquals(1, store.replyCount(quoteOnly.id)) // Its own replies still belong to it.
    }

    @Test fun `live ancestor stats cross a citing reply for insert duplicate and deletion`() = runTest {
        val root = note(1)
        val reply = note(2, listOf(edge(root, "root"), citation), commenter)
        val child = note(3, listOf(edge(reply)), commenter)
        val store = store()
        store.insert(root)
        store.statsFlow(root.id).test {
            assertEquals(0, awaitItem().replyCount)
            store.insert(reply)
            assertEquals(1, awaitItem().replyCount)
            store.insert(child)
            assertEquals(2, awaitItem().replyCount)
            store.insert(reply.copy(relayUrl = "wss://second.example"))
            assertEquals(2, store.replyCount(root.id))
            expectNoEvents()
            store.insert(note(4, listOf(listOf("e", child.id)), commenter, kind = 5))
            assertEquals(1, awaitItem().replyCount)
            store.insert(note(5, listOf(listOf("e", reply.id)), commenter, kind = 5))
            assertEquals(0, awaitItem().replyCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `child arriving before its citing parent converges without restarting stats`() = runTest {
        val root = note(1)
        val reply = note(2, listOf(edge(root, "root"), citation), commenter)
        val child = note(3, listOf(edge(reply)), commenter)
        val store = store()
        store.insert(root)
        store.statsFlow(root.id).test {
            assertEquals(0, awaitItem().replyCount)
            store.insert(child)
            assertEquals(0, store.replyCount(root.id))
            store.insert(reply)
            assertEquals(2, awaitItem().replyCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `binary and text restoration preserve reply citations but not quote-only edges`() = runTest {
        val root = note(1)
        val reply = note(2, listOf(edge(root, "root"), citation), commenter)
        val child = note(3, listOf(listOf("e", reply.id)), commenter)
        val quoteOnly = note(4, listOf(listOf("q", root.id)), commenter)
        val source = store()
        source.insertBatch(listOf(root, reply, child, quoteOnly))
        val binary = ByteArrayOutputStream()
        DataOutputStream(binary).use { source.copySnapshotForTest(it) }
        val text = StringWriter()
        text.buffered().use { source.saveSnapshotTo(it) }
        for (useBinary in listOf(true, false)) {
            val restored = store()
            if (useBinary) {
                DataInputStream(ByteArrayInputStream(binary.toByteArray())).use { restored.restoreSnapshotBinary(it) }
            } else {
                restored.restoreSnapshotFrom(StringReader(text.toString()).buffered())
            }
            assertEquals(2, restored.replyCount(root.id))
            assertEquals(setOf(root.id, reply.id, child.id), restored.threadFlow(root.id).first().map { it.id }.toSet())
            restored.insert(reply)
            assertEquals(2, restored.replyCount(root.id))
        }
    }

    @Test fun `evicting a citing parent invalidates the root and detaches its unrooted child`() = runTest {
        val root = note(1)
        val reply = note(2, listOf(edge(root, "root"), citation), commenter)
        val child = note(3, listOf(edge(reply)))
        val store = store()
        store.insertBatch(listOf(root, reply, child))
        store.statsFlow(root.id).test {
            assertEquals(2, awaitItem().replyCount)
            store.evictOldContentEventsForTest(mapOf(1 to 0)) // Owner's root/child are anchored.
            assertEquals(0, awaitItem().replyCount)
            assertNull(store.getNostrEvent(reply.id))
            assertEquals(child.id, store.getNostrEvent(child.id)?.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `article comment descendants with citations use the same membership and invalidation`() = runTest {
        val article = note(1, listOf(listOf("d", "article")), kind = 30023)
        val coord = "30023:$owner:article"
        val comment = note(2, listOf(edge(article, "root")), commenter)
        val child = note(3, listOf(edge(comment), citation), commenter)
        val quoteOnly = note(4, listOf(listOf("a", coord), listOf("q", article.id)), commenter)
        val store = store()
        store.insertBatch(listOf(article, comment, quoteOnly))
        store.statsFlow(article.id).test {
            assertEquals(1, awaitItem().replyCount)
            store.insert(child)
            assertEquals(2, awaitItem().replyCount)
            assertEquals(listOf(comment.id, child.id), store.articleCommentsFlow(coord).first().map { it.id })
            store.insert(note(5, listOf(listOf("e", child.id)), commenter, kind = 5))
            assertEquals(1, awaitItem().replyCount)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
