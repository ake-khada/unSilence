package com.unsilence.app.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabSwipeGestureTest {
    @Test
    fun `mostly vertical drag with modest horizontal movement is not claimed`() {
        assertFalse(claims(dx = 12f, dy = 30f))
    }

    @Test
    fun `clearly horizontal drag is claimed`() {
        assertTrue(claims(dx = 30f, dy = 10f))
    }

    @Test
    fun `diagonal drag exactly at dominance boundary is not claimed`() {
        assertFalse(claims(dx = 30f, dy = 20f))
    }

    @Test
    fun `movement below touch slop is not claimed`() {
        assertFalse(claims(dx = 9f, dy = 2f))
    }

    @Test
    fun `carousel ratio claims a drag rejected by the stricter tab ratio`() {
        assertTrue(claims(dx = 12f, dy = 10f, touchSlop = 5f, dominance = 1f))
        assertFalse(claims(dx = 12f, dy = 10f, touchSlop = 5f, dominance = 1.5f))
    }

    @Test
    fun `carousel ratio rejects an exact 45 degree tie`() {
        assertFalse(claims(dx = 12f, dy = 12f, touchSlop = 5f, dominance = 1f))
    }

    private fun claims(
        dx: Float,
        dy: Float,
        touchSlop: Float = 10f,
        dominance: Float = 1.5f,
    ): Boolean =
        shouldClaimHorizontalSwipe(
            dx = dx,
            dy = dy,
            touchSlop = touchSlop,
            dominance = dominance,
        )
}
