package com.unsilence.app.ui.feed

object FeedWindowFlag {
    // Architectural commitment point: new window-loader pipeline is the default.
    // Old path remains in tree, flag-gated, for safety until deletion.
    const val USE_WINDOW_LOADER = true
}
