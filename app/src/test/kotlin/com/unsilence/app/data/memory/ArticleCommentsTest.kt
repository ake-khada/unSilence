package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Article comments (NIP-22 kind-1111 via `A`, legacy kind-1 via `a`) indexed by
 * the article's a-coordinate: articleCommentsFlow + coord-aware replyCount.
 */
class ArticleCommentsTest {

    private lateinit var store: MemoryEventStore

    @Before
    fun setUp() {
        store = MemoryEventStore(object : MuteKeyProvider {}, com.unsilence.app.data.relay.stubTimelineServiceProvider())
    }

    private val author = "f".repeat(64)
    private val coord = "30023:$author:slug"

    private fun event(
        id: String, pubkey: String = "c".repeat(64), kind: Int,
        content: String = "nice", createdAt: Long = 1000,
        tags: List<List<String>> = emptyList(),
    ) = NostrEvent(
        id = id, pubkey = pubkey, kind = kind, content = content,
        createdAt = createdAt, tags = tags, tagsJson = "[]", sig = "sig",
        relayUrl = "wss://r.example", replyToId = null, rootId = null,
        hasContentWarning = false, contentWarningReason = null,
        firstSeenAt = System.currentTimeMillis(), relaysSeen = mutableSetOf("wss://r.example"),
    )

    private fun insertArticle() =
        store.insert(event(id = "article-1", pubkey = author, kind = 30023, content = "# body", tags = listOf(listOf("d", "slug"))))

    @Test
    fun `kind-1111 comment with A-tag is indexed and counted`() = runTest {
        insertArticle()
        store.insert(event(id = "c1", kind = 1111, tags = listOf(listOf("A", coord))))
        assertEquals(1, store.replyCount("article-1"))
        assertEquals(listOf("c1"), store.articleCommentsFlow(coord).first().map { it.id })
    }

    @Test
    fun `legacy kind-1 comment with a-tag is indexed and counted`() = runTest {
        insertArticle()
        store.insert(event(id = "c1", kind = 1, tags = listOf(listOf("a", coord))))
        assertEquals(1, store.replyCount("article-1"))
        assertEquals(listOf("c1"), store.articleCommentsFlow(coord).first().map { it.id })
    }

    @Test
    fun `non-matching event is excluded`() = runTest {
        insertArticle()
        store.insert(event(id = "other", kind = 1111, tags = listOf(listOf("A", "30023:$author:different"))))
        assertEquals(0, store.replyCount("article-1"))
        assertEquals(emptyList<String>(), store.articleCommentsFlow(coord).first().map { it.id })
    }

    @Test
    fun `comments are oldest-first with id tie-break`() = runTest {
        insertArticle()
        store.insert(event(id = "newer", kind = 1111, createdAt = 2000, tags = listOf(listOf("A", coord))))
        store.insert(event(id = "older", kind = 1111, createdAt = 1000, tags = listOf(listOf("A", coord))))
        assertEquals(listOf("older", "newer"), store.articleCommentsFlow(coord).first().map { it.id })
    }
}
