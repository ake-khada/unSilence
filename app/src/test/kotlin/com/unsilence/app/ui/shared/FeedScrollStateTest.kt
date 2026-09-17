package com.unsilence.app.ui.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedScrollStateTest {
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
