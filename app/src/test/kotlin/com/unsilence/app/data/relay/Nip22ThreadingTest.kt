package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class Nip22ThreadingTest {

    @Test
    fun `top-level article comment uses article event id when lowercase parent e is present`() {
        val (replyToId, rootId) = parseNip22Threading(
            listOf(
                listOf("A", "30023:author:slug"),
                listOf("K", "30023"),
                listOf("a", "30023:author:slug"),
                listOf("e", "article-id"),
                listOf("k", "30023"),
            )
        )

        assertEquals("article-id", replyToId)
        assertEquals("article-id", rootId)
    }

    @Test
    fun `reply to kind-1111 comment uses lowercase parent e without inventing root id`() {
        val (replyToId, rootId) = parseNip22Threading(
            listOf(
                listOf("A", "30023:author:slug"),
                listOf("K", "30023"),
                listOf("e", "parent-comment-id"),
                listOf("k", "1111"),
                listOf("p", "parent-pubkey"),
            )
        )

        assertEquals("parent-comment-id", replyToId)
        assertEquals(null, rootId)
    }

    @Test
    fun `comment without lowercase parent e has no thread ids`() {
        val (replyToId, rootId) = parseNip22Threading(
            listOf(
                listOf("A", "30023:author:slug"),
                listOf("K", "30023"),
            )
        )

        assertEquals(null, replyToId)
        assertEquals(null, rootId)
    }
}
