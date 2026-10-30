package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import org.junit.Assert.*
import org.junit.Test

class DisplayWindowPolicyTest {
    @Test
    fun `window move releases old row and target while preserving the new pair against sweep`() {
        val store = store()
        listOf("wrapper", "target", "next").forEach { store.insert(note(it)) }
        store.openDisplayWindow().use { window ->
            window.update(listOf("wrapper" to "target"), setOf("wrapper"))
            store.evictOldContentEventsForTest(mapOf(1 to 2))
            assertNotNull(store.getNostrEvent("wrapper"))
            assertNotNull(store.getNostrEvent("target"))
            assertNull(store.getNostrEvent("next"))
            store.insert(note("next"))
            window.update(listOf("next" to "next"), setOf("next"))
            store.evictOldContentEventsForTest(mapOf(1 to 1))
            assertNull(store.getNostrEvent("wrapper"))
            assertNull(store.getNostrEvent("target"))
            assertNotNull(store.getNostrEvent("next"))
        }
    }

    @Test
    fun `only genuinely visible rows are protected`() {
        val store = store()
        store.insert(note("visible"))
        store.insert(note("lookahead"))
        store.openDisplayWindow().use { window ->
            window.update(listOf("visible" to "visible", "lookahead" to "lookahead"), setOf("visible"))
            store.evictOldContentEventsForTest(mapOf(1 to 0))
            assertNotNull(store.getNostrEvent("visible"))
            assertNull(store.getNostrEvent("lookahead"))
        }
        store.evictOldContentEventsForTest(mapOf(1 to 0))
        assertNull(store.getNostrEvent("visible"))
    }

    @Test
    fun `old account window cannot repin or release a new account window`() {
        val store = store()
        store.insert(note("old"))
        store.insert(note("new"))
        val old = store.openDisplayWindow()
        old.update(listOf("old" to "old"), setOf("old"))
        store.clearUserState()
        store.openDisplayWindow().use { current ->
            current.update(listOf("new" to "new"), setOf("new"))
            old.update(listOf("old" to "old", "new" to "new"), setOf("old", "new"))
            old.close()
            store.evictOldContentEventsForTest(mapOf(1 to 1))
            assertNull(store.getNostrEvent("old"))
            assertNotNull(store.getNostrEvent("new"))
        }
    }

    @Test
    fun `valid deletion remains authoritative for a protected row`() {
        val store = store()
        val id = "ab".repeat(32)
        store.insert(note(id))
        store.openDisplayWindow().use { window ->
            window.update(listOf(id to id), setOf(id))
            store.insert(note("delete").copy(kind = 5, createdAt = 2L, tags = listOf(listOf("e", id))))
            assertNull(store.getNostrEvent(id))
            assertFalse(store.insert(note(id)))
        }
    }

    @Test
    fun `late reactions to an article coordinate inherit window protection and release`() {
        val store = store()
        val article = note("article").copy(kind = 30023, tags = listOf(listOf("d", "post")))
        store.insert(article)
        val coord = "30023:${article.pubkey}:post"
        val reaction = note("reaction").copy(kind = 7, content = "+", tags = listOf(listOf("a", coord)))
        val window = store.openDisplayWindow()
        window.update(listOf(article.id to article.id), setOf(article.id))
        store.insert(reaction)
        store.evictOldContentEventsForTest(mapOf(7 to 0))
        assertNotNull(store.getNostrEvent(reaction.id))
        window.close()
        store.evictOldContentEventsForTest(mapOf(7 to 0))
        assertNull(store.getNostrEvent(reaction.id))
    }

    @Test
    fun `late kind 6 repost of visible note survives admission and sweep until release`() {
        assertTargetedRepostProtected(6, note("visible-note"), "visible-note", "e")
    }

    @Test
    fun `late kind 16 repost of visible article coordinate survives admission and sweep until release`() {
        val article = note("visible-article").copy(kind = 30023, tags = listOf(listOf("d", "post")))
        assertTargetedRepostProtected(16, article, "30023:${article.pubkey}:post", "a")
    }

    private fun assertTargetedRepostProtected(kind: Int, target: NostrEvent, targetKey: String, tag: String) {
        val store = store()
        assertTrue(store.insert(target))
        val window = store.openDisplayWindow()
        window.update(listOf(target.id to target.id), setOf(target.id))
        val repost = note("protected-repost").copy(kind = kind, tags = listOf(listOf(tag, targetKey)))
        assertTrue(store.insert(repost))
        val cap = CONTENT_EVENT_KIND_CAPS.getValue(kind)
        repeat(cap) { i ->
            assertTrue(store.insert(note("unrelated-repost-$i").copy(
                kind = kind, tags = listOf(listOf("e", "other-note")),
            )))
        }
        assertTrue("Must exercise admission pressure", store.snapshotEvictionMetrics().admissionReplaced > 0)
        assertEquals(cap, store.snapshotSize().eventsByKind[kind])
        assertNotNull("Targeted repost must survive admission", store.getNostrEvent(repost.id))
        store.evictOldContentEventsForTest(mapOf(kind to 0))
        assertNotNull("Targeted repost must survive sweep", store.getNostrEvent(repost.id))
        assertEquals(1, store.snapshotSize().eventsByKind[kind])
        window.close()
        store.evictOldContentEventsForTest(mapOf(kind to 0))
        assertNull("Same repost is evictable after release", store.getNostrEvent(repost.id))
    }

    private fun store() = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
    private fun note(id: String) = NostrEvent(
        id = id, pubkey = "unfollowed", kind = 1, content = "fixture", createdAt = 1L,
        tags = emptyList(), sig = "", relayUrl = "wss://relay.example", replyToId = null, rootId = null,
        hasContentWarning = false, contentWarningReason = null, firstSeenAt = 1L,
        relaysSeen = mutableSetOf("wss://relay.example"),
    )
}
