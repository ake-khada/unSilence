package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.NostrFilter
import com.unsilence.app.data.relay.TimelineRef
import com.unsilence.app.data.relay.TimelineService
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.system.measureNanoTime

/** The reading experience needs retained bodies, not just a remembered reply total. */
class ActiveReadingAdmissionTest {
    private val provider = stubTimelineServiceProvider()
    private val store = MemoryEventStore(object : MuteKeyProvider {}, provider).apply {
        ownPubkey = "owner"
    }
    private val cap = CONTENT_EVENT_KIND_CAPS.getValue(1)

    @Test fun `opening a thread admits its first replies before the viewport can report them`() = runTest {
        val root = note("root").copy(pubkey = "owner")
        store.insert(root)
        fillTimelineHistory()
        store.retainRequestedEvent(root.id).use {
            val replies = (0 until 12).map { note("reply-$it", root.id) }
            replies.forEach { assertTrue("The requested reply body must enter MES", store.insert(it)) }
            assertEquals(replies.map { it.id }.toSet(),
                store.threadFeedRowFlow(root.id).first().filter { it.id != root.id }.map { it.id }.toSet())
            assertEquals(cap + 1, store.snapshotSize().eventsByKind[1]) // Own root is exempt.
        }
    }

    @Test fun `legacy nested replies resolve known ancestors before first presentation`() {
        val root = note("root").copy(pubkey = "owner")
        val parent = note("parent", root.id)
        val middle = note("middle", parent.id)
        store.insertBatch(listOf(root, parent, middle))
        fillTimelineHistory(listOf(parent, middle))
        store.retainRequestedEvent(root.id).use {
            val child = note("child", middle.id)
            assertNull(child.rootId)
            assertTrue("Known parent chain must reach the active root before rendering", store.insert(child))
            assertNotNull(store.getNostrEvent(child.id))
            assertEquals(3, store.replyCount(root.id))
        }
    }

    @Test fun `a visible card keeps the loaded reply bodies behind its count until release`() {
        val root = note("root")
        val replies = (0 until 12).map { note("reply-$it", root.id) }
        store.insertBatch(listOf(root) + replies)
        store.openDisplayWindow().use { window ->
            window.update(listOf(root.id to root.id), setOf(root.id))
            store.evictOldContentEventsForTest(mapOf(1 to 0))
            assertEquals(12, store.replyCount(root.id))
            assertTrue("Retained counts must have retained bodies", replies.all { store.getNostrEvent(it.id) != null })
        }
        store.evictOldContentEventsForTest(mapOf(1 to 0))
        assertTrue(replies.all { store.getNostrEvent(it.id) == null })
    }

    @Test fun `nearby conversation cannot prevent a visible reply body from being admitted`() {
        val nearby = note("nearby-root").copy(pubkey = "owner")
        val active = note("active-root").copy(pubkey = "owner")
        store.insertBatch(listOf(nearby, active))
        repeat(cap) { assertTrue(store.insert(note("nearby-$it", nearby.id))) }
        val incoming = note("visible-reply", active.id)
        store.openDisplayWindow().use { window ->
            window.update(listOf(active.id to active.id, incoming.id to incoming.id, nearby.id to nearby.id),
                setOf(active.id, incoming.id))
            assertTrue("Visible body wins even when nearby conversation fills the pool", store.insert(incoming))
            assertNotNull(store.getNostrEvent(incoming.id))
            assertEquals(cap + 2, store.snapshotSize().eventsByKind[1])
        }
    }

