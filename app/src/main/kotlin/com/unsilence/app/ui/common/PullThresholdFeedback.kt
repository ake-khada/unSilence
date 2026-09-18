package com.unsilence.app.ui.common

/** One threshold pulse per pull, not one per bounce across the threshold. */
internal class PullThresholdFeedback {
    private var fired = false

    fun update(fraction: Float, refreshing: Boolean): Boolean {
        if (fraction <= 0f) fired = false
        if (refreshing || fired || fraction < 1f || !fraction.isFinite()) return false
        fired = true
        return true
    }
}
