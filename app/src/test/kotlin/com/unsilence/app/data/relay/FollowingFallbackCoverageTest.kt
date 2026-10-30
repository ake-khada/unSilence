package com.unsilence.app.data.relay

import org.junit.Assert.*
import org.junit.Test

class FollowingFallbackCoverageTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val fallback = "wss://fallback.example"
    private val write = "wss://write.example"
    private val config = OutboxRelayResolver.Config(kinds = listOf(1, 6), limit = 300, since = 123L)

    private fun resolver(relays: Map<String, List<String>>) = OutboxRelayResolver(object : RelayMetadataSource {
        override fun writeRelaysFor(pubkey: String) = relays[pubkey].orEmpty()
    })

    @Test fun `healthy selected write coverage removes only that author from fallback`() {
        val result = resolver(mapOf(a to listOf(write))).resolveFollowing(
            setOf(a, b), listOf(fallback), emptySet(), config, setOf(write))
        assertEquals(listOf(b), result.single { it.urls == listOf(fallback) }.filter.authors)
        assertEquals(listOf(a), result.single { it.urls == listOf(write) }.filter.authors)
        assertTrue(result.all { it.filter.kinds == config.kinds && it.filter.limit == 300 && it.filter.since == 123L })
    }

    @Test fun `all covered authors need no fallback request`() {
        val result = resolver(mapOf(a to listOf(write), b to listOf(write))).resolveFollowing(
            setOf(a, b), listOf(fallback), emptySet(), config, setOf(write))
        assertEquals(listOf(write), result.flatMap { it.urls })
        assertEquals(listOf(a, b), result.single().filter.authors)
    }

    @Test fun `unknown unhealthy and recovered connections are resolved from each snapshot`() {
        val resolver = resolver(mapOf(a to listOf(write)))
        fun resolve(healthy: Set<String>) = resolver.resolveFollowing(
            setOf(a, b), listOf(fallback), emptySet(), config, healthy)
        assertEquals(listOf(a, b), resolve(emptySet()).single { it.urls == listOf(fallback) }.filter.authors)
        assertEquals(listOf(b), resolve(setOf(write)).single { it.urls == listOf(fallback) }.filter.authors)
        assertEquals(listOf(a, b), resolve(emptySet()).single { it.urls == listOf(fallback) }.filter.authors)
    }

    @Test fun `fallback URL that is also a declared write retains its own authors`() {
        val result = resolver(mapOf(a to listOf(fallback, write), b to listOf(write))).resolveFollowing(
            setOf(a, b), listOf(fallback), emptySet(), config, setOf(fallback, write))
        assertEquals(listOf(a), result.single { it.urls == listOf(fallback) }.filter.authors)
        assertEquals(result.size, result.flatMap { it.urls }.distinct().size)
    }

    @Test fun `normalized health matches and invalid or blocked writes cannot cover authors`() {
        val result = resolver(mapOf(a to listOf("wss://WRITE.example/", write), b to listOf("broken", "wss://blocked.example")))
            .resolveFollowing(setOf(b, a), listOf(fallback, "$fallback/"), setOf("wss://blocked.example"), config, setOf("wss://WRITE.example/", "wss://blocked.example"))
        assertEquals(listOf(b), result.single { it.urls == listOf(fallback) }.filter.authors)
        // Shared relay identity strips the slash but deliberately preserves host case.
        assertEquals(3, result.size)
        assertEquals(setOf(fallback, "wss://WRITE.example", write), result.flatMap { it.urls }.toSet())
    }

    @Test fun `pruned write relays cannot falsely cover their authors`() {
        val authors = (0..10).map { it.toString(16).padStart(64, '0') }
        val relays = authors.mapIndexed { i, author -> author to listOf("wss://write-$i.example") }.toMap()
        val result = resolver(relays).resolveFollowing(authors.toSet(), listOf(fallback), emptySet(), config, relays.values.flatten().toSet())
        val writes = result.filter { it.urls != listOf(fallback) }
        assertEquals(10, writes.size)
        assertEquals(3, writes.count { it.tier == SubTier.FAST })
        val selectedAuthors = writes.flatMap { it.filter.authors.orEmpty() }.toSet()
        assertEquals(authors.filterNot { it in selectedAuthors }, result.single { it.urls == listOf(fallback) }.filter.authors)
    }
}
