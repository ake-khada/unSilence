package com.unsilence.app.ui.feed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPostsPillTest {
    @Test fun visibleOnlyForPendingPostsAwayFromTop() {
        assertFalse(showPendingPosts(0, false))
        assertFalse(showPendingPosts(-1, false))
        assertFalse(showPendingPosts(4, true))
        assertTrue(showPendingPosts(1, false))
    }
}
