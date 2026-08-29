package com.unsilence.app.data.relay

import com.unsilence.app.data.DEFAULT_WOT_PROVIDER_PUBKEY
import com.unsilence.app.data.DEFAULT_WOT_RELAY
import com.unsilence.app.data.WOT_REGISTRY_LOOKUP_RELAYS
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.WotAssertionEntity
import com.unsilence.app.data.memory.WotLookup
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WotFetchPolicyTest {

    private fun pubkey(n: Int): String = n.toString(16).padStart(64, '0')

    @Test
    fun `wot subject chunks are bounded to 200 and normalized`() {
        val subjects = (0 until 401).map(::pubkey) + pubkey(0).uppercase() + "not-a-pubkey"

        val chunks = wotSubjectChunks(subjects)

        assertEquals(3, chunks.size)
        assertEquals(200, chunks[0].size)
        assertEquals(200, chunks[1].size)
        assertEquals(1, chunks[2].size)
        assertEquals(401, chunks.flatten().distinct().size)
        assertTrue(chunks.flatten().all { it == it.lowercase() && it.length == 64 })
    }

    @Test
    fun `wot subject chunks place priority subjects in the first chunk`() {
        val owner = pubkey(300)
        val subjects = (0 until 401).map(::pubkey)

        val chunks = wotSubjectChunks(subjects, prioritySubjects = listOf(owner.uppercase()))

        assertEquals(owner, chunks.first().first())
        assertEquals(WOT_ASSERTION_CHUNK_SIZE, chunks.first().size)
        assertEquals(3, chunks.size)
        assertEquals(401, chunks.flatten().distinct().size)
        assertTrue(chunks.all { it.size <= WOT_ASSERTION_CHUNK_SIZE })
    }

    @Test
    fun `wot batch stays sequential and attempts pages after a miss`() = runTest {
        val attempted = mutableListOf<Int>()

        val allSucceeded = runWotChunkBatch(listOf("one", "two", "three")) { _, page, _ ->
            attempted += page
            page != 2
        }

        assertFalse(allSucceeded)
        assertEquals(listOf(1, 2, 3), attempted)
    }

    @Test
    fun `wot assertion filter has no limit so historical duplicates cannot crowd out subjects`() {
        val provider = pubkey(9)
        val filter = wotAssertionFilter(
            providerPubkey = provider.uppercase(),
            subjects = listOf(pubkey(2), pubkey(1), pubkey(2), "bad"),
        )!!

        assertNull(filter["limit"])
        assertEquals(listOf("30382"), filter.valuesFor("kinds"))
        assertEquals(listOf(provider), filter.valuesFor("authors"))
        assertEquals(listOf(pubkey(1), pubkey(2)), filter.valuesFor("#d"))
    }

    @Test
    fun `failed wot chunk does not mark subjects queried`() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val subject = pubkey(1)

        markWotChunkIfEosed(listOf(subject), eosed = false) {
            store.markWotSubjectsQueried(it)
        }
        assertTrue(store.wotFor(subject) is WotLookup.Pending)

        markWotChunkIfEosed(listOf(subject), eosed = true) {
            store.markWotSubjectsQueried(it)
        }
        assertTrue(store.wotFor(subject) is WotLookup.Absent)
    }

    @Test
    fun `bootstrap gate never skips on cold start without wot data`() {
        val hash = wotTargetsHash(pubkey(9), listOf(pubkey(1), pubkey(2)))

        assertFalse(
            shouldSkipBootstrapWotFetch(
                hasWotData = false,
                ageMs = 1_000L,
                lastTargetsHash = hash,
                currentTargetsHash = hash,
            )
        )
        assertTrue(
            shouldSkipBootstrapWotFetch(
                hasWotData = true,
                ageMs = 1_000L,
                lastTargetsHash = hash,
                currentTargetsHash = hash,
            )
        )
        assertFalse(
            shouldSkipBootstrapWotFetch(
                hasWotData = true,
                ageMs = WOT_STALENESS_MS + 1,
                lastTargetsHash = hash,
                currentTargetsHash = hash,
            )
        )
        assertFalse(
            shouldSkipBootstrapWotFetch(
                hasWotData = true,
                ageMs = 1_000L,
                lastTargetsHash = hash,
                currentTargetsHash = wotTargetsHash(pubkey(9), listOf(pubkey(3))),
            )
        )
    }

    @Test
    fun `wot targets hash is stable for target order but changes by provider`() {
        val a = wotTargetsHash(pubkey(9), listOf(pubkey(2), pubkey(1)))
        val b = wotTargetsHash(pubkey(9), listOf(pubkey(1), pubkey(2)))
        val c = wotTargetsHash(pubkey(10), listOf(pubkey(1), pubkey(2)))

        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `wot registry lookup bypasses capability filtering only for registry relays`() {
        val targets = wotRegistryLookupTargets(
            readRelays = listOf("nos.lol", "wss://read.example.com", "not-a-relay"),
            registryRelays = WOT_REGISTRY_LOOKUP_RELAYS,
        )

        assertEquals(
            listOf(
                "wss://nos.lol",
                "wss://read.example.com",
                "wss://nip85.nosfabrica.com",
                "wss://nip85.brainstorm.world",
            ),
            targets.relayUrls,
        )
        assertEquals(
            setOf("wss://nip85.nosfabrica.com", "wss://nip85.brainstorm.world", "wss://nos.lol"),
            targets.capabilityBypassRelays,
        )
    }

    @Test
    fun `own 10040 prefs resolve to stored provider instead of default fallback`() {
        val personalProvider = pubkey(12)
        val own = wotProviderDescriptorFromPrefs(
            WotProviderPrefs(
                pubkey = personalProvider,
                relay = "nip85.nosfabrica.com",
                source = WotProviderSource.OWN_10040,
            )
        )

        assertEquals(personalProvider, own.providerPubkey)
        assertEquals("wss://nip85.nosfabrica.com", own.relayHint)
        assertNotEquals(DEFAULT_WOT_PROVIDER_PUBKEY, own.providerPubkey)

        val default = wotProviderDescriptorFromPrefs(
            WotProviderPrefs(
                pubkey = personalProvider,
                relay = "wss://custom.example.com",
                source = WotProviderSource.DEFAULT,
            )
        )
        assertEquals(DEFAULT_WOT_PROVIDER_PUBKEY, default.providerPubkey)
        assertEquals(DEFAULT_WOT_RELAY, default.relayHint)
    }

    @Test
    fun `coalescer candidate selection drops non pending and includes stale profile scores only when requested`() {
        val pending = pubkey(1)
        val absent = pubkey(2)
        val freshScored = pubkey(3)
        val staleScored = pubkey(4)
        val nowSeconds = 100_000L
        val lookup: (String) -> WotLookup = { subject ->
            when (subject) {
                pending -> WotLookup.Pending
                absent -> WotLookup.Absent
                freshScored -> WotLookup.Scored(
                    WotAssertionEntity(
                        subjectPubkey = freshScored,
                        providerPubkey = pubkey(9),
                        rank = 80,
                        updatedAt = nowSeconds - 60,
                    )
                )
                staleScored -> WotLookup.Scored(
                    WotAssertionEntity(
                        subjectPubkey = staleScored,
                        providerPubkey = pubkey(9),
                        rank = 80,
                        updatedAt = nowSeconds - WOT_STALENESS_SECONDS - 1,
                    )
                )
                else -> WotLookup.Pending
            }
        }

        assertEquals(
            listOf(pending),
            selectWotHydrationCandidates(
                pubkeys = listOf(pending, absent, freshScored, staleScored),
                lookup = lookup,
                nowSeconds = nowSeconds,
                refreshStaleScored = false,
            )
        )
        assertEquals(
            listOf(pending, staleScored),
            selectWotHydrationCandidates(
                pubkeys = listOf(pending, absent, freshScored, staleScored),
                lookup = lookup,
                nowSeconds = nowSeconds,
                refreshStaleScored = true,
            )
        )
    }

    private fun Map<String, kotlinx.serialization.json.JsonElement>.valuesFor(key: String): List<String> =
        (get(key) as JsonArray).map { it.jsonPrimitive.content }
}
