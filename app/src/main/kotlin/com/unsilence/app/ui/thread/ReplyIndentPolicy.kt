package com.unsilence.app.ui.thread

internal const val MAX_REPLY_DEPTH = 8

/** Cumulative reply indentation using the app's 12 -> 8 -> 5dp spacing descent. */
internal fun replyIndentDp(depth: Int): Int = when {
    depth <= 0 -> 0
    depth <= 6 -> depth * 12
    depth == 7 -> 80
    else -> 85
}
