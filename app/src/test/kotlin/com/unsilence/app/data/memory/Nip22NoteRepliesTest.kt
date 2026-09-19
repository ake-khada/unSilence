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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic wire tags matching the reported kind-1111 reply to a kind-1 note. */
class Nip22NoteRepliesTest {
    private val owner = "a".repeat(64)
    private val commenter = "b".repeat(64)
    private val relay = "wss://relay.example"

    private fun store(viewer: String = owner) =
        MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider()).apply {
            ownPubkey = viewer
        }

    private fun wireEvent(
        id: Int,
        kind: Int = 1,
        author: String = owner,
        tags: List<List<String>> = emptyList(),
    ): NostrEvent = EventDto(
        id = id.toString(16).padStart(64, '0'),
        pubkey = author,
        kind = kind,
        content = "Synthetic conversation fixture",
        createdAt = id.toLong(),
        tags = tags,
        sig = "0".repeat(128),
    ).toNostrEvent(relay)

    private fun comment(id: Int, parent: NostrEvent, root: NostrEvent = parent): NostrEvent = wireEvent(
        id = id,
        kind = 1111,
        author = commenter,
        tags = listOf(
            listOf("E", root.id, relay, root.pubkey),
            listOf("K", root.kind.toString()),
            listOf("P", root.pubkey, relay),
            listOf("e", parent.id, relay, parent.pubkey),
            listOf("k", parent.kind.toString()),
            listOf("p", parent.pubkey, relay),
        ),
    )

    @Test fun `reply notification thread and count agree for recipient and other viewers`() = runTest {
        val root = wireEvent(1)
        val reply = comment(2, root)
        assertEquals(root.id, reply.replyToId)
        for (viewer in listOf(owner, "c".repeat(64))) {
            val store = store(viewer)
            store.insertBatch(listOf(root, reply))
            val notification = store.getNotifications(owner).single() as NotificationRow.Single
            assertEquals("reply", notification.notifType)
            assertEquals(reply.id, notification.id)
            if (viewer != owner) assertTrue(store.getNotifications(viewer).isEmpty())
            val rows = store.threadFeedRowFlow(root.id).first().filter { it.id != root.id }
            val rendered = flattenThreadReplies(root.id, rows, coordinateScoped = false)
            assertEquals(listOf(reply.id), rendered.map { it.row.id })
            assertEquals(1, store.replyCount(root.id))
            assertEquals(listOf(commenter), store.replyPubkeysForEvent(root.id))
        }
    }

    @Test fun `live stats count a new comment once even after a duplicate relay arrival`() = runTest {
        val store = store()
        val root = wireEvent(1)
        val reply = comment(2, root)
        store.insert(root)
        store.statsFlow(root.id).test {
            assertEquals(0, awaitItem().replyCount)
            store.insert(reply)
            assertEquals(1, awaitItem().replyCount)
            store.insert(reply.copy(relayUrl = "wss://second.example"))
            assertEquals(1, store.replyCount(root.id))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `mixed nested comments and legacy replies share the same rendered membership`() = runTest {
        val store = store()
        val root = wireEvent(1)
        val reply = comment(2, root)
        val nested = comment(3, reply, root)
        val legacy = wireEvent(4, author = "c".repeat(64), tags = listOf(
            listOf("e", root.id, relay, "root"),
            listOf("e", nested.id, relay, "reply"),
        ))
        store.insertBatch(listOf(root, reply, nested, legacy))
        val rows = store.threadFeedRowFlow(root.id).first().filter { it.id != root.id }
        val rendered = flattenThreadReplies(root.id, rows, coordinateScoped = false)
        assertEquals(listOf(reply.id, nested.id, legacy.id), rendered.map { it.row.id })
        assertEquals(listOf(1, 2, 3), rendered.map { it.depth })
        assertEquals(3, store.replyCount(root.id))
        assertEquals(2, store.replyCount(reply.id))
    }

    @Test fun `a nested comment arriving before its parent converges in live root stats`() = runTest {
        val store = store()
        val root = wireEvent(1)
        val reply = comment(2, root)
        val nested = comment(3, reply, root)
        store.insert(root)
        store.statsFlow(root.id).test {
            assertEquals(0, awaitItem().replyCount)
            store.insert(nested)
            assertEquals(0, store.replyCount(root.id))
            store.insert(reply)
            assertEquals(2, awaitItem().replyCount)
            assertEquals(setOf(root.id, reply.id, nested.id), store.threadFlow(root.id).first().map { it.id }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `deleting a comment invalidates the existing root count`() = runTest {
        val store = store()
        val root = wireEvent(1)
        val reply = comment(2, root)
        store.insertBatch(listOf(root, reply))
        store.statsFlow(root.id).test {
            assertEquals(1, awaitItem().replyCount)
            store.insert(wireEvent(3, kind = 5, author = commenter, tags = listOf(listOf("e", reply.id))))
            assertEquals(0, awaitItem().replyCount)
            assertEquals(listOf(root.id), store.threadFlow(root.id).first().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `binary and legacy text snapshots rebuild note comment membership`() = runTest {
        val source = store()
        val root = wireEvent(1)
        val reply = comment(2, root)
        val nested = comment(3, reply, root)
        source.insertBatch(listOf(root, reply, nested))
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
            assertEquals(setOf(root.id, reply.id, nested.id), restored.threadFlow(root.id).first().map { it.id }.toSet())
            restored.insert(reply)
            assertEquals(2, restored.replyCount(root.id))
        }
    }

    @Test fun `root scope quotes and engagement do not create a direct comment edge`() = runTest {
        val store = store()
        val root = wireEvent(1)
        val other = wireEvent(2)
        val elsewhere = comment(3, other, root)
        val scopeOnly = wireEvent(4, kind = 1111, author = commenter, tags = listOf(
            listOf("E", root.id), listOf("K", "1"), listOf("q", root.id),
        ))
        val quoteOnly = wireEvent(5, author = commenter, tags = listOf(listOf("q", root.id)))
        val reaction = wireEvent(6, kind = 7, author = commenter, tags = listOf(listOf("e", root.id)))
        val repost = wireEvent(7, kind = 16, author = commenter, tags = listOf(listOf("e", root.id)))
        val unknownParentKind = comment(8, root).copy(tags = listOf(listOf("e", root.id), listOf("k", "30078")))
        store.insertBatch(listOf(root, other, elsewhere, scopeOnly, quoteOnly, reaction, repost, unknownParentKind))
        assertEquals(0, store.replyCount(root.id))
        assertEquals(listOf(root.id), store.threadFlow(root.id).first().map { it.id })
        assertFalse(store.conversationMembership(root.id).truncated)
    }

    @Test fun `existing event addressed video and comment parents remain supported`() {
        for (kind in listOf(21, 22, 1111)) {
            val store = store()
            val root = wireEvent(1, kind = kind)
            val reply = comment(2, root)
            store.insertBatch(listOf(root, reply))
            assertEquals(1, store.replyCount(root.id))
        }
    }
}
