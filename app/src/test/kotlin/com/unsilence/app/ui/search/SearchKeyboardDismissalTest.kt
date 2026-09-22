package com.unsilence.app.ui.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchKeyboardDismissalTest {
    @Test fun `hundreds of callbacks dismiss a visible keyboard only once`() {
        val dismissal = SearchKeyboardDismissal()
        dismissal.updateVisibility(true)
        assertTrue(dismissal.onScroll(userInput = true, verticalMovement = true))
        repeat(400) {
            dismissal.updateVisibility(true) // layout still reports visible during hide animation
            assertFalse(dismissal.onScroll(userInput = true, verticalMovement = true))
        }
    }

    @Test fun `hidden keyboard and fling continuation do not issue requests`() {
        val dismissal = SearchKeyboardDismissal()
        assertFalse(dismissal.onScroll(userInput = true, verticalMovement = true))
        dismissal.updateVisibility(true)
        assertFalse(dismissal.onScroll(userInput = false, verticalMovement = true))
        assertTrue(dismissal.onScroll(userInput = true, verticalMovement = true))
        dismissal.updateVisibility(false)
        repeat(400) { assertFalse(dismissal.onScroll(userInput = true, verticalMovement = true)) }
    }

    @Test fun `a newly shown keyboard can be dismissed again`() {
        val dismissal = SearchKeyboardDismissal()
        repeat(3) {
            dismissal.updateVisibility(true)
            assertTrue(dismissal.onScroll(userInput = true, verticalMovement = true))
            dismissal.updateVisibility(false)
        }
    }

    @Test fun `horizontal tab swipes and zero movement do not dismiss`() {
        val dismissal = SearchKeyboardDismissal()
        dismissal.updateVisibility(true)
        assertFalse(dismissal.onScroll(userInput = true, verticalMovement = false))
        assertTrue(dismissal.onScroll(userInput = true, verticalMovement = true))
    }
}
