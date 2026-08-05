package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.tagsToJson
import org.junit.Assert.assertEquals
import org.junit.Test

class StructuredTagExtractionTest {

    @Test
    fun `nip18 repost extractors match serialized boundary behavior`() {
        val tags = listOf(
            listOf("e", "reposted-id", "wss://relay.example", "mention"),
            listOf("p", "reposted-author"),
            listOf("k", "30023"),
        )
        val serialized = tagsToJson(tags)

        assertEquals(extractRepostTargetId(serialized), extractRepostTargetId(tags))
        assertEquals(extractRepostTargetRelay(serialized), extractRepostTargetRelay(tags))
        assertEquals(extractPTagPubkeys(serialized), extractPTagPubkeys(tags))
    }

    @Test
    fun `nip10 thread extractor retains e tag order`() {
        val tags = listOf(
            listOf("e", "root-id", "wss://root.example", "root"),
            listOf("e", "reply-id", "wss://reply.example", "reply"),
            listOf("p", "root-author"),
        )

        assertEquals(listOf("root-id", "reply-id"), extractETagIds(tags))
    }

    @Test
    fun `multi p tag extraction retains source order and duplicates`() {
        val tags = listOf(
            listOf("p", "alice", "wss://one.example"),
            listOf("t", "nostr"),
            listOf("p", "bob"),
            listOf("p", "alice", "wss://two.example"),
        )
        val serialized = tagsToJson(tags)

        assertEquals(listOf("alice", "bob", "alice"), extractPTagPubkeys(tags))
        assertEquals(extractPTagPubkeys(serialized), extractPTagPubkeys(tags))
    }
}
