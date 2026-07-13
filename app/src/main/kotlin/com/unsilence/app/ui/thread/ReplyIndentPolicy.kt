package com.unsilence.app.ui.thread

internal const val MAX_REPLY_DEPTH = 10

/** Keep every visible reply level on a regular 8dp rhythm. */
internal fun replyIndentDp(depth: Int): Int = depth.coerceIn(0, MAX_REPLY_DEPTH) * 8
