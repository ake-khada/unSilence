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

    @Test
    fun `top-level kind-21 comment uses its lowercase event parent as thread root`() {
        val (replyToId, rootId) = parseNip22Threading(
            listOf(
                listOf("E", "video-id"),
                listOf("K", "21"),
                listOf("e", "video-id"),
                listOf("k", "21"),
            )
        )

        assertEquals("video-id", replyToId)
        assertEquals("video-id", rootId)
    }

    @Test
    fun `top-level addressable portrait video comment uses revision id as thread root`() {
        val (replyToId, rootId) = parseNip22Threading(
            listOf(
                listOf("A", "34236:video-author:clip", "wss://relay.divine.video"),
                listOf("K", "34236"),
                listOf("P", "video-author", "wss://relay.divine.video"),
                listOf("a", "34236:video-author:clip", "wss://relay.divine.video"),
                listOf("e", "video-revision-id", "wss://relay.divine.video"),
                listOf("k", "34236"),
                listOf("p", "video-author", "wss://relay.divine.video"),
            )
        )

        assertEquals("video-revision-id", replyToId)
        assertEquals("video-revision-id", rootId)
    }

    @Test
    fun `nested addressable video comment keeps direct comment parent from lowercase e`() {
        val (replyToId, rootId) = parseNip22Threading(
            listOf(
                listOf("A", "34236:video-author:clip", "wss://relay.divine.video"),
                listOf("E", "video-revision-id", "wss://relay.divine.video"),
                listOf("K", "34236"),
                listOf("e", "parent-comment-id", "wss://comments.example"),
                listOf("k", "1111"),
                listOf("p", "comment-author", "wss://comments.example"),
            )
        )

        assertEquals("parent-comment-id", replyToId)
        assertEquals(null, rootId)
    }
}
