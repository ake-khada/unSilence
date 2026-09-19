package com.unsilence.app.data.memory

import app.cash.turbine.test
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import com.unsilence.app.ui.thread.flattenThreadReplies
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class ConversationMembershipTest {
    private fun store() = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
    private fun event(
        id: String,
        parent: String? = null,
        root: String? = null,
        pubkey: String = "author",
        kind: Int = 1,
        tags: List<List<String>> = emptyList(),
        createdAt: Long = 1,
    ) = NostrEvent(
        id = id, pubkey = pubkey, kind = kind, content = "reply", createdAt = createdAt,
        tags = tags, sig = "sig", relayUrl = "wss://relay.example", replyToId = parent, rootId = root,
        hasContentWarning = false, contentWarningReason = null, firstSeenAt = 1,
        relaysSeen = ConcurrentHashMap.newKeySet<String>(),
    )

    @Test
    fun `legacy descendants count exactly the same members as the rendered thread`() = runTest {
        val store = store()
        store.insertBatch(listOf(event("A"), event("B", "A", pubkey = "bob"), event("C", "B", pubkey = "carol")))
        val rows = store.threadFeedRowFlow("A").first().filter { it.id != "A" }
        val rendered = flattenThreadReplies("A", rows, coordinateScoped = false)
        assertEquals(setOf("B", "C"), rendered.map { it.row.id }.toSet())
        assertEquals(rendered.size, store.replyCount("A"))
        assertEquals(setOf("bob", "carol"), store.replyPubkeysForEvent("A").toSet())
    }

    @Test
    fun `already running root stats observe a legacy reply to a child`() = runTest {
        val store = store()
        store.insertBatch(listOf(event("A"), event("B", "A")))
        store.statsFlow("A").test {
            assertEquals(1, awaitItem().replyCount)
            store.insert(event("C", "B")) // no root tag pointing at A
            assertEquals(2, awaitItem().replyCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `batch of nested replies emits only the coalesced root total`() = runTest {
        val store = store()
        store.insertBatch(listOf(event("A"), event("B", "A")))
        store.statsFlow("A").test {
            assertEquals(1, awaitItem().replyCount)
            store.insertBatch(listOf(event("C", "B"), event("D", "C"), event("E", "D")))
            assertEquals(4, awaitItem().replyCount)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reply citations stay in the graph but quote-only subtrees and engagement do not`() = runTest {
        val store = store()
        val quote = listOf(listOf("q", "somewhere-else"))
        store.insertBatch(listOf(
            event("A"), event("B", "A"), event("direct-quote", "A", tags = quote),
            event("nested-quote", "B", tags = quote), event("quote-child", "nested-quote"),
            event("quote-only", tags = listOf(listOf("q", "A"))), event("unrelated-child", "quote-only"),
            event("repost", "B", kind = 16), event("reaction", "B", kind = 7),
        ))
        val replies = setOf("B", "direct-quote", "nested-quote", "quote-child")
        assertEquals(replies, store.conversationMembership("A").ids)
        assertEquals(replies + "A", store.threadFlow("A").first().map { it.id }.toSet())
        assertEquals(4, store.replyCount("A"))
    }

    @Test
    fun `cycle terminates without counting the root itself or a member twice`() = runTest {
        val store = store()
        store.insertBatch(listOf(event("A", "C"), event("B", "A"), event("C", "B")))
        assertEquals(setOf("B", "C"), store.conversationMembership("A").ids)
        assertEquals(2, store.replyCount("A"))
        assertEquals(3, store.threadFlow("A").first().size)
    }

    @Test
    fun `one wide parent cannot overshoot the traversal bound`() {
        val events = (listOf(event("parent")) + (1..100).map { event("child-$it", "parent") })
            .associateBy { it.id }
        val result = expandConversationDescendants(
            sequenceOf("parent"), "root", 7, events::get,
            childrenOf = { parent -> if (parent == "parent") (1..100).map { "child-$it" } else emptyList() },
        )
        assertEquals(7, result.ids.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `direct seeds are capped too and exact cap does not falsely claim truncation`() {
        val events = (1..9).associate { "c$it" to event("c$it") }
        fun expand(ids: Sequence<String>) = expandConversationDescendants(
            ids, "root", 8, events::get, childrenOf = { emptyList() },
        )
        assertEquals(8, expand(events.keys.asSequence()).ids.size)
        assertTrue(expand(events.keys.asSequence()).truncated)
        assertFalse(expand(events.keys.take(8).asSequence()).truncated)
    }

    @Test
    fun `duplicate missing and self seeds are not counted`() {
        val events = listOf(event("root"), event("child")).associateBy { it.id }
        val result = expandConversationDescendants(
            sequenceOf("root", "missing", "child", "child"), "root", 2, events::get,
            childrenOf = { listOf("root", "child") },
        )
        assertEquals(setOf("child"), result.ids)
        assertFalse(result.truncated)
    }

    @Test
    fun `deletion of a nested leaf or intermediate parent notifies running ancestors`() = runTest {
        for ((deletedId, expected) in listOf("C" to 1, "B" to 0)) {
            val store = store()
            store.insertBatch(listOf(event("A"), event("B", "A"), event("C", "B")))
            store.statsFlow("A").test {
                assertEquals(2, awaitItem().replyCount)
                store.insert(event("delete", kind = 5, tags = listOf(listOf("e", deletedId))))
                assertEquals(expected, awaitItem().replyCount)
                assertNull(store.getNostrEvent(deletedId))
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `eviction captures ancestor links before removing a nested leaf or its parent`() = runTest {
        for ((evictedId, expected) in listOf("C" to 1, "B" to 0)) {
            val store = store()
            store.ownPubkey = "keep"
            for ((id, parent) in listOf("A" to null, "B" to "A", "C" to "B")) {
                store.insert(event(id, parent, pubkey = if (id == evictedId) "evict" else "keep"))
            }
            store.statsFlow("A").test {
                assertEquals(2, awaitItem().replyCount)
                store.evictOldContentEventsForTest(mapOf(1 to 0))
                assertEquals(expected, awaitItem().replyCount)
                assertNull(store.getNostrEvent(evictedId))
                assertNotNull(store.getNostrEvent("A"))
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `child before parent converges without restarting the stats collector`() = runTest {
        val store = store()
        store.insert(event("A"))
        store.statsFlow("A").test {
            assertEquals(0, awaitItem().replyCount)
            store.insert(event("C", "B"))
            assertEquals(0, store.replyCount("A"))
            store.insert(event("B", "A"))
            assertEquals(2, awaitItem().replyCount)
            assertEquals(3, store.threadFlow("A").first().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `article ancestor stats follow legacy descendants without coordinate tags`() = runTest {
        val store = store()
        val coord = "30023:author:slug"
        store.insert(event("article", kind = 30023, tags = listOf(listOf("d", "slug"))))
        store.insert(event("B", kind = 1111, tags = listOf(listOf("A", coord))))
        store.statsFlow("article").test {
            assertEquals(1, awaitItem().replyCount)
            store.insert(event("C", "B"))
            assertEquals(2, awaitItem().replyCount)
            store.insert(event("delete", kind = 5, tags = listOf(listOf("e", "C"))))
            assertEquals(1, awaitItem().replyCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `article counts 201 known replies independently of its 200 row page`() = runTest {
        for (chain in listOf(false, true)) {
            val store = store()
            val coord = "30023:author:slug"
            store.insert(event("article", kind = 30023, tags = listOf(listOf("d", "slug"))))
            store.insertBatch((1..201).map {
                event("c$it", parent = if (!chain || it == 1) "article" else "c${it - 1}", createdAt = it.toLong())
            })
            assertEquals(201, store.replyCount("article"))
            assertFalse(store.currentStatsSnapshot("article").replyCountTruncated)
            assertEquals(ARTICLE_COMMENT_PAGE_SIZE, store.articleCommentsFlow(coord).first().size)
        }
    }

    @Test
    fun `coordinate comments resolve before their article event is fetched`() = runTest {
        val store = store()
        val coord = "30023:author:missing"
        store.insert(event("B", kind = 1111, tags = listOf(listOf("A", coord))))
        store.insert(event("C", "B"))
        assertEquals(setOf("B", "C"), store.articleCommentsFlow(coord).first().map { it.id }.toSet())
    }

    @Test
    fun `coordinate-rooted stats observe eviction of an untagged legacy descendant`() = runTest {
        val store = store()
        store.ownPubkey = "author"
        val coord = "30023:author:slug"
        store.insert(event("article", kind = 30023, tags = listOf(listOf("d", "slug"))))
        store.insert(event("B", kind = 1111, tags = listOf(listOf("A", coord))))
        store.insert(event("C", "B", pubkey = "evict"))
        store.statsFlow("article").test {
            assertEquals(2, awaitItem().replyCount)
            store.evictOldContentEventsForTest(mapOf(1 to 0))
            assertEquals(1, awaitItem().replyCount)
            assertNull(store.getNostrEvent("C"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `kind-1111 quoting text remains a comment`() = runTest {
        val store = store()
        val coord = "30023:author:slug"
        store.insert(event("article", kind = 30023, tags = listOf(listOf("d", "slug"))))
        store.insert(event("B", kind = 1111, tags = listOf(listOf("A", coord), listOf("q", "elsewhere"))))
        store.insert(event("C", "B", kind = 1111, tags = listOf(listOf("k", "1111"), listOf("q", "elsewhere"))))
        assertEquals(setOf("B", "C"), store.conversationMembership("article").ids)
        assertEquals(2, store.articleCommentsFlow(coord).first().size)
    }

    @Test
    fun `nested root-only references invalidate the same graph that membership expands`() = runTest {
        val store = store()
        store.insertBatch(listOf(event("A"), event("B", "A")))
        store.statsFlow("A").test {
            assertEquals(1, awaitItem().replyCount)
            store.insert(event("C", root = "B"))
            assertEquals(2, awaitItem().replyCount)
            store.insert(event("D", "C"))
            assertEquals(3, awaitItem().replyCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stats expose a truncated known total without increasing the member limit`() {
        val store = store()
        store.ownPubkey = "author" // fixtures must not be evicted by the independent content cap
        store.insert(event("A"))
        store.insertBatch((0..CONVERSATION_MEMBER_LIMIT).map { event("c$it", "A") })
        val stats = store.currentStatsSnapshot("A")
        assertEquals(CONVERSATION_MEMBER_LIMIT, stats.replyCount)
        assertTrue(stats.replyCountTruncated)
    }

    @Test
    fun `unrelated reactions do not reemit a conversations stats`() = runTest {
        val store = store()
        store.insertBatch(listOf(event("A"), event("B", "A"), event("C", "B")))
        store.statsFlow("A").test {
            assertEquals(2, awaitItem().replyCount)
            store.insert(event("reaction", kind = 7, tags = listOf(listOf("e", "other"))))
            expectNoEvents()
            store.insert(event("D", "C"))
            assertEquals(3, awaitItem().replyCount)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