    @Test fun `an active first page can displace nearby conversation history before rendering`() {
        val nearby = note("nearby-root").copy(pubkey = "owner")
        val active = note("active-root").copy(pubkey = "owner")
        store.insertBatch(listOf(nearby, active))
        repeat(cap) { assertTrue(store.insert(note("nearby-$it", nearby.id))) }
        store.openDisplayWindow().use { window ->
            window.update(listOf(active.id to active.id, nearby.id to nearby.id), setOf(active.id))
            store.retainRequestedEvent(active.id).use {
                val reply = note("new-first-page", active.id)
                assertTrue("Initial page must not depend on already being rendered", store.insert(reply))
                assertNotNull(store.getNostrEvent(reply.id))
                assertEquals(cap + 2, store.snapshotSize().eventsByKind[1])
            }
        }
    }

    @Test fun `overlapping windows retain bodies until the last owner releases`() {
        store.insertBatch(listOf(note("root"), note("reply", "root")))
        val first = store.openDisplayWindow()
        val second = store.openDisplayWindow()
        first.update(listOf("root" to "root"), setOf("root"))
        second.update(listOf("root" to "root"), setOf("root"))
        first.close()
        store.evictOldContentEventsForTest(mapOf(1 to 0))
        assertNotNull(store.getNostrEvent("reply"))
        second.close()
        store.evictOldContentEventsForTest(mapOf(1 to 0))
        assertNull(store.getNostrEvent("reply"))
        assertEquals(0, store.replyCount("root"))
    }

    @Test fun `valid deletion wins over reading protection and refetch cannot resurrect it`() {
        val reply = note("reply", "root")
        store.insertBatch(listOf(note("root"), reply))
        store.openDisplayWindow().use { window ->
            window.update(listOf("root" to "root"), setOf("root"))
            val deletion = note("delete").copy(kind = 5, tags = listOf(listOf("e", reply.id)))
            store.insert(deletion.copy(id = "foreign", pubkey = "other"))
            assertNotNull(store.getNostrEvent(reply.id))
            store.insert(deletion)
            assertNull(store.getNostrEvent(reply.id))
            assertEquals(0, store.replyCount("root"))
            assertFalse(store.insert(reply))
        }
    }

    @Test fun `article coordinate and legacy child retain real bodies without changing kind1111 cap`() = runTest {
        val coord = "30023:unfollowed:article"
        val article = note("article").copy(kind = 30023, tags = listOf(listOf("d", "article")))
        val comment = note("comment").copy(kind = 1111,
            tags = listOf(listOf("A", coord), listOf("K", "30023")))
        store.insertBatch(listOf(article, comment, note("nested", comment.id)))
        assertFalse("No new kind cap in this slice", 1111 in CONTENT_EVENT_KIND_CAPS)
        store.openDisplayWindow().use { window ->
            window.update(listOf(article.id to article.id), setOf(article.id))
            store.evictOldContentEventsForTest(mapOf(1 to 0, 1111 to 0))
            assertNotNull(store.getNostrEvent(comment.id))
            assertNotNull(store.getNostrEvent("nested"))
            assertEquals(2, store.replyCount(article.id))
            assertEquals(setOf(comment.id, "nested"), store.articleCommentsFlow(coord).first().map { it.id }.toSet())
        }
    }

    @Test fun `cycle safe ancestry and account teardown do not leave indefinite protection`() {
        store.insertBatch(listOf(note("root"), note("a", "b").copy(rootId = "root"), note("b", "a")))
        val window = store.openDisplayWindow()
        window.update(listOf("root" to "root"), setOf("root"))
        store.evictOldContentEventsForTest(mapOf(1 to 0))
        assertNotNull(store.getNostrEvent("a"))
        assertNotNull(store.getNostrEvent("b"))
        store.clearUserState()
        store.evictOldContentEventsForTest(mapOf(1 to 0))
        assertNull(store.getNostrEvent("a"))
        assertNull(store.getNostrEvent("b"))
        window.close()
    }

    @Test fun `repeated delivery does not inflate body derived counts`() {
        val reply = note("reply", "root")
        store.insertBatch(listOf(note("root"), reply))
        store.openDisplayWindow().use { window ->
            window.update(listOf("root" to "root"), setOf("root"))
            repeat(20) { assertFalse(store.insert(reply)) }
            assertEquals(1, store.replyCount("root"))
            assertNotNull(store.getNostrEvent(reply.id))
        }
    }

