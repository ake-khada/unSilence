package com.unsilence.app.ui.feed

import com.unsilence.app.data.relay.NetworkConditions
import com.unsilence.app.data.media.blocksAutomaticMedia

/** Offline has no metered cost: keep buffered playback through a network dropout. */
internal val NetworkConditions.blocksAutoplay: Boolean
    get() = blocksAutomaticMedia

/** Player-lifetime network decisions; retains no media or content. */
internal class VideoNetworkPolicy(
    private val stopUnrequested: () -> Unit,
    private val clearPreloads: () -> Unit,
) {
    private var metered = false
    private var explicitlyRequested = false

    fun networkChanged(isMetered: Boolean) {
        metered = isMetered
        enforce()
    }

    fun claimed(explicit: Boolean) {
        explicitlyRequested = explicit
    }

    fun released() {
        explicitlyRequested = false
        enforce()
    }

    private fun enforce() {
        if (!metered) return
        clearPreloads()
        if (!explicitlyRequested) stopUnrequested()
    }
}

internal fun allowVideoPlayback(isMetered: Boolean, explicitlyRequested: Boolean): Boolean =
    !isMetered || explicitlyRequested
