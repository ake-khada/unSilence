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
    fun `resolveFollowing groups authors by their write relays`() {
        metadata.setWriteRelays("a".repeat(64), listOf("wss://shared.example"))
        metadata.setWriteRelays("b".repeat(64), listOf("wss://shared.example"))
        metadata.setWriteRelays("c".repeat(64), listOf("wss://shared.example"))

        val result = resolver.resolveFollowing(
            authors = setOf("a".repeat(64), "b".repeat(64), "c".repeat(64)),
            fallbackRelays = listOf("wss://global.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        assertEquals(1, result.size)
        assertEquals(listOf("wss://shared.example"), result[0].urls)
        assertEquals(3, result[0].filter.authors?.size)
    }

    @Test
    fun `resolveFollowing assigns each author to exactly one group`() {
        metadata.setWriteRelays("a".repeat(64), listOf("wss://r1.example", "wss://r2.example"))
        metadata.setWriteRelays("b".repeat(64), listOf("wss://r1.example", "wss://r2.example"))

        val result = resolver.resolveFollowing(
            authors = setOf("a".repeat(64), "b".repeat(64)),
            fallbackRelays = listOf("wss://global.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        assertEquals(1, result.size)
        val totalAuthorAssignments = result.sumOf { it.filter.authors?.size ?: 0 }
        assertEquals(2, totalAuthorAssignments)
    }

    @Test
    fun `resolveFollowing greedy selects highest-coverage relay first`() {
        metadata.setWriteRelays("a".repeat(64), listOf("wss://big.example", "wss://small.example"))
        metadata.setWriteRelays("b".repeat(64), listOf("wss://big.example"))
        metadata.setWriteRelays("c".repeat(64), listOf("wss://big.example"))
        metadata.setWriteRelays("d".repeat(64), listOf("wss://small.example"))

        val result = resolver.resolveFollowing(
            authors = setOf("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64)),
            fallbackRelays = listOf("wss://global.example"),
            blockedRelays = emptySet(),
            config = defaultConfig.copy(coverageTarget = 1.0),
        )

        assertEquals(2, result.size)
        assertEquals(listOf("wss://big.example"), result[0].urls)
        assertEquals(3, result[0].filter.authors?.size)
        assertEquals(listOf("wss://small.example"), result[1].urls)
        assertEquals(1, result[1].filter.authors?.size)
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
        assertEquals(listOf("wss://ok.example"), result[0].urls)
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
        assertEquals(listOf("wss://ok.example"), result[0].urls)
    }

    @Test
    fun `resolveFollowing untrusted unknown relays are kept`() {
        metadata.setWriteRelays("a".repeat(64), listOf("wss://unknown.example"))

        val result = resolver.resolveFollowing(
            authors = setOf("a".repeat(64)),
            fallbackRelays = listOf("wss://global.example"),
            blockedRelays = emptySet(),
            config = defaultConfig.copy(minTrustScore = 30),
        )

        assertEquals(1, result.size)
        assertEquals(listOf("wss://unknown.example"), result[0].urls)
    }

    @Test
    fun `resolveFollowing falls back for authors with no known relays`() {
        metadata.setWriteRelays("a".repeat(64), listOf("wss://known.example"))

        val result = resolver.resolveFollowing(
            authors = setOf("a".repeat(64), "b".repeat(64)),
            fallbackRelays = listOf("wss://fallback.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        assertEquals(2, result.size)
        val knownGroup = result.first { it.urls == listOf("wss://known.example") }
        val fallbackGroup = result.first { it.urls == listOf("wss://fallback.example") }
        assertEquals(listOf("a".repeat(64)), knownGroup.filter.authors)
        assertEquals(listOf("b".repeat(64)), fallbackGroup.filter.authors)
    }

    @Test
    fun `resolveFollowing respects maxRelays cap`() {
        for (i in 0 until 10) {
            metadata.setWriteRelays(hexPubkey(i), listOf("wss://r$i.example"))
        }
        val authors = (0 until 10).map { hexPubkey(it) }.toSet()

        val result = resolver.resolveFollowing(
            authors = authors,
            fallbackRelays = listOf("wss://fallback.example"),
            blockedRelays = emptySet(),
            config = defaultConfig.copy(maxRelays = 3, coverageTarget = 1.0),
        )

        // 3 relay groups max + no fallback (all authors have relays)
        assertTrue("max 3 relay groups, got ${result.size}", result.size <= 3)
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
