package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.RelayTrustScoreEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ── resolveFollowing ─────────────────────────────────────────────────

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
    fun `resolveFollowing produces per-relay SubRequests`() {
        metadata.setWriteRelays("a".repeat(64), listOf("wss://shared.example", "wss://r1.example"))
        metadata.setWriteRelays("b".repeat(64), listOf("wss://shared.example", "wss://r2.example"))
        metadata.setWriteRelays("c".repeat(64), listOf("wss://shared.example"))

        val result = resolver.resolveFollowing(
            authors = setOf("a".repeat(64), "b".repeat(64), "c".repeat(64)),
            fallbackRelays = listOf("wss://global.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        // 1 fallback + 3 write relays = 4 SubRequests
        assertEquals(4, result.size)

        // Each SubRequest has exactly 1 URL
        assertTrue(result.all { it.urls.size == 1 })

        // Fallback relay has all 3 authors
        val fallback = result.first { it.urls[0] == "wss://global.example" }
        assertEquals(3, fallback.filter.authors?.size)

        // shared.example has all 3 authors
        val shared = result.first { it.urls[0] == "wss://shared.example" }
        assertEquals(3, shared.filter.authors?.size)

        // r1 has only "a"
        val r1 = result.first { it.urls[0] == "wss://r1.example" }
        assertEquals(1, r1.filter.authors?.size)
        assertTrue(r1.filter.authors!!.contains("a".repeat(64)))

        // r2 has only "b"
        val r2 = result.first { it.urls[0] == "wss://r2.example" }
        assertEquals(1, r2.filter.authors?.size)
        assertTrue(r2.filter.authors!!.contains("b".repeat(64)))
    }

    @Test
    fun `resolveFollowing takes top 4 write relays per author`() {
        val relays = (1..8).map { "wss://r$it.example" }
        metadata.setWriteRelays("a".repeat(64), relays)

        val result = resolver.resolveFollowing(
            authors = setOf("a".repeat(64)),
            fallbackRelays = listOf("wss://global.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        // 1 fallback + 4 write = 5 SubRequests
        assertEquals(5, result.size)
        assertTrue(result.all { it.urls.size == 1 })
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

        val allUrls = result.flatMap { it.urls }.toSet()
        assertTrue(allUrls.contains("wss://ok.example"))
        assertTrue(allUrls.contains("wss://global.example"))
        assertFalse(allUrls.contains("wss://blocked.example"))
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

        val allUrls = result.flatMap { it.urls }.toSet()
        assertTrue(allUrls.contains("wss://ok.example"))
        assertFalse(allUrls.contains("wss://lowtrust.example"))
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

        val allUrls = result.flatMap { it.urls }.toSet()
        assertTrue(allUrls.contains("wss://unknown.example"))
    }

    @Test
    fun `resolveFollowing falls back when no authors have write relays`() {
        val result = resolver.resolveFollowing(
            authors = setOf("a".repeat(64), "b".repeat(64)),
            fallbackRelays = listOf("wss://fallback.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        // Only 1 fallback SubRequest, no write relays
        assertEquals(1, result.size)
        assertEquals(listOf("wss://fallback.example"), result[0].urls)
        assertEquals(2, result[0].filter.authors?.size)
    }

    @Test
    fun `resolveFollowing fallback has all authors and write has subset`() {
        metadata.setWriteRelays("a".repeat(64), listOf("wss://known.example"))

        val result = resolver.resolveFollowing(
            authors = setOf("a".repeat(64), "b".repeat(64)),
            fallbackRelays = listOf("wss://fallback.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        // 1 fallback + 1 write = 2 SubRequests
        assertEquals(2, result.size)

        // Fallback has ALL authors
        val fallback = result.first { it.urls[0] == "wss://fallback.example" }
        assertEquals(2, fallback.filter.authors?.size)

        // Write relay has only its covered author
        val write = result.first { it.urls[0] == "wss://known.example" }
        assertEquals(1, write.filter.authors?.size)
        assertTrue(write.filter.authors!!.contains("a".repeat(64)))
    }

    @Test
    fun `resolveFollowing deduplicates write relay that matches fallback`() {
        metadata.setWriteRelays("a".repeat(64), listOf("wss://global.example", "wss://other.example"))

        val result = resolver.resolveFollowing(
            authors = setOf("a".repeat(64)),
            fallbackRelays = listOf("wss://global.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        // Fallback already covers global.example — only 1 fallback + 1 other = 2
        assertEquals(2, result.size)
        val urls = result.map { it.urls[0] }.toSet()
        assertEquals(setOf("wss://global.example", "wss://other.example"), urls)
    }

    @Test
    fun `resolveFollowing prunes write relays beyond 10 by coverage`() {
        // 15 unique relays. r0 covers 5 authors, r1-r14 each cover 1 unique author.
        for (i in 0 until 5) {
            metadata.setWriteRelays(hexPubkey(i), listOf("wss://r0.example", "wss://r${i + 1}.example"))
        }
        for (i in 5 until 15) {
            metadata.setWriteRelays(hexPubkey(i), listOf("wss://r${i + 1}.example"))
        }
        val authors = (0 until 15).map { hexPubkey(it) }.toSet()

        val result = resolver.resolveFollowing(
            authors = authors,
            fallbackRelays = listOf("wss://fallback.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        // 1 fallback + max 10 write = 11 SubRequests
        val fallbackCount = result.count { it.urls[0] == "wss://fallback.example" }
        val writeCount = result.size - fallbackCount
        assertEquals(1, fallbackCount)
        assertTrue("write relays should be capped at 10, got $writeCount", writeCount <= 10)

        // r0 should be selected (covers 5 authors — highest coverage)
        val writeUrls = result.filter { it.urls[0] != "wss://fallback.example" }.map { it.urls[0] }
        assertTrue("highest-coverage relay should be selected", writeUrls.contains("wss://r0.example"))
    }

    @Test
    fun `resolveFollowing authors are sorted in each SubRequest`() {
        metadata.setWriteRelays("c".repeat(64), listOf("wss://shared.example"))
        metadata.setWriteRelays("a".repeat(64), listOf("wss://shared.example"))
        metadata.setWriteRelays("b".repeat(64), listOf("wss://shared.example"))

        val result = resolver.resolveFollowing(
            authors = setOf("c".repeat(64), "a".repeat(64), "b".repeat(64)),
            fallbackRelays = listOf("wss://global.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        for (sr in result) {
            val authors = sr.filter.authors!!
            assertEquals("authors should be sorted in SubRequest for ${sr.urls}", authors.sorted(), authors)
        }
    }

    @Test
    fun `resolveFollowing no pruning when 10 or fewer write relays`() {
        for (i in 0 until 10) {
            metadata.setWriteRelays(hexPubkey(i), listOf("wss://r$i.example"))
        }
        val authors = (0 until 10).map { hexPubkey(it) }.toSet()

        val result = resolver.resolveFollowing(
            authors = authors,
            fallbackRelays = listOf("wss://fallback.example"),
            blockedRelays = emptySet(),
            config = defaultConfig,
        )

        // 1 fallback + 10 write = 11 (no pruning needed)
        assertEquals(11, result.size)
    }

    // ── resolveGlobal ────────────────────────────────────────────────────

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
