package com.unsilence.app.ui.compose

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** NIP-22 kind-1111 article-comment tag construction. */
class Nip22TagsTest {

    private fun List<Array<String>>.tag(name: String) = firstOrNull { it[0] == name }

    @Test
    fun `top-level article comment emits A K P root and a e k p parent`() {
        val t = ArticleCommentTarget(
            articleId = "aid", articleCoord = "30023:pk:slug",
            articlePubkey = "pk", articleRelayHint = "wss://r",
        )
        val tags = Nip22Tags.articleComment(t)
        // Root scope (uppercase) — the article
        assertArrayEquals(arrayOf("A", "30023:pk:slug", "wss://r"), tags.tag("A"))
        assertArrayEquals(arrayOf("K", "30023"), tags.tag("K"))
        assertArrayEquals(arrayOf("P", "pk", "wss://r"), tags.tag("P"))
        // Parent == root == article
        assertArrayEquals(arrayOf("a", "30023:pk:slug", "wss://r"), tags.tag("a"))
        assertArrayEquals(arrayOf("e", "aid", "wss://r", "pk"), tags.tag("e"))
        assertArrayEquals(arrayOf("k", "30023"), tags.tag("k"))
        assertArrayEquals(arrayOf("p", "pk", "wss://r"), tags.tag("p"))
    }

    @Test
    fun `reply to a 1111 comment keeps article root and parents the comment`() {
        val t = ArticleCommentTarget(
            articleId = "aid", articleCoord = "30023:pk:slug", articlePubkey = "pk",
            parentId = "cid", parentKind = 1111, parentPubkey = "cpk", parentRelayHint = "wss://p",
        )
        val tags = Nip22Tags.articleComment(t)
        // Root stays the article
        assertArrayEquals(arrayOf("A", "30023:pk:slug", ""), tags.tag("A"))
        assertArrayEquals(arrayOf("K", "30023"), tags.tag("K"))
        // Parent is the comment (e/k/p), NOT the addressable article (no lowercase a)
        assertNull(tags.tag("a"))
        assertArrayEquals(arrayOf("e", "cid", "wss://p", "cpk"), tags.tag("e"))
        assertArrayEquals(arrayOf("k", "1111"), tags.tag("k"))
        assertArrayEquals(arrayOf("p", "cpk", "wss://p"), tags.tag("p"))
    }

    @Test
    fun `top-level without a known article id omits the e tag`() {
        val t = ArticleCommentTarget(articleId = null, articleCoord = "30023:pk:slug", articlePubkey = "pk")
        val tags = Nip22Tags.articleComment(t)
        assertNull(tags.tag("e"))
        assertNotNull(tags.tag("a")) // addressable parent still present
        assertArrayEquals(arrayOf("A", "30023:pk:slug", ""), tags.tag("A"))
    }
}