    @Test fun `equal priority saturation rejects a newcomer without replacing loaded replies`() {
        store.insertBatch(listOf(note("a").copy(pubkey = "owner"), note("b").copy(pubkey = "owner")))
        repeat(cap) { store.insert(note("held-$it", "a")) }
        store.openDisplayWindow().use { window ->
            window.update(listOf("a" to "a", "b" to "b"), setOf("a", "b"))
            assertFalse(store.insert(note("new", "b")))
            assertTrue((0 until cap).all { store.getNostrEvent("held-$it") != null })
            assertEquals(cap + 2, store.snapshotSize().eventsByKind[1])
        }
    }

    @Test fun `closing a requested root leaves no descendant priority behind`() {
        store.insert(note("root").copy(pubkey = "owner"))
        fillTimelineHistory()
        store.retainRequestedEvent("root").use {
            assertTrue(store.insert(note("reply", "root")))
        }
        assertTrue("An ordinary arrival can replace the released ordinary reply", store.insert(note("next")))
        assertNull(store.getNostrEvent("reply"))
        assertEquals(cap + 1, store.snapshotSize().eventsByKind[1])
    }

    @Test fun `final removal rechecks a victim that became an actual window row`() {
        store.insert(note("victim"))
        val index = MemoryEventStore::class.java.getDeclaredField("contentAdmissionIndex")
            .apply { isAccessible = true }.get(store) as ContentAdmissionIndex
        assertEquals("victim", index.pollVictim(1, 0) { ContentEvictionTier.ORDINARY }?.eventId)
        store.openDisplayWindow().use { window ->
            window.update(listOf("victim" to "victim"), setOf("victim"))
            val remove = MemoryEventStore::class.java.getDeclaredMethod(
                "removeContentEventForEviction", String::class.java, MutableSet::class.java,
                NostrEvent::class.java, Set::class.java,
            ).apply { isAccessible = true }
            assertNull(remove.invoke(store, "victim", mutableSetOf<String>(), note("incoming"), null))
            assertNotNull(store.getNostrEvent("victim"))
            assertEquals("Final check restores the reserved accounting entry", 1, index.size(1))
        }
    }

    @Test fun `final relative removal stops when the incoming reading lease ends`() {
        store.insertBatch(listOf(note("nearby"), note("active"), note("victim", "nearby")))
        store.openDisplayWindow().use { window ->
            window.update(listOf("nearby" to "nearby"), emptySet())
            val lease = store.retainRequestedEvent("active")
            val incoming = note("incoming", "active")
            val remove = MemoryEventStore::class.java.getDeclaredMethod(
                "removeContentEventForEviction", String::class.java, MutableSet::class.java,
                NostrEvent::class.java, Set::class.java,
            ).apply { isAccessible = true }
            lease.close()
            assertNull(remove.invoke(store, "victim", mutableSetOf<String>(), incoming, null))
            assertNotNull(store.getNostrEvent("victim"))
            store.retainRequestedEvent("active").use {
                assertNotNull(remove.invoke(store, "victim", mutableSetOf<String>(), incoming, null))
                assertNull(store.getNostrEvent("victim"))
            }
        }
    }

    @Test fun `shared cold ancestors do not inherit protection from a different branch`() {
        store.insertBatch(listOf(
            note("root").copy(pubkey = "owner"), note("cold-parent"),
            note("owned", "cold-parent").copy(rootId = "root"), note("unrelated", "cold-parent"),
        ))
        store.openDisplayWindow().use { window ->
            window.update(listOf("root" to "root"), setOf("root"))
            store.evictOldContentEventsForTest(mapOf(1 to 0))
            assertNotNull(store.getNostrEvent("owned"))
            assertNull(store.getNostrEvent("unrelated"))
            assertNull(store.getNostrEvent("cold-parent"))
            assertEquals(1, store.replyCount("root"))
        }
    }

