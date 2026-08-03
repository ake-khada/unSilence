package com.unsilence.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomNavPolicyTest {
    @Test
    fun `reselecting feed or profile scrolls that tab to top`() {
        assertEquals(TabReselectAction.FEED_TOP, tabReselectAction(tappedTab = 0, selectedTab = 0))
        assertEquals(TabReselectAction.PROFILE_TOP, tabReselectAction(tappedTab = 3, selectedTab = 3))
    }

    @Test
    fun `switching tabs or reselecting non-scroll tabs has no scroll action`() {
        assertEquals(TabReselectAction.NONE, tabReselectAction(tappedTab = 3, selectedTab = 0))
        assertEquals(TabReselectAction.NONE, tabReselectAction(tappedTab = 1, selectedTab = 1))
        assertEquals(TabReselectAction.NONE, tabReselectAction(tappedTab = 2, selectedTab = 2))
    }
}
