package com.unsilence.app.ui.profile

import com.unsilence.app.data.repository.FollowPublishResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileFollowFeedbackTest {
    @Test
    fun `unresolved follows surface a specific retry message`() {
        assertEquals(
            "Follow list not loaded yet. Check your connection and try again.",
            profileFollowFeedback(FollowPublishResult.FollowsUnavailable, "target"),
        )
    }

    @Test
    fun `zero relay acknowledgement reports that the prior list was restored`() {
        assertEquals(
            "Follow update did not reach any relay. Your previous list was restored.",
            profileFollowFeedback(
                FollowPublishResult.NoRelayAccepted(rollbackRestored = true),
                "target",
            ),
        )
    }
}
