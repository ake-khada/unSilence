package com.unsilence.app.ui.navigation

import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.domain.model.GlobalFeedLens
import com.unsilence.app.domain.model.ShowType
import com.unsilence.app.ui.feed.FeedType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedHeaderPolicyTest {
    @Test
    fun `global exposes trust while following and relay sources do not`() {
        val trusted = feedHeaderElements(FeedType.Global, GlobalFeedLens.TRUSTED, FeedFilter())
        val raw = feedHeaderElements(FeedType.Global, GlobalFeedLens.RAW, FeedFilter())
        val following = feedHeaderElements(FeedType.Following, GlobalFeedLens.RAW, FeedFilter())
        val relay = feedHeaderElements(
            FeedType.SingleRelay("wss://nos.lol", "nos.lol"),
            GlobalFeedLens.TRUSTED,
            FeedFilter(),
        )

        assertTrue(trusted.showTrustChip)
        assertEquals(GlobalFeedLens.TRUSTED, trusted.lens)
        assertEquals(GlobalFeedLens.RAW, raw.lens)
        assertFalse(following.showTrustChip)
        assertFalse(relay.showTrustChip)
        assertEquals("Following", following.sourceLabel)
        assertEquals("nos.lol", relay.sourceLabel)
    }

    @Test
    fun `format chip appears only for explicit show types`() {
        val all = feedHeaderElements(
            FeedType.Global,
            GlobalFeedLens.TRUSTED,
            FeedFilter(sinceHours = 24),
        )
        val images = feedHeaderElements(
            FeedType.Global,
            GlobalFeedLens.TRUSTED,
            FeedFilter(showTypes = setOf(ShowType.IMAGES)),
        )
        val mixed = feedHeaderElements(
            FeedType.Following,
            GlobalFeedLens.TRUSTED,
            FeedFilter(showTypes = setOf(ShowType.VIDEO, ShowType.TEXT)),
        )

        assertFalse(all.showFormatChip)
        assertNull(all.formatContentDescription)
        assertTrue(images.showFormatChip)
        assertEquals("Images filter", images.formatContentDescription)
        assertEquals(listOf(ShowType.TEXT, ShowType.VIDEO), mixed.activeShowTypes)
        assertEquals("Text, Video filters", mixed.formatContentDescription)
    }

    @Test
    fun `relay set source uses its display name`() {
        val elements = feedHeaderElements(
            FeedType.RelaySet("friends", "A very long relay set name"),
            GlobalFeedLens.TRUSTED,
            FeedFilter(showTypes = setOf(ShowType.ARTICLES)),
        )

        assertEquals("A very long relay set name", elements.sourceLabel)
        assertFalse(elements.showTrustChip)
        assertTrue(elements.showFormatChip)
        assertEquals("Articles filter", elements.formatContentDescription)
    }
}
