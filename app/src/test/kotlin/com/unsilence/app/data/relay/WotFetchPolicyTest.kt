package com.unsilence.app.data.relay

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.WotAssertionEntity
import com.unsilence.app.data.memory.WotLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
}
