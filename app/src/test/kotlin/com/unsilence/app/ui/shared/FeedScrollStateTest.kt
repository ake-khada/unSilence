package com.unsilence.app.ui.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedScrollStateTest {
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