    @Test fun `mixed pressure keeps reading bodies while admitting and sweeping unrelated history`() {
        store.insert(note("root").copy(pubkey = "owner"))
        repeat(3_000) { store.insert(note("reading-$it", "root")) }
        repeat(2_000) { store.insert(note("history-$it")) }
        store.openDisplayWindow().use { window ->
            window.update(listOf("root" to "root"), setOf("root"))
            val admissions = (0 until 20).map { index ->
                measureNanoTime { assertTrue(store.insert(note("arrival-$index"))) } / 1_000_000.0
            }.sorted()
            val sweep = measureNanoTime { store.evictOldContentEventsForTest(mapOf(1 to 3_000)) } / 1_000_000.0
            assertTrue((0 until 3_000).all { store.getNostrEvent("reading-$it") != null })
            assertEquals(3_001, store.snapshotSize().eventsByKind[1])
            // Opt-in host diagnostics only, never production logging or a device performance claim.
            if (System.getenv("UNSILENCE_READING_COST") == "1") {
                println("reading-pressure admission medianMs=${admissions[10]} maxMs=${admissions.last()} sweepMs=$sweep")
            }
        }
    }

    @Test fun `shared legacy ancestry survives mixed admission and sweep pressure`() {
        store.insert(note("root").copy(pubkey = "owner"))
        repeat(64) { store.insert(note("parent-$it", if (it == 0) "root" else "parent-${it - 1}")) }
        repeat(3_000 - 64) { store.insert(note("leaf-$it", "parent-63")) }
        repeat(2_000) { store.insert(note("history-$it")) }
        store.openDisplayWindow().use { window ->
            window.update(listOf("root" to "root"), setOf("root"))
            val admissions = (0 until 10).map { index ->
                measureNanoTime { assertTrue(store.insert(note("arrival-$index"))) } / 1_000_000.0
            }.sorted()
            val sweep = measureNanoTime { store.evictOldContentEventsForTest(mapOf(1 to 3_000)) } / 1_000_000.0
            assertTrue((0 until 3_000 - 64).all { store.getNostrEvent("leaf-$it") != null })
            assertTrue((0 until 64).all { store.getNostrEvent("parent-$it") != null })
            assertEquals(3_001, store.snapshotSize().eventsByKind[1])
            if (System.getenv("UNSILENCE_READING_COST") == "1") {
                println("legacy-pressure admission medianMs=${admissions[5]} maxMs=${admissions.last()} sweepMs=$sweep")
            }
        }
    }

    private fun fillTimelineHistory(existing: List<NostrEvent> = emptyList()) {
        val notes = existing + (existing.size until cap).map { note("history-$it") }
        store.insertBatch(notes.filterNot { it in existing })
        val timelines = notes.chunked(500).mapIndexed { index, chunk ->
            val urls = listOf("wss://history-$index.example")
            val filter = NostrFilter(kinds = listOf(1), limit = 500)
            val service = provider.get()
            service.generateTimelineKey(urls, filter) to TimelineService.Timeline(
                refs = chunk.map { TimelineRef(it.id, it.createdAt) }, filter = filter, urls = urls,
            )
        }.toMap()
        provider.get().restoreFromSnapshot(timelines)
        assertEquals(cap, provider.get().liveReferencedIds().size)
    }

    private fun note(id: String, parent: String? = null) = NostrEvent(
        id = id, pubkey = "unfollowed", kind = 1, content = "body-$id", createdAt = 1L,
        tags = parent?.let { listOf(listOf("e", it, "", "reply")) }.orEmpty(),
        sig = "", relayUrl = "wss://fixture.example", replyToId = parent, rootId = null,
        hasContentWarning = false, contentWarningReason = null, firstSeenAt = 1L,
        relaysSeen = mutableSetOf("wss://fixture.example"),
    )
}
