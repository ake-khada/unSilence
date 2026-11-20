package com.unsilence.app.ui.thread

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal const val MAX_REPLY_DEPTH = 10

/** Keep every visible reply level on a regular 8dp rhythm. */
internal fun replyIndentDp(depth: Int): Int = depth.coerceIn(0, MAX_REPLY_DEPTH) * 8

/** Draw the ancestor guides across this row's full height before insetting its content. */
internal fun Modifier.threadReplyIndent(depth: Int): Modifier = drawBehind {
    for (level in 1..depth.coerceAtMost(MAX_REPLY_DEPTH)) {
        val x = replyIndentDp(level).dp.toPx()
        drawLine(Color.White.copy(alpha = 0.10f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
    }
}.padding(start = replyIndentDp(depth).dp)
