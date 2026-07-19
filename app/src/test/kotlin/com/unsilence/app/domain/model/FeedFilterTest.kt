package com.unsilence.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedFilterTest {
    @Test
    fun `all show type enables the feed kind set`() {
        assertEquals(
            listOf(1, 6, 16, 20, 21, 22, 34235, 34236, 1068, 30023),
            FeedFilter().enabledKinds,
        )
    }

    @Test
    fun `default kind set keeps regular and addressable NIP-71 videos`() {
        assertTrue(22 in FeedFilter().enabledKinds)
        assertTrue(34235 in FeedFilter().enabledKinds)
        assertTrue(34236 in FeedFilter().enabledKinds)
    }

    @Test
    fun `every specific show type pins both repost kinds`() {
        ShowType.values().filterNot { it == ShowType.ALL }.forEach { showType ->
            val kinds = FeedFilter(showTypes = setOf(showType)).enabledKinds
            assertTrue("$showType must query kind 6", 6 in kinds)
            assertTrue("$showType must query kind 16", 16 in kinds)
        }
    }

    @Test
    fun `specific show types enable only matching relay kinds`() {
        val filter = FeedFilter(showTypes = setOf(ShowType.TEXT, ShowType.IMAGES, ShowType.ARTICLES))

        assertEquals(listOf(1, 6, 16, 1068, 20, 30023), filter.enabledKinds)
        assertTrue(filter.needsMediaFilter)
        assertTrue(6 in filter.enabledKinds)
    }

    @Test
    fun `activity presets map to exactly one threshold field`() {
        assertEquals(DISCUSSED_MIN_REPLIES, FeedFilter().withActivityPreset(ActivityPreset.DISCUSSED).minReplies)
        assertEquals(POPULAR_MIN_REACTIONS, FeedFilter().withActivityPreset(ActivityPreset.POPULAR).minReactions)
        assertEquals(ZAPPED_MIN_ZAP_SATS, FeedFilter().withActivityPreset(ActivityPreset.ZAPPED).minZapSats)

        assertEquals(ActivityPreset.DISCUSSED, FeedFilter(minReplies = DISCUSSED_MIN_REPLIES).activityPreset())
        assertEquals(ActivityPreset.POPULAR, FeedFilter(minReactions = POPULAR_MIN_REACTIONS).activityPreset())
        assertEquals(ActivityPreset.ZAPPED, FeedFilter(minZapSats = ZAPPED_MIN_ZAP_SATS).activityPreset())
        assertEquals(ActivityPreset.ANY, FeedFilter(minReplies = 3, minReactions = 4).activityPreset())
    }

    @Test
    fun `summary label names active session filters`() {
        val filter = FeedFilter(
            showTypes = setOf(ShowType.IMAGES),
            sinceHours = 24,
        ).withActivityPreset(ActivityPreset.POPULAR)

        assertEquals("Images · 24h · Popular", filter.summaryLabel())
        assertEquals(null, FeedFilter().summaryLabel())
    }
}
