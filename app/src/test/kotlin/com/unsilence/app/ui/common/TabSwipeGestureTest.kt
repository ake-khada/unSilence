package com.unsilence.app.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabSwipeGestureTest {
    @Test
    fun `mostly vertical drag with modest horizontal movement is not claimed`() {
        assertFalse(shouldClaimHorizontalSwipe(dx = 12f, dy = 30f, touchSlop = 10f))
    }

    @Test
    fun `clearly horizontal drag is claimed`() {
        assertTrue(shouldClaimHorizontalSwipe(dx = 30f, dy = 10f, touchSlop = 10f))
    }

    @Test
    fun `diagonal drag exactly at dominance boundary is not claimed`() {
        assertFalse(shouldClaimHorizontalSwipe(dx = 30f, dy = 20f, touchSlop = 10f))
    }

    @Test
    fun `movement below touch slop is not claimed`() {
        assertFalse(shouldClaimHorizontalSwipe(dx = 9f, dy = 2f, touchSlop = 10f))
    }
}
