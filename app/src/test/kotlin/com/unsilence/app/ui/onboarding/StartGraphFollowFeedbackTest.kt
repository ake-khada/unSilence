package com.unsilence.app.ui.onboarding

import com.unsilence.app.data.repository.FollowPublishResult
import org.junit.Assert.assertEquals
import org.junit.Test

class StartGraphFollowFeedbackTest {
    @Test
    fun `unresolved follows surface a specific onboarding retry message`() {
        assertEquals(
            "Your follow list has not loaded yet. Check your connection and try again.",
            startGraphFollowError(FollowPublishResult.FollowsUnavailable),
        )
    }

    @Test
    fun `zero relay acknowledgement surfaces publish failure`() {
        assertEquals(
            "No relay accepted the follow list. Check your connection and try again.",
            startGraphFollowError(
                FollowPublishResult.NoRelayAccepted(rollbackRestored = true),
            ),
        )
    }
}
