package com.unsilence.app.ui.common

import org.junit.Assert.*
import org.junit.Test

class ScrollAwayStateTest {
    @Test fun `micro scrolls do not toggle and a reversed gesture starts a fresh threshold`() {
        val state = ScrollAwayState()
        repeat(50) { state.scroll(-1f) }
        assertTrue(state.visible)
        state.scroll(5f)
        state.scroll(-20f)
        assertTrue(state.visible)
        state.scroll(-41f)
        assertFalse(state.visible)
        repeat(30) { state.scroll(1f) }
        assertFalse(state.visible)
        state.scroll(1f)
        assertTrue(state.visible)
    }

    @Test fun `long scroll changes visibility once and show resets previous momentum`() {
        val state = ScrollAwayState()
        var changes = 0
        repeat(1000) {
            val before = state.visible
            state.scroll(-2f)
            if (before != state.visible) changes++
        }
        assertEquals(1, changes)
        state.show()
        state.scroll(-1f)
        assertTrue(state.visible)
    }
}
