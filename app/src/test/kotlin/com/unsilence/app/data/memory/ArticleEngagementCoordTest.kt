package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Engagement targeting a long-form article by its `a`-coordinate (30023:pk:d) —
 * reactions/zaps tag the coordinate, not the event id — must still count under
 * the article, and own coordinate reactions must light hasReacted. Plus zap
 * drawer dedup (optimistic row collapses once the receipt arrives).
 */
class ArticleEngagementCoordTest {

    private lateinit var store: MemoryEventStore

    @Before
    fun setUp() {
        store = MemoryEventStore(object : MuteKeyProvider {}, com.unsilence.app.data.relay.stubTimelineServiceProvider())
    }

    private val author = "f".repeat(64)
    private val sender = "a".repeat(64)
    private val coord = "30023:$author:slug"

    private fun event(
        id: String,
        pubkey: String = author,
        kind: Int,
        content: String = "",
        tags: List<List<String>> = emptyList(),
    ) = NostrEvent(
        id = id, pubkey = pubkey, kind = kind, content = content,
        createdAt = System.currentTimeMillis() / 1000, tags = tags, tagsJson = "[]",
        sig = "sig", relayUrl = "wss://r.example", replyToId = null, rootId = null,
        hasContentWarning = false, contentWarningReason = null,
        firstSeenAt = System.currentTimeMillis(), relaysSeen = mutableSetOf("wss://r.example"),
    )

    private fun insertArticle(id: String = "article-1") {
        store.insert(event(id = id, kind = 30023, content = "# body", tags = listOf(listOf("d", "slug"))))
    }

    @Test
    fun `kind-7 reaction with only a-tag counts under the article id`() {
        insertArticle()
        store.insert(event(id = "react-1", pubkey = sender, kind = 7, content = "+", tags = listOf(listOf("a", coord))))
        assertEquals(1, store.reactionCount("article-1"))
    }

    @Test
    fun `uppercase A-tag reaction also counts under the article id`() {
        insertArticle()
        store.insert(event(id = "react-2", pubkey = sender, kind = 7, content = "🔥", tags = listOf(listOf("A", coord))))
        assertEquals(1, store.reactionCount("article-1"))
    }

    @Test
    fun `own reaction by coordinate lights isOwnEngaged for the article`() {
        store.ownPubkey = sender
        insertArticle()
        store.insert(event(id = "react-3", pubkey = sender, kind = 7, content = "+", tags = listOf(listOf("a", coord))))
        assertTrue(store.isOwnEngaged("article-1"))
    }

    @Test
    fun `reaction with no e or a tag is ignored`() {
        insertArticle()
        store.insert(event(id = "react-4", pubkey = sender, kind = 7, content = "+", tags = emptyList()))
        assertEquals(0, store.reactionCount("article-1"))
    }

    @Test
    fun `zap receipt targeting the coordinate counts under the article id`() {
        insertArticle()
        store.insert(
            event(
                id = "zap-1", pubkey = sender, kind = 9735,
                tags = listOf(
                    listOf("a", coord),
                    listOf("amount", "21000"),  // millisats → 21 sats
                ),
            ),
        )
        assertEquals(1, store.zapStats("article-1").count)
        assertEquals(21L, store.zapStats("article-1").totalSats)
    }

    @Test
    fun `optimistic zap collapses when matching receipt arrives`() {
        insertArticle()
        // Optimistic row added on the article id (eventId == null).
        store.addOptimisticZapDetail("article-1", senderPubkey = sender, sats = 21, comment = "gm")
        // Receipt arrives on the coordinate with a matching sender/sats/comment.
        val desc = """{"pubkey":"$sender","content":"gm"}"""
        store.insert(
            event(
                id = "zap-2", pubkey = "lnurl".padEnd(64, '0'), kind = 9735,
                tags = listOf(
                    listOf("a", coord),
                    listOf("amount", "21000"),
                    listOf("description", desc),
                ),
            ),
        )
        val rows = store.zapDetailsForEvent("article-1")
        assertEquals(1, rows.size)
        // The surviving row is the receipt-backed one (has an event id).
        assertEquals("zap-2", rows.first().eventId)
    }

    @Test
    fun `non-article event has no coordinate and counts only by id`() {
        store.insert(event(id = "note-1", kind = 1, content = "hi"))
        assertEquals(null, store.articleCoordForEvent("note-1"))
        store.insert(event(id = "r", pubkey = sender, kind = 7, content = "+", tags = listOf(listOf("e", "note-1"))))
        assertEquals(1, store.reactionCount("note-1"))
    }
}
