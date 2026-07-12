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

    // ── Boosted/embedded: original kind-30023 NEVER inserted, coord registered
    //    from the rendered row only. ──────────────────────────────────────────

    @Test
    fun `registered coord resolves reactions when the article event is absent`() {
        // No insertArticle() — the article isn't in eventsById (boosted/embedded).
        store.registerArticleCoord("article-x", coord)
        store.insert(event(id = "react-x", pubkey = sender, kind = 7, content = "+", tags = listOf(listOf("a", coord))))
        assertEquals(1, store.reactionCount("article-x"))
    }

    @Test
    fun `registered coord resolves zaps and isOwnEngaged when the article event is absent`() {
        store.ownPubkey = sender
        store.registerArticleCoord("article-x", coord)
        store.insert(event(id = "react-x", pubkey = sender, kind = 7, content = "+", tags = listOf(listOf("a", coord))))
        assertTrue(store.isOwnEngaged("article-x"))
        store.insert(
            event(
                id = "zap-x", pubkey = sender, kind = 9735,
                tags = listOf(listOf("a", coord), listOf("amount", "21000")),
            ),
        )
        assertEquals(1, store.zapStats("article-x").count)
        assertEquals(21L, store.zapStats("article-x").totalSats)
    }

    @Test
    fun `zapStats uses deduped receipt details not a stale raw aggregate`() {
        insertArticle()
        val desc = """{"pubkey":"$sender","content":"gm"}"""
        // Two real receipt zaps (21 each) on the coordinate.
        store.insert(event(id = "z1", pubkey = "l".repeat(64), kind = 9735,
            tags = listOf(listOf("a", coord), listOf("amount", "21000"), listOf("description", desc))))
        store.insert(event(id = "z2", pubkey = "l".repeat(64), kind = 9735,
            tags = listOf(listOf("a", coord), listOf("amount", "21000"), listOf("description", desc))))
        // Inflate the raw aggregate so it disagrees with the actual receipts.
        store.incrementZapStats("article-1", 1000L)
        val zs = store.zapStats("article-1")
        // Must match the deduped receipt rows the drawer shows (2 × 21), not raw.
        assertEquals(2, zs.count)
        assertEquals(42L, zs.totalSats)
        assertEquals(2, store.zapDetailsForEvent("article-1").count { it.eventId != null })
    }

    @Test
    fun `non-article event has no coordinate and counts only by id`() {
        store.insert(event(id = "note-1", kind = 1, content = "hi"))
        assertEquals(null, store.articleCoordForEvent("note-1"))
        store.insert(event(id = "r", pubkey = sender, kind = 7, content = "+", tags = listOf(listOf("e", "note-1"))))
        assertEquals(1, store.reactionCount("note-1"))
    }

    @Test
    fun `addressable video registers coordinate and aggregates A-tag comments`() {
        val videoCoord = "34236:$author:divine-clip"
        store.insert(
            event(
                id = "video-1",
                kind = 34236,
                tags = listOf(
                    listOf("d", "divine-clip"),
                    listOf("imeta", "url https://media.divine.video/video", "m video/mp4"),
                ),
            ),
        )
        store.insert(
            event(
                id = "comment-1",
                pubkey = sender,
                kind = 1111,
                content = "nice clip",
                tags = listOf(listOf("A", videoCoord), listOf("K", "34236"), listOf("P", author)),
            ),
        )

        assertEquals(videoCoord, store.articleCoordForEvent("video-1"))
        assertEquals(1, store.replyCount("video-1"))
    }
}
