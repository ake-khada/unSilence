package com.unsilence.app.ui.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedScrollStateTest {
    @Test fun `neighbors are bounded and ordered by old distance with successor first`() {
        assertEquals(listOf("21", "19", "22", "18", "23", "17"), feedAnchorNeighbors(500, 20) { "$it" })
        assertEquals(listOf("1", "2", "3"), feedAnchorNeighbors(500, 0) { "$it" })
        assertEquals(listOf("498", "497", "496"), feedAnchorNeighbors(500, 499) { "$it" })
        assertEquals(emptyList<String>(), feedAnchorNeighbors(0, 0) { error("empty") })
        assertEquals(emptyList<String>(), feedAnchorNeighbors(5, 5) { error("sentinel") })
    }
    @Test fun `exact anchor beats neighbors and neighbors beat index after arrivals`() {
        val ids = listOf("new", "a", "reading", "b", "c")
        assertEquals(2, pendingFeedRestoreIndex(ids, "reading", 1, true, listOf("b", "a")))
        assertEquals(3, pendingFeedRestoreIndex(ids, "deleted", 1, true, listOf("b", "a")))
        assertEquals(1, pendingFeedRestoreIndex(ids, "deleted", 4, true, listOf("gone", "a", "c")))
        assertEquals(3, restoredFeedIndex(ids, "deleted", 1, listOf("b", "a")))
    }
    @Test fun `unavailable neighborhood clamps only when a nonempty projection is ready`() {
        assertNull(pendingFeedRestoreIndex(emptyList(), "gone", 50, true, listOf("also-gone")))
        assertNull(pendingFeedRestoreIndex(listOf("a"), "gone", 50, false, listOf("a")))
        assertEquals(0, pendingFeedRestoreIndex(listOf("a"), "gone", 50, true, listOf("also-gone")))
    }
    @Test fun `legacy saves restore and new saves retain a bounded neighborhood`() {
        assertEquals(FeedScrollAnchor(12, 23, "reading"), FeedScrollAnchor.restore(listOf(12, 23, "reading")))
        val anchor = FeedScrollAnchor(12, 23, "reading", listOf("next", "previous"))
        assertEquals(anchor, FeedScrollAnchor.restore(anchor.save()))
        assertEquals(9, FeedScrollAnchor(12, 23, "reading", List(100) { "$it" }).save().size)
        assertEquals(6, FeedScrollAnchor.restore(listOf(12, 23, "reading") + List(100) { "$it" }).neighbors.size)
    }
    @Test fun `anchor waits for rows after activity recreation`() {
        assertNull(pendingFeedRestoreIndex(emptyList(), "reading", 12, restorationReady = false))
        assertNull(pendingFeedRestoreIndex(listOf("new"), "reading", 12, restorationReady = false))
        assertNull(pendingFeedRestoreIndex(listOf("new", "older", "reading"), "reading", 12, false))
        assertEquals(2, pendingFeedRestoreIndex(listOf("new", "older", "reading"), "reading", 12, true))
    }
    @Test fun `settled missing anchor uses fallback without repeatedly dragging the reader back`() {
        assertEquals(1, pendingFeedRestoreIndex(listOf("a", "b"), "deleted", 4, true))
        assertNull(pendingFeedRestoreIndex(emptyList(), "deleted", 4, true))
    }
    @Test fun `new posts do not move the saved event anchor`() {
        assertEquals(3, restoredFeedIndex(listOf("new-2", "new-1", "a", "b", "c"), "b", 1))
    }
    @Test fun `unchanged list restores same event`() {
        assertEquals(1, restoredFeedIndex(listOf("a", "b", "c"), "b", 1))
    }
    @Test fun `missing or not yet loaded anchor keeps saved index`() {
        assertEquals(4, restoredFeedIndex(emptyList(), "b", 4))
        assertEquals(1, restoredFeedIndex(listOf("a", "c"), "b", 1))
    }
    @Test fun `top and sentinel fallbacks are valid`() {
        assertEquals(0, restoredFeedIndex(emptyList(), "", -1))
        assertEquals(3, restoredFeedIndex(listOf("a", "b", "c"), "", 3))
    }
}
