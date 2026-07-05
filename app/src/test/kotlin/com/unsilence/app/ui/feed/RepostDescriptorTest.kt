package com.unsilence.app.ui.feed

import com.unsilence.app.data.memory.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit tests for [buildRepostDescriptor] — the pure NIP-18 kind/tag decision that
 * drives repost publishing. Asserting here (rather than against a signed event)
 * keeps the test crypto-free; signing/publishing is trivial plumbing over this.
 */
class RepostDescriptorTest {

    private fun Array<Array<String>>.tag(key: String): Array<String>? =
        firstOrNull { it.getOrNull(0) == key }

    private fun event(
        id: String,
        kind: Int,
        pubkey: String = "author-pk",
        tags: List<List<String>> = emptyList(),
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        kind = kind,
        content = "",
        createdAt = 1_000,
        tags = tags,
        tagsJson = "[]",
        sig = "sig",
        relayUrl = "wss://relay.example",
        replyToId = null,
        rootId = null,
        hasContentWarning = false,
        contentWarningReason = null,
        firstSeenAt = 1_000,
        relaysSeen = ConcurrentHashMap.newKeySet(),
    )

    @Test
    fun `reposting a kind-1 note yields kind-6 with k=1 and no a coordinate`() {
        val d = buildRepostDescriptor(
            targetId = "note-id", targetPubkey = "author-pk",
            targetKind = 1, targetDTag = null, relayHint = "wss://r",
        )
        assertEquals(6, d.kind)
        assertEquals(listOf("e", "note-id", "wss://r"), d.tags.tag("e")?.toList())
        assertEquals("author-pk", d.tags.tag("p")?.getOrNull(1))
        assertEquals("1", d.tags.tag("k")?.getOrNull(1))
        assertNull("note repost carries no a coordinate", d.tags.tag("a"))
    }

    @Test
    fun `reposting a kind-30023 article yields kind-16 with k=30023 plus e p a tags`() {
        val d = buildRepostDescriptor(
            targetId = "art-id", targetPubkey = "author-pk",
            targetKind = 30023, targetDTag = "my-article", relayHint = "wss://r",
        )
        assertEquals(16, d.kind)
        assertEquals("art-id", d.tags.tag("e")?.getOrNull(1))
        assertEquals("author-pk", d.tags.tag("p")?.getOrNull(1))
        assertEquals("30023", d.tags.tag("k")?.getOrNull(1))
        assertEquals("30023:author-pk:my-article", d.tags.tag("a")?.getOrNull(1))
    }

    @Test
    fun `reposting an addressable target with a blank d tag omits the a coordinate`() {
        val d = buildRepostDescriptor(
            targetId = "art-id", targetPubkey = "author-pk",
            targetKind = 30023, targetDTag = "", relayHint = "wss://r",
        )
        assertEquals(16, d.kind)
        assertEquals("30023", d.tags.tag("k")?.getOrNull(1))
        assertNull("blank d tag → no malformed a coordinate", d.tags.tag("a"))
    }

    @Test
    fun `reposting a non-addressable non-note (kind-20 picture) yields kind-16 without an a tag`() {
        val d = buildRepostDescriptor(
            targetId = "pic-id", targetPubkey = "author-pk",
            targetKind = 20, targetDTag = null, relayHint = "wss://r",
        )
        assertEquals(16, d.kind)
        assertEquals("20", d.tags.tag("k")?.getOrNull(1))
        assertNull(d.tags.tag("a"))
    }

    @Test
    fun `deletion request tags include e and k for ordinary events`() {
        val tags = buildDeletionRequestTags(listOf(event("note-id", 1)))

        assertEquals(listOf("e", "note-id"), tags.tag("e")?.toList())
        assertEquals(listOf("k", "1"), tags.tag("k")?.toList())
        assertNull(tags.tag("a"))
    }

    @Test
    fun `deletion request tags include addressable coordinate for replaceable events`() {
        val tags = buildDeletionRequestTags(
            listOf(event("article-id", 30023, tags = listOf(listOf("d", "slug")))),
        )

        assertEquals(listOf("e", "article-id"), tags.tag("e")?.toList())
        assertEquals(listOf("k", "30023"), tags.tag("k")?.toList())
        assertNotNull(tags.tag("a"))
        assertEquals("30023:author-pk:slug", tags.tag("a")?.getOrNull(1))
    }
}
