package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.RelayTrustScoreEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OutboxRelayResolverEngagementTest {
    private lateinit var metadata: FakeMetadata
    private lateinit var resolver: OutboxRelayResolver

    @Before
    fun setUp() {
        metadata = FakeMetadata()
        resolver = OutboxRelayResolver(metadata)
    }

    private fun hex(seed: Int) = seed.toString().padStart(64, 'a')

    @Test
    fun `returns author write relays first then own read relays`() {
        val author = hex(1)
        metadata.setWriteRelays(author, listOf(
            "wss://author-a.example",
            "wss://author-b.example",
        ))
        val ownRead = listOf("wss://own-a.example", "wss://own-b.example")

        val result = resolver.resolveEngagementRelays(
            authorPubkey = author,
            ownReadRelays = ownRead,
        )

        assertEquals(4, result.size)
        // Author write relays come first
        assertEquals("wss://author-a.example", result[0])
        assertEquals("wss://author-b.example", result[1])
        assertTrue(result.containsAll(ownRead))
    }

    @Test
    fun `caps author write relays at 4`() {
        val author = hex(2)
        val many = (1..10).map { "wss://author-$it.example" }
        metadata.setWriteRelays(author, many)

        val result = resolver.resolveEngagementRelays(
            authorPubkey = author,
            ownReadRelays = emptyList(),
        )

        assertEquals(4, result.size)
        assertTrue(many.containsAll(result))
    }

    @Test
    fun `falls back to GLOBAL when both author write and own read are empty`() {
        val author = hex(3)

        val result = resolver.resolveEngagementRelays(
            authorPubkey = author,
            ownReadRelays = emptyList(),
        )

        assertEquals(GLOBAL_RELAY_URLS, result)
    }

    @Test
    fun `returns own read relays when author has no kind-10002`() {
        val author = hex(4)
        val ownRead = listOf("wss://own.example")

        val result = resolver.resolveEngagementRelays(
            authorPubkey = author,
            ownReadRelays = ownRead,
        )

        assertEquals(1, result.size)
        assertEquals("wss://own.example", result[0])
    }

    @Test
    fun `caps own read relays at 2`() {
        val author = hex(5)
        val ownRead = (1..10).map { "wss://own-$it.example" }

        val result = resolver.resolveEngagementRelays(
            authorPubkey = author,
            ownReadRelays = ownRead,
        )

        assertEquals(2, result.size)
    }

    @Test
    fun `dedupes overlap between author write and own read`() {
        val author = hex(6)
        val shared = "wss://shared.example"
        metadata.setWriteRelays(author, listOf(shared, "wss://author-only.example"))
        val ownRead = listOf(shared, "wss://own-only.example")

        val result = resolver.resolveEngagementRelays(
            authorPubkey = author,
            ownReadRelays = ownRead,
        )

        assertEquals(3, result.size)
        assertEquals(result.distinct().size, result.size)
    }

    @Test
    fun `excludes blocked relays`() {
        val author = hex(7)
        metadata.setWriteRelays(author, listOf(
            "wss://blocked.example",
            "wss://allowed.example",
        ))

        val result = resolver.resolveEngagementRelays(
            authorPubkey = author,
            ownReadRelays = listOf("wss://blocked-own.example"),
            blockedRelays = setOf("wss://blocked.example", "wss://blocked-own.example"),
        )

        assertEquals(1, result.size)
        assertEquals("wss://allowed.example", result[0])
    }

    @Test
    fun `orders author write relays by trust score`() {
        val author = hex(8)
        metadata.setWriteRelays(author, listOf(
            "wss://low-trust.example",
            "wss://high-trust.example",
            "wss://mid-trust.example",
        ))
        metadata.setTrustScore("wss://low-trust.example", 20)
        metadata.setTrustScore("wss://high-trust.example", 90)
        metadata.setTrustScore("wss://mid-trust.example", 50)

        val result = resolver.resolveEngagementRelays(
            authorPubkey = author,
            ownReadRelays = emptyList(),
        )

        assertEquals("wss://high-trust.example", result[0])
        assertEquals("wss://mid-trust.example", result[1])
        assertEquals("wss://low-trust.example", result[2])
    }

    @Test
    fun `falls back to GLOBAL when all relays are blocked`() {
        val author = hex(9)
        metadata.setWriteRelays(author, listOf("wss://only.example"))

        val result = resolver.resolveEngagementRelays(
            authorPubkey = author,
            ownReadRelays = listOf("wss://only-own.example"),
            blockedRelays = setOf("wss://only.example", "wss://only-own.example"),
        )

        assertEquals(GLOBAL_RELAY_URLS, result)
    }
}
