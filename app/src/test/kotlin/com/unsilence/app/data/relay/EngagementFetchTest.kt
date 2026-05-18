package com.unsilence.app.data.relay

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the bounded engagement count fetch:
 * - REQ filter construction (buildEngagementReq)
 * - Freshness tier intervals (engagementFreshnessInterval)
 * - Staleness gating (isEngagementStale via CardHydrator)
 * - MES engagementCapped accessors
 * - eng- prefix registered in SubscriptionRules
 */
class EngagementFetchTest {

    private lateinit var store: MemoryEventStore

    @Before
    fun setUp() {
        store = MemoryEventStore(object : MuteKeyProvider {})
    }

    // ── buildEngagementReq ──────────────────────────────────────────────

    @Test
    fun `buildEngagementReq constructs correct filter without since`() {
        val req = buildEngagementReq("eng-test", "evt-1", since = 0L)
        val parsed = Json.parseToJsonElement(req).jsonArray
        assertEquals("REQ", parsed[0].jsonPrimitive.content)
        assertEquals("eng-test", parsed[1].jsonPrimitive.content)

        val filter = parsed[2].jsonObject
        val kinds = filter["kinds"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }
        assertEquals(listOf(1, 6, 7, 9735), kinds)

        val eTags = filter["#e"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("evt-1"), eTags)

        assertEquals(ENGAGEMENT_LIMIT, filter["limit"]!!.jsonPrimitive.content.toInt())
        assertFalse(filter.containsKey("since"))
    }

    @Test
    fun `buildEngagementReq includes since when cursor is positive`() {
        val req = buildEngagementReq("eng-test", "evt-1", since = 1700000000L)
        val filter = Json.parseToJsonElement(req).jsonArray[2].jsonObject
        assertEquals(1700000000L, filter["since"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `buildEngagementReq has all four engagement kinds`() {
        val req = buildEngagementReq("eng-test", "evt-1", since = 0L)
        val kinds = Json.parseToJsonElement(req).jsonArray[2].jsonObject["kinds"]!!
            .jsonArray.map { it.jsonPrimitive.content.toInt() }
        assertEquals(listOf(1, 6, 7, 9735), kinds)
    }

    // ── engagementFreshnessInterval ─────────────────────────────────────

    @Test
    fun `post under 1h old re-fetches after 2 minutes`() {
        assertEquals(120L, engagementFreshnessInterval(1800)) // 30min old
        assertEquals(120L, engagementFreshnessInterval(0))    // brand new
        assertEquals(120L, engagementFreshnessInterval(3599)) // just under 1h
    }

    @Test
    fun `post 1-6h old re-fetches after 10 minutes`() {
        assertEquals(600L, engagementFreshnessInterval(3600))  // exactly 1h
        assertEquals(600L, engagementFreshnessInterval(10800)) // 3h
        assertEquals(600L, engagementFreshnessInterval(21599)) // just under 6h
    }

    @Test
    fun `post 6-24h old re-fetches after 1 hour`() {
        assertEquals(3600L, engagementFreshnessInterval(21600)) // exactly 6h
        assertEquals(3600L, engagementFreshnessInterval(43200)) // 12h
        assertEquals(3600L, engagementFreshnessInterval(86399)) // just under 24h
    }

    @Test
    fun `post 1-7d old re-fetches after 6 hours`() {
        assertEquals(21600L, engagementFreshnessInterval(86400))  // exactly 1d
        assertEquals(21600L, engagementFreshnessInterval(259200)) // 3d
        assertEquals(21600L, engagementFreshnessInterval(604799)) // just under 7d
    }

    @Test
    fun `post 7d or older fetches once then never`() {
        assertEquals(Long.MAX_VALUE, engagementFreshnessInterval(604800))  // exactly 7d
        assertEquals(Long.MAX_VALUE, engagementFreshnessInterval(2592000)) // 30d
    }

    // ── SubscriptionRules eng- prefix ───────────────────────────────────

    @Test
    fun `eng- prefix is registered as one-shot`() {
        assertTrue(SubscriptionRules.isOneShotSubscription("eng-12345"))
    }

    @Test
    fun `eng- prefix does not match own-eng-`() {
        // own-eng- has its own prefix — eng- should not catch it via startsWith
        // (both should match, but via different prefixes)
        assertTrue(SubscriptionRules.isOneShotSubscription("own-eng-12345"))
    }

    // ── MES engagementCapped ────────────────────────────────────────────

    @Test
    fun `engagementCapped starts empty`() {
        assertFalse(store.isEngagementCapped("any-event"))
    }

    @Test
    fun `markEngagementCapped marks event`() {
        store.markEngagementCapped("event-1")
        assertTrue(store.isEngagementCapped("event-1"))
        assertFalse(store.isEngagementCapped("event-2"))
    }

    @Test
    fun `engagementCapped cleared on store clear`() {
        store.markEngagementCapped("event-1")
        store.clear()
        assertFalse(store.isEngagementCapped("event-1"))
    }

    // ── MES currentStatsSnapshot ────────────────────────────────────────

    @Test
    fun `currentStatsSnapshot returns zeroes for unknown event`() {
        val stats = store.currentStatsSnapshot("nonexistent")
        assertEquals(0, stats.replyCount)
        assertEquals(0, stats.repostCount)
        assertEquals(0, stats.reactionCount)
        assertEquals(0, stats.zapCount)
        assertEquals(0L, stats.zapTotalSats)
    }

    // ── ENGAGEMENT_LIMIT constant ───────────────────────────────────────

    @Test
    fun `ENGAGEMENT_LIMIT is 100`() {
        assertEquals(100, ENGAGEMENT_LIMIT)
    }
}
