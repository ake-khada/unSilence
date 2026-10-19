package com.unsilence.app.data.memory

import app.cash.turbine.test
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import com.unsilence.app.data.relay.toNostrEvent
import com.unsilence.app.ui.thread.flattenThreadReplies
import java.io.DataInputStream
import java.io.StringReader
import java.io.StringWriter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic NIP-22 wire fixtures; no personal events or relay requests. */
class Nip22PollRepliesTest {
    @get:Rule val temp = TemporaryFolder()
    private val owner = "a".repeat(64)
    private val commenter = "b".repeat(64)
    private val relay = "wss://relay.example"

    private fun store(viewer: String = owner) =
        MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider()).apply {
            ownPubkey = viewer
        }

    private fun event(
        id: Int,
        kind: Int = 1068,
        author: String = owner,
        tags: List<List<String>> = emptyList(),
    ): NostrEvent = EventDto(
        id = id.toString(16).padStart(64, '0'),
        pubkey = author,
        kind = kind,
        content = "Synthetic poll conversation",
        createdAt = id.toLong(),
        tags = tags,
        sig = "0".repeat(128),
    ).toNostrEvent(relay)

    private fun comment(id: Int, parent: NostrEvent, poll: NostrEvent = parent): NostrEvent = event(
        id, 1111, commenter, listOf(
            listOf("E", poll.id, relay, poll.pubkey),
            listOf("K", "1068"),
            listOf("P", poll.pubkey, relay),
            listOf("e", parent.id, relay, parent.pubkey),
            listOf("k", parent.kind.toString()),
            listOf("p", parent.pubkey, relay),
        ),
    )

    @Test fun `poll reply notification thread count and actors agree for all viewers`() = runTest {
        val poll = event(1)
        val reply = comment(2, poll)
        for (viewer in listOf(owner, "c".repeat(64))) {
            val store = store(viewer)
            store.insertBatch(listOf(poll, reply))
            val notification = store.getNotifications(owner).single() as NotificationRow.Single
            assertEquals("reply", notification.notifType)
            assertEquals(reply.id, notification.id)
            if (viewer != owner) assertTrue(store.getNotifications(viewer).isEmpty())
            val rows = store.threadFeedRowFlow(poll.id).first().filter { it.id != poll.id }
            assertEquals(listOf(reply.id), flattenThreadReplies(poll.id, rows, false).map { it.row.id })
            assertEquals(1, store.replyCount(poll.id))
            assertEquals(listOf(commenter), store.replyPubkeysForEvent(poll.id))
        }
    }

    @Test fun `legacy kind 1 poll reply remains visible and counted`() = runTest {
        val store = store()
        val poll = event(1)
        val reply = event(2, 1, commenter, listOf(
            listOf("e", poll.id, relay, "root"), listOf("p", owner),
        ))
        store.insertBatch(listOf(poll, reply))
        assertEquals(1, store.replyCount(poll.id))
        assertEquals(setOf(poll.id, reply.id), store.threadFlow(poll.id).first().map { it.id }.toSet())
    }

    @Test fun `nested poll comment stays visible before parent and reattaches on arrival`() = runTest {
        val store = store()
        val poll = event(1)
        val parent = comment(2, poll)
        val child = comment(3, parent, poll)
        store.insert(poll)
        store.statsFlow(poll.id).test {
            assertEquals(0, awaitItem().replyCount)
            store.insert(child)
            assertEquals(1, awaitItem().replyCount)
            var rows = store.threadFeedRowFlow(poll.id).first().filter { it.id != poll.id }
            var rendered = flattenThreadReplies(poll.id, rows, false)
            assertEquals(listOf(child.id), rendered.map { it.row.id })
            assertEquals(listOf(1), rendered.map { it.depth })
            store.insert(parent)
            assertEquals(2, awaitItem().replyCount)
            rows = store.threadFeedRowFlow(poll.id).first().filter { it.id != poll.id }
            rendered = flattenThreadReplies(poll.id, rows, false)
            assertEquals(listOf(parent.id, child.id), rendered.map { it.row.id })
            assertEquals(listOf(1, 2), rendered.map { it.depth })
            assertEquals(1, store.replyCount(parent.id))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `duplicate deliveries count once and deletion removes only the deleted comment`() = runTest {
        val store = store()
        val poll = event(1)
        val parent = comment(2, poll)
        val child = comment(3, parent, poll)
        store.insertBatch(listOf(poll, parent, child))
        store.statsFlow(poll.id).test {
            assertEquals(2, awaitItem().replyCount)
            store.insert(child.copy(relayUrl = "wss://second.example"))
            assertEquals(2, store.replyCount(poll.id))
            expectNoEvents()
            store.insert(event(4, 5, commenter, listOf(listOf("e", parent.id))))
            assertEquals(1, awaitItem().replyCount)
            val rows = store.threadFeedRowFlow(poll.id).first().filter { it.id != poll.id }
            assertEquals(listOf(child.id), flattenThreadReplies(poll.id, rows, false).map { it.row.id })
            store.insert(event(5, 5, commenter, listOf(listOf("e", child.id))))
            assertEquals(0, awaitItem().replyCount)
            assertEquals(listOf(poll.id), store.threadFlow(poll.id).first().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `poll scope without a comment parent and engagement are not replies`() = runTest {
        val store = store()
        val poll = event(1)
        val scopeOnly = event(2, 1111, commenter, listOf(listOf("E", poll.id), listOf("K", "1068")))
        val quote = event(3, 1, commenter, listOf(listOf("q", poll.id)))
        val vote = event(4, 1018, commenter, listOf(listOf("e", poll.id), listOf("response", "option")))
        val reaction = event(5, 7, commenter, listOf(listOf("e", poll.id)))
        val repost = event(6, 16, commenter, listOf(listOf("e", poll.id), listOf("k", "1068")))
        val unsupported = event(7, 1111, commenter, listOf(
            listOf("E", poll.id), listOf("K", "1068"), listOf("e", poll.id), listOf("k", "30078"),
        ))
        store.insertBatch(listOf(poll, scopeOnly, quote, vote, reaction, repost, unsupported))
        assertEquals(0, store.replyCount(poll.id))
        assertEquals(listOf(poll.id), store.threadFlow(poll.id).first().map { it.id })
    }

    @Test fun `poll root does not attach a nested reply to an unrelated poll`() = runTest {
        val store = store()
        val poll = event(1)
        val other = event(2)
        val parent = comment(3, other)
        val child = comment(4, parent, other)
        store.insertBatch(listOf(poll, other, child))
        assertEquals(0, store.replyCount(poll.id))
        assertEquals(1, store.replyCount(other.id))
        assertEquals(setOf(other.id, child.id), store.threadFlow(other.id).first().map { it.id }.toSet())
    }

    @Test fun `muting still applies to orphan and nested poll comments`() = runTest {
        val store = store()
        val poll = event(1)
        val parent = comment(2, poll)
        val child = comment(3, parent, poll)
        store.insertBatch(listOf(poll, child))
        var rows = store.threadFeedRowFlow(poll.id).first().filter { it.id != poll.id }
        assertTrue(flattenThreadReplies(poll.id, rows, false, setOf(child.id)).isEmpty())
        store.insert(parent)
        rows = store.threadFeedRowFlow(poll.id).first().filter { it.id != poll.id }
        val rendered = flattenThreadReplies(poll.id, rows, false, setOf(parent.id))
        assertEquals(listOf(parent.id, child.id), rendered.map { it.row.id })
        assertEquals(listOf(true, false), rendered.map { it.muted })
        assertTrue(flattenThreadReplies(poll.id, rows, false, setOf(parent.id, child.id)).isEmpty())
        assertEquals(2, store.replyCount(poll.id)) // Moderation does not rewrite known membership.
    }

    @Test fun `restoring old top level poll comment rebuilds root without changing signed fields`() {
        val store = store()
        val poll = event(1)
        val reply = comment(2, poll).copy(rootId = null)
        store.insertFromSnapshot(poll)
        store.insertFromSnapshot(reply)
        val restored = store.getNostrEvent(reply.id)!!
        assertEquals(poll.id, restored.replyToId)
        assertEquals(poll.id, restored.rootId)
        assertEquals(reply.copy(rootId = poll.id), restored)
        assertEquals(1, store.replyCount(poll.id))
    }

    @Test fun `binary snapshots preserve poll membership and repair old missing root fields`() = runTest {
        val poll = event(1)
        val parent = comment(2, poll)
        val child = comment(3, parent, poll)
        for (version in listOf(19, 20)) for (oldFields in listOf(false, true)) {
            val source = store()
            source.insertBatch(listOf(poll, child.let { if (oldFields) it.copy(rootId = null) else it }))
            val file = temp.newFile("poll-$version-$oldFields.bin")
            file.outputStream().use { source.saveSnapshotBinary(it, version) }
            val restored = store()
            DataInputStream(file.inputStream()).use { restored.restoreSnapshotBinary(it) }
            assertEquals(poll.id, restored.getNostrEvent(child.id)?.rootId)
            assertEquals(parent.id, restored.getNostrEvent(child.id)?.replyToId)
            assertEquals(1, restored.replyCount(poll.id))
            val rows = restored.threadFeedRowFlow(poll.id).first().filter { it.id != poll.id }
            assertEquals(listOf(child.id), flattenThreadReplies(poll.id, rows, false).map { it.row.id })
            restored.insert(parent)
            assertEquals(2, restored.replyCount(poll.id))
        }
    }

    @Test fun `legacy text snapshot repairs a poll comment missing its parsed root`() = runTest {
        val poll = event(1)
        val parent = comment(2, poll)
        val child = comment(3, parent, poll)
        val source = store()
        source.insertBatch(listOf(poll, child.copy(rootId = null)))
        val text = StringWriter()
        text.buffered().use { source.saveSnapshotTo(it) }
        val restored = store()
        restored.restoreSnapshotFrom(StringReader(text.toString()).buffered())
        assertEquals(poll.id, restored.getNostrEvent(child.id)?.rootId)
        assertEquals(1, restored.replyCount(poll.id))
        assertEquals(setOf(poll.id, child.id), restored.threadFlow(poll.id).first().map { it.id }.toSet())
    }
}
