package com.unsilence.app.ui.feed

import com.unsilence.app.domain.model.FeedFilter
import org.junit.Assert.*
import org.junit.Test

class FeedPresentationTest {
    private val saved = SavedFeedSession(
        owner = "alice", source = FeedType.Global.savedSource(),
        isAtTop = false, eventIds = listOf("first", "reading", "last"),
    )
    private val current = saved.copy(eventIds = emptyList())

    @Test fun `another save before projection retains pending references and reading intent`() {
        val loading = feedSessionForSave(current, saved, FeedPresentation())
        assertEquals(saved, loading)
        val recreated = decodeFeedSession(encodeFeedSession(loading), "alice")
        assertEquals(saved, feedSessionForSave(current, recreated, FeedPresentation()))
    }

    @Test fun `partial preference projection cannot overwrite pending references`() {
        val partial = FeedPresentation(selection = saved.selection, restorationReady = false)
        assertEquals(saved, feedSessionForSave(current, saved, partial))
    }

    @Test fun `fully moderated empty projection does not resurrect hidden rows`() {
        val settled = FeedPresentation(selection = saved.selection, restorationReady = true)
        assertEquals(current, feedSessionForSave(current, saved, settled))
    }

    @Test fun `user changing feeds while loading cannot retain the old feed references`() {
        val switched = current.copy(source = FeedType.Following.savedSource())
        assertEquals(switched, feedSessionForSave(switched, saved, FeedPresentation()))
        val oldProjection = FeedPresentation(selection = saved.selection, restorationReady = true)
        assertEquals(switched, feedSessionForSave(switched, null, oldProjection))
    }

    @Test fun `filters and account identity fence pending references`() {
        val filtered = current.copy(contentFilter = FeedContentFilter.REPLIES_ONLY.value)
        assertEquals(filtered, feedSessionForSave(filtered, saved, FeedPresentation()))
        val anotherAccount = current.copy(owner = "bob")
        assertEquals(anotherAccount, feedSessionForSave(anotherAccount, saved, FeedPresentation()))
    }

    @Test fun `cleared restoration does not preserve old references during a deliberate refresh`() {
        assertEquals(current, feedSessionForSave(current, null, FeedPresentation()))
    }

    @Test fun `timeline readiness belongs to the exact seeded source and filter`() {
        assertFalse(FeedTimeline().isSeededFor(FeedType.Global, FeedFilter()))
        val seeded = FeedTimeline(source = FeedType.Global, filter = FeedFilter())
        assertTrue(seeded.isSeededFor(FeedType.Global, FeedFilter()))
        assertFalse(seeded.isSeededFor(FeedType.Following, FeedFilter()))
        assertFalse(seeded.isSeededFor(FeedType.Global, FeedFilter(sinceHours = 24)))
        assertTrue(seeded.copy(events = emptyList()).isSeededFor(FeedType.Global, FeedFilter()))
    }
}
