package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.RelayTrustScoreEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OutboxRelayResolverTest {

    private lateinit var metadata: FakeMetadata
    private lateinit var resolver: OutboxRelayResolver

    @Before
    fun setUp() {
        metadata = FakeMetadata()
        resolver = OutboxRelayResolver(metadata)
    }

    private val defaultConfig = OutboxRelayResolver.Config(
        kinds = listOf(1, 6, 20, 21, 30023),
        limit = 300,
    )

    private fun hexPubkey(i: Int): String = "%064x".format(i)

    @Test
    fun `resolveFollowing returns empty for empty authors`() {
        val result = resolver.resolveFollowing(
            authors = emptySet(),
            fallbackRelays = listOf("wss://global.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )
        assertEquals(0, result.size)
    }

    @Test
    fun `resolveFollowing returns single SubRequest with deduplicated relays`() {
        metadata.setWriteRelays("a".repeat(64), listOf("wss://shared.example", "wss://r1.example"))
        metadata.setWriteRelays("b".repeat(64), listOf("wss://shared.example", "wss://r2.example"))
        metadata.setWriteRelays("c".repeat(64), listOf("wss://shared.example"))

        val result = resolver.resolveFollowing(
            authors = setOf("a".repeat(64), "b".repeat(64), "c".repeat(64)),
            fallbackRelays = listOf("wss://global.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        assertEquals(1, result.size)
        // All 3 authors in the single SubRequest
        assertEquals(3, result[0].filter.authors?.size)
        // 4 unique relays: global (fallback) + shared, r1, r2
        assertEquals(4, result[0].urls.size)
        assertTrue(result[0].urls.contains("wss://global.example"))
        assertTrue(result[0].urls.contains("wss://shared.example"))
        assertTrue(result[0].urls.contains("wss://r1.example"))
        assertTrue(result[0].urls.contains("wss://r2.example"))
    }

    @Test
    fun `resolveFollowing includes all authors in single SubRequest`() {
        metadata.setWriteRelays("a".repeat(64), listOf("wss://r1.example", "wss://r2.example"))
        metadata.setWriteRelays("b".repeat(64), listOf("wss://r1.example", "wss://r2.example"))

        val result = resolver.resolveFollowing(
            authors = setOf("a".repeat(64), "b".repeat(64)),
            fallbackRelays = listOf("wss://global.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        assertEquals(1, result.size)
        assertEquals(2, result[0].filter.authors?.size)
        // global (fallback) + r1 + r2 = 3 URLs
        assertEquals(3, result[0].urls.size)
    }

    @Test
    fun `resolveFollowing takes top 5 write relays per author`() {
        val relays = (1..8).map { "wss://r$it.example" }
        metadata.setWriteRelays("a".repeat(64), relays)

        val result = resolver.resolveFollowing(
            authors = setOf("a".repeat(64)),
            fallbackRelays = listOf("wss://global.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        assertEquals(1, result.size)
        // 1 fallback + 5 write = 6 URLs
        assertEquals(6, result[0].urls.size)
        assertTrue(result[0].urls.contains("wss://global.example"))
    }

    @Test
    fun `resolveFollowing skips blocked relays`() {
        metadata.setWriteRelays("a".repeat(64), listOf("wss://blocked.example", "wss://ok.example"))

        val result = resolver.resolveFollowing(
            authors = setOf("a".repeat(64)),
            fallbackRelays = listOf("wss://global.example"),
            blockedRelays = setOf("wss://blocked.example"),
            config = defaultConfig,
        )

        assertEquals(1, result.size)
        assertTrue(result[0].urls.contains("wss://ok.example"))
        assertTrue(result[0].urls.contains("wss://global.example"))
        assertTrue(!result[0].urls.contains("wss://blocked.example"))
    }

    @Test
    fun `resolveFollowing skips low-trust relays`() {
        metadata.setWriteRelays("a".repeat(64), listOf("wss://lowtrust.example", "wss://ok.example"))
        metadata.setTrustScore("wss://lowtrust.example", 10)
        metadata.setTrustScore("wss://ok.example", 80)

        val result = resolver.resolveFollowing(
            authors = setOf("a".repeat(64)),
            fallbackRelays = listOf("wss://global.example"),
            blockedRelays = emptySet(),
            config = defaultConfig.copy(minTrustScore = 30),
        )

        assertEquals(1, result.size)
        assertTrue(result[0].urls.contains("wss://ok.example"))
        assertTrue(!result[0].urls.contains("wss://lowtrust.example"))
    }

    @Test
    fun `resolveFollowing keeps unknown-trust relays`() {
        metadata.setWriteRelays("a".repeat(64), listOf("wss://unknown.example"))

        val result = resolver.resolveFollowing(
            authors = setOf("a".repeat(64)),
            fallbackRelays = listOf("wss://global.example"),
            blockedRelays = emptySet(),
            config = defaultConfig.copy(minTrustScore = 30),
        )

        assertEquals(1, result.size)
        assertTrue(result[0].urls.contains("wss://unknown.example"))
    }

    @Test
    fun `resolveFollowing falls back when no authors have write relays`() {
        val result = resolver.resolveFollowing(
            authors = setOf("a".repeat(64), "b".repeat(64)),
            fallbackRelays = listOf("wss://fallback.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        assertEquals(1, result.size)
        assertEquals(listOf("wss://fallback.example"), result[0].urls)
        assertEquals(2, result[0].filter.authors?.size)
    }

    @Test
    fun `resolveFollowing always includes fallback relays`() {
        // Mix: one author with relays, one without
        metadata.setWriteRelays("a".repeat(64), listOf("wss://known.example"))

        val result = resolver.resolveFollowing(
            authors = setOf("a".repeat(64), "b".repeat(64)),
            fallbackRelays = listOf("wss://fallback.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        assertEquals(1, result.size)
        assertEquals(2, result[0].filter.authors?.size)
        // Both fallback and write relay included
        assertTrue(result[0].urls.contains("wss://fallback.example"))
        assertTrue(result[0].urls.contains("wss://known.example"))
        assertEquals(2, result[0].urls.size)
    }

    @Test
    fun `resolveFollowing many authors produce single SubRequest`() {
        for (i in 0 until 100) {
            metadata.setWriteRelays(hexPubkey(i), listOf("wss://r${i % 20}.example"))
        }
        val authors = (0 until 100).map { hexPubkey(it) }.toSet()

        val result = resolver.resolveFollowing(
            authors = authors,
            fallbackRelays = listOf("wss://fallback.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        assertEquals("always 1 SubRequest", 1, result.size)
        assertEquals(100, result[0].filter.authors?.size)
        // 20 write relays + 1 fallback = 21 URLs
        assertEquals(21, result[0].urls.size)
    }

    @Test
    fun `resolveFollowing caps write relays at MAX_WRITE_RELAYS`() {
        // 50 authors, each on a unique relay — 50 unique write relays
        for (i in 0 until 50) {
            metadata.setWriteRelays(hexPubkey(i), listOf("wss://r$i.example"))
        }
        val authors = (0 until 50).map { hexPubkey(it) }.toSet()

        val result = resolver.resolveFollowing(
            authors = authors,
            fallbackRelays = listOf("wss://fallback.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        assertEquals(1, result.size)
        // 1 fallback + 20 top-coverage write relays = 21
        assertEquals(21, result[0].urls.size)
        // Fallback relay is always included
        assertTrue(result[0].urls.contains("wss://fallback.example"))
    }

    @Test
    fun `resolveGlobal returns single SubRequest with read relays`() {
        val result = resolver.resolveGlobal(
            readRelays = listOf("wss://a.example", "wss://b.example"),
            fallbackRelays = listOf("wss://global.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        assertEquals(1, result.size)
        assertEquals(2, result[0].urls.size)
        assertEquals(null, result[0].filter.authors)
    }

    @Test
    fun `resolveGlobal falls back when no read relays configured`() {
        val result = resolver.resolveGlobal(
            readRelays = emptyList(),
            fallbackRelays = listOf("wss://global.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        assertEquals(1, result.size)
        assertEquals(listOf("wss://global.example"), result[0].urls)
    }

    @Test
    fun `resolveGlobal filters blocked relays`() {
        val result = resolver.resolveGlobal(
            readRelays = listOf("wss://blocked.example", "wss://ok.example"),
            fallbackRelays = listOf("wss://fallback.example"),
            blockedRelays = setOf("wss://blocked.example"),
            config = defaultConfig,
        )

        assertEquals(1, result.size)
        assertEquals(listOf("wss://ok.example"), result[0].urls)
    }

    @Test
    fun `resolveSingleRelay returns single SubRequest`() {
        val result = resolver.resolveSingleRelay(
            url = "wss://r.example",
            config = defaultConfig,
        )

        assertEquals(1, result.size)
        assertEquals(listOf("wss://r.example"), result[0].urls)
    }
}

class FakeMetadata : RelayMetadataSource {
    private val writeRelays = HashMap<String, List<String>>()
    private val trustScores = HashMap<String, RelayTrustScoreEntity>()

    fun setWriteRelays(pubkey: String, relays: List<String>) {
        writeRelays[pubkey] = relays
    }

    fun setTrustScore(url: String, score: Int) {
        trustScores[url] = RelayTrustScoreEntity(
            relayUrl = url,
            score = score,
            reliability = score,
            quality = score,
            accessibility = score,
            confidence = "test",
            observations = 100,
        )
    }

    override fun writeRelaysFor(pubkey: String): List<String> =
        writeRelays[pubkey] ?: emptyList()

    override fun getTrustScores(): Map<String, RelayTrustScoreEntity> =
        trustScores
}
