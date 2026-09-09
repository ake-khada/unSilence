package com.unsilence.app.ui.search

import com.unsilence.app.data.memory.WotAssertionEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.relay.TRENDING_CANDIDATE_LIMIT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendingWotCoverageTest {
    private val provider = "f".repeat(64)
    private val scoredWithFollowers = "a".repeat(64)
    private val scoredWithoutFollowers = "b".repeat(64)
    private val pending = "c".repeat(64)
    private val absent = "d".repeat(64)

    @Test
    fun `summary counts each WoT state and verified follower coverage`() {
        val states = mapOf(
            scoredWithFollowers to scored(scoredWithFollowers, verifiedFollowers = 0L),
            scoredWithoutFollowers to scored(scoredWithoutFollowers, verifiedFollowers = null),
            pending to WotLookup.Pending,
            absent to WotLookup.Absent,
        )

        val summary = trendingWotCoverage(
            authors = listOf(
                scoredWithFollowers,
                scoredWithoutFollowers,
                pending,
                absent,
                scoredWithFollowers,
            ),
            lookup = states::getValue,
        )

        assertEquals(
            TrendingWotCoverage(
                authors = 4,
                scored = 2,
                pending = 1,
                absent = 1,
                verifiedFollowers = 1,
            ),
            summary,
        )
        assertFalse(summary.settled)
        assertEquals(
            "TRENDING-WOT authors=4 scored=2 pending=1 absent=1 verifiedFollowers=1",
            summary.logMessage(),
        )
    }

    @Test
    fun `summary is settled only after pending authors resolve`() {
        val summary = trendingWotCoverage(
            authors = listOf(scoredWithFollowers, absent),
            lookup = { pubkey ->
                if (pubkey == scoredWithFollowers) {
                    scored(pubkey, verifiedFollowers = 42L)
                } else {
                    WotLookup.Absent
                }
            },
        )

        assertTrue(summary.settled)
        assertEquals(1, summary.verifiedFollowers)
    }

    @Test
    fun `summary measures the full candidate pool rather than the display limit`() {
        val authors = (0 until TRENDING_CANDIDATE_LIMIT).map { index ->
            index.toString(16).padStart(64, '0')
        }
        val scoredAuthors = authors.take(20).toSet()
        val verifiedAuthors = authors.take(17).toSet()
        val pendingAuthors = authors.drop(20).take(4).toSet()

        val summary = trendingWotCoverage(authors) { pubkey ->
            when (pubkey) {
                in scoredAuthors -> scored(
                    pubkey,
                    verifiedFollowers = 500L.takeIf { pubkey in verifiedAuthors },
                )
                in pendingAuthors -> WotLookup.Pending
                else -> WotLookup.Absent
            }
        }

        assertEquals(
            TrendingWotCoverage(
                authors = TRENDING_CANDIDATE_LIMIT,
                scored = 20,
                pending = 4,
                absent = 8,
                verifiedFollowers = 17,
            ),
            summary,
        )
        assertFalse(summary.settled)
    }

    private fun scored(subject: String, verifiedFollowers: Long?): WotLookup.Scored =
        WotLookup.Scored(
            WotAssertionEntity(
                subjectPubkey = subject,
                providerPubkey = provider,
                rank = 80,
                verifiedFollowers = verifiedFollowers,
            ),
        )
}
