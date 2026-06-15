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
    fun `kind-1111 reply to comment increments parent count and remains flat under article`() = runTest {
        insertArticle()
        store.insert(
            event(
                id = "parent",
                kind = 1111,
                createdAt = 1000,
                tags = listOf(listOf("A", coord), listOf("k", "30023")),
            )
        )
        store.insert(
            event(
                id = "child",
                kind = 1111,
                createdAt = 1001,
                tags = listOf(listOf("A", coord), listOf("e", "parent"), listOf("k", "1111")),
            )
        )

        assertEquals(2, store.replyCount("article-1"))
        assertEquals(1, store.replyCount("parent"))
        assertEquals(0, store.replyCount("child"))
        assertEquals(listOf("parent", "child"), store.articleCommentsFlow(coord).first().map { it.id })
    }

    @Test
    fun `legacy kind-1 with only an a-tag (no article thread) is NOT indexed`() = runTest {
        insertArticle()
        // A quote/mention post: references the article via #a but isn't a reply to it.
        store.insert(event(id = "mention", kind = 1, tags = listOf(listOf("a", coord))))
        assertEquals(0, store.replyCount("article-1"))
        assertEquals(emptyList<String>(), store.articleCommentsFlow(coord).first().map { it.id })
    }

    @Test
    fun `legacy kind-1 reply threaded to the article id IS indexed`() = runTest {
        insertArticle()
        store.insert(
            NostrEvent(
                id = "legacy-comment", pubkey = "c".repeat(64), kind = 1, content = "good read",
                createdAt = 1000, tags = listOf(listOf("a", coord), listOf("e", "article-1")),
                tagsJson = "[]", sig = "sig", relayUrl = "wss://r.example",
                replyToId = "article-1", rootId = "article-1",
                hasContentWarning = false, contentWarningReason = null,
                firstSeenAt = System.currentTimeMillis(), relaysSeen = mutableSetOf("wss://r.example"),
            ),
        )
        assertEquals(1, store.replyCount("article-1"))
        assertEquals(listOf("legacy-comment"), store.articleCommentsFlow(coord).first().map { it.id })
    }

    @Test
    fun `legacy kind-1 with a-tag but unknown article is NOT indexed`() = runTest {
        // Article never inserted/registered → can't confirm the thread → skip.
        store.insert(
            NostrEvent(
                id = "orphan", pubkey = "c".repeat(64), kind = 1, content = "x",
                createdAt = 1000, tags = listOf(listOf("a", coord)),
                tagsJson = "[]", sig = "sig", relayUrl = "wss://r.example",
                replyToId = "something-else", rootId = "something-else",
                hasContentWarning = false, contentWarningReason = null,
                firstSeenAt = System.currentTimeMillis(), relaysSeen = mutableSetOf("wss://r.example"),
            ),
        )
        assertEquals(emptyList<String>(), store.articleCommentsFlow(coord).first().map { it.id })
    }

    @Test
    fun `non-matching event is excluded`() = runTest {
        insertArticle()
        store.insert(event(id = "other", kind = 1111, tags = listOf(listOf("A", "30023:$author:different"))))
        assertEquals(0, store.replyCount("article-1"))
        assertEquals(emptyList<String>(), store.articleCommentsFlow(coord).first().map { it.id })
    }

    @Test
    fun `kind-1 quoting the article (q tag) is NOT indexed as a comment`() = runTest {
        insertArticle()
        // A reply elsewhere that quotes the article: has `a` to the coord but also a
        // `q` quote tag → must not be attributed as a comment on the article.
        store.insert(event(id = "quoter", kind = 1, tags = listOf(listOf("a", coord), listOf("q", "article-1"))))
        assertEquals(0, store.replyCount("article-1"))
        assertEquals(emptyList<String>(), store.articleCommentsFlow(coord).first().map { it.id })
    }

    @Test
    fun `kind-1 replying to a different event but referencing the article is NOT indexed`() = runTest {
        insertArticle()
        store.insert(event(id = "somenote", kind = 1))
        // Reply to somenote that mentions the article via `a`: replyToId != articleId.
        store.insert(
            NostrEvent(
                id = "reply-elsewhere", pubkey = "c".repeat(64), kind = 1, content = "see this",
                createdAt = 1000, tags = listOf(listOf("a", coord)), tagsJson = "[]", sig = "sig",
                relayUrl = "wss://r.example", replyToId = "somenote", rootId = "somenote",
                hasContentWarning = false, contentWarningReason = null,
                firstSeenAt = System.currentTimeMillis(), relaysSeen = mutableSetOf("wss://r.example"),
            ),
        )
        assertEquals(emptyList<String>(), store.articleCommentsFlow(coord).first().map { it.id })
    }

    @Test
    fun `reply to a comment (no a-tag) is included as a descendant`() = runTest {
        insertArticle()
        // Direct legacy kind-1 comment threaded to the article.
        store.insert(
            NostrEvent(
                id = "comment", pubkey = "c".repeat(64), kind = 1, content = "top",
                createdAt = 1000, tags = listOf(listOf("a", coord), listOf("e", "article-1")),
                tagsJson = "[]", sig = "sig", relayUrl = "wss://r.example",
                replyToId = "article-1", rootId = "article-1",
                hasContentWarning = false, contentWarningReason = null,
                firstSeenAt = System.currentTimeMillis(), relaysSeen = mutableSetOf("wss://r.example"),
            ),
        )
        // Reply to that comment — NO #a article tag, only replyToId=comment.
        store.insert(
            NostrEvent(
                id = "child", pubkey = "d".repeat(64), kind = 1, content = "nested",
                createdAt = 1001, tags = listOf(listOf("e", "comment")),
                tagsJson = "[]", sig = "sig", relayUrl = "wss://r.example",
                replyToId = "comment", rootId = "comment",
                hasContentWarning = false, contentWarningReason = null,
                firstSeenAt = System.currentTimeMillis(), relaysSeen = mutableSetOf("wss://r.example"),
            ),
        )
        val ids = store.articleCommentsFlow(coord).first().map { it.id }
        assertEquals(listOf("comment", "child"), ids)
        // Count equals the visible rows (direct + descendant).
        assertEquals(2, store.replyCount("article-1"))
    }

    @Test
    fun `a quote reply to a comment is excluded from descendants`() = runTest {
        insertArticle()
        store.insert(
            NostrEvent(
                id = "comment", pubkey = "c".repeat(64), kind = 1, content = "top",
                createdAt = 1000, tags = listOf(listOf("a", coord), listOf("e", "article-1")),
                tagsJson = "[]", sig = "sig", relayUrl = "wss://r.example",
                replyToId = "article-1", rootId = "article-1",
                hasContentWarning = false, contentWarningReason = null,
                firstSeenAt = System.currentTimeMillis(), relaysSeen = mutableSetOf("wss://r.example"),
            ),
        )
        // A quote post replying to the comment → has a q tag → excluded.
        store.insert(
            NostrEvent(
                id = "quote-child", pubkey = "d".repeat(64), kind = 1, content = "quoting",
                createdAt = 1001, tags = listOf(listOf("e", "comment"), listOf("q", "something")),
                tagsJson = "[]", sig = "sig", relayUrl = "wss://r.example",
                replyToId = "comment", rootId = "comment",
                hasContentWarning = false, contentWarningReason = null,
                firstSeenAt = System.currentTimeMillis(), relaysSeen = mutableSetOf("wss://r.example"),
            ),
        )
        assertEquals(listOf("comment"), store.articleCommentsFlow(coord).first().map { it.id })
    }

    @Test
    fun `comments are oldest-first with id tie-break`() = runTest {
        insertArticle()
        store.insert(event(id = "newer", kind = 1111, createdAt = 2000, tags = listOf(listOf("A", coord))))
        store.insert(event(id = "older", kind = 1111, createdAt = 1000, tags = listOf(listOf("A", coord))))
        assertEquals(listOf("older", "newer"), store.articleCommentsFlow(coord).first().map { it.id })
    }
}
