package com.unsilence.app.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullThresholdFeedbackTest {
    @Test fun firesOnceDespiteThresholdJitterAndRearmsAfterRelease() {
        val gate = PullThresholdFeedback()
        assertFalse(gate.update(0.9f, false))
        assertTrue(gate.update(1f, false))
        assertFalse(gate.update(1.2f, false))
        assertFalse(gate.update(0.9f, false))
        assertFalse(gate.update(1.1f, false))
        assertFalse(gate.update(0f, false))
        assertTrue(gate.update(1f, false))
    }

    @Test fun refreshAnimationAndInvalidProgressDoNotBuzz() {
        val gate = PullThresholdFeedback()
        assertFalse(gate.update(1f, true))
        assertFalse(gate.update(Float.NaN, false))
        assertFalse(gate.update(Float.POSITIVE_INFINITY, false))
    }
}
