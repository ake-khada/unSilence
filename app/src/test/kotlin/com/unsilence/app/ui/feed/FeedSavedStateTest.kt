package com.unsilence.app.ui.feed

import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.domain.model.ShowType
import org.junit.Assert.*
import org.junit.Test

class FeedSavedStateTest {
    @Test fun `all feed sources survive activity recreation`() {
        val sources = listOf(
            FeedType.Following, FeedType.Global,
            FeedType.RelaySet("friends", "Friends"),
            FeedType.SingleRelay("wss://nos.lol/", "My relay"),
        )
        sources.forEach { source ->
            val saved = SavedFeedSession("owner", source.savedSource())
            assertEquals(source, decodeFeedSession(encodeFeedSession(saved), "owner")?.source?.restore())
        }
    }

    @Test fun `filters conversation tab reading intent and visible references survive together`() {
        val state = SavedFeedSession(
            owner = "owner", source = FeedType.Global.savedSource(),
            filter = FeedFilter(showTypes = setOf(ShowType.TEXT, ShowType.IMAGES), sinceHours = 24, minReactions = 10),
            contentFilter = FeedContentFilter.REPLIES_ONLY.value,
            automaticTrustedGlobal = true, isAtTop = false,
            eventIds = listOf("first", "reading", "last"),
        )
        assertEquals(state, decodeFeedSession(encodeFeedSession(state), "owner"))
    }

    @Test fun `state cannot leak into another account`() {
        val encoded = encodeFeedSession(SavedFeedSession("alice", FeedType.Global.savedSource()))
        assertNull(decodeFeedSession(encoded, "bob"))
    }

    @Test fun `missing corrupt and unknown source state falls back to normal startup`() {
        assertNull(decodeFeedSession(null, "owner"))
        assertNull(decodeFeedSession("not json", "owner"))
        assertNull(decodeFeedSession(encodeFeedSession(SavedFeedSession("owner", SavedFeedSource("removed"))), "owner"))
        assertNull(SavedFeedSource("relay", "not a relay").restore())
    }

    @Test fun `legacy popular relay restoration still migrates to Global`() {
        assertEquals(FeedType.Global, SavedFeedSource("relay", "wss://antiprimal.net/hot/", "Popular").restore())
    }

    @Test fun `saved references are bounded by the display cap`() {
        val state = SavedFeedSession("owner", FeedType.Global.savedSource(), eventIds = List(2000) { "$it" })
        val restored = decodeFeedSession(encodeFeedSession(state), "owner")!!
        assertEquals(SAVED_FEED_EVENT_LIMIT, restored.eventIds.size)
        assertEquals("0", restored.eventIds.first())
        assertEquals("499", restored.eventIds.last())
    }

    @Test fun `oversized state cannot fill the activity saved state bundle`() {
        val state = SavedFeedSession("owner", SavedFeedSource("relay-set", "a", "x".repeat(100_000)))
        assertNull(encodeFeedSession(state))
        assertNull(decodeFeedSession("x".repeat(100_000), "owner"))
    }
}
