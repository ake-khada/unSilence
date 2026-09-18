package com.unsilence.app.data.repository

import org.junit.Assert.*
import org.junit.Test

class NotificationBadgeTest {
    @Test fun `empty or fully muted rows never light the badge`() {
        assertFalse(hasUnreadNotifications(null, 0, 0))
    }
    @Test fun `new activity lights the badge until its current timestamp is seen`() {
        assertTrue(hasUnreadNotifications(11, 10, 0))
        assertFalse(hasUnreadNotifications(11, 10, 11))
        assertTrue(hasUnreadNotifications(12, 10, 11))
    }
    @Test fun `slow preference writes cannot resurrect already seen activity`() {
        assertFalse(hasUnreadNotifications(11, 0, 11))
        assertFalse(hasUnreadNotifications(11, 12, 0))
    }
}
