package com.unsilence.app.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private const val TAB_SWIPE_DOMINANCE = 1.5f

/** Claims only after horizontal slop and strict horizontal-over-vertical dominance. */
internal fun shouldClaimHorizontalSwipe(
    dx: Float,
    dy: Float,
    touchSlop: Float,
    dominance: Float,
): Boolean = abs(dx) > touchSlop && abs(dx) > abs(dy) * dominance

/** Whole-screen tab swipe that yields to vertical scrolls and consumed child gestures. */
internal fun Modifier.tabSwipe(
    key: Any?,
    enabled: Boolean = true,
    commitThresholdPx: Float? = null,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): Modifier {
    if (!enabled) return this

    return pointerInput(key, commitThresholdPx) {
        val commitThreshold = commitThresholdPx ?: 100.dp.toPx()
        val touchSlop = viewConfiguration.touchSlop

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var drag = Offset.Zero
            var claimed = false

            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) break

                drag += change.positionChange()

                if (!change.pressed) {
                    if (claimed && abs(drag.x) >= commitThreshold) {
                        if (drag.x > 0f) onPrevious() else onNext()
                    }
                    break
                }

                if (!claimed) {
                    if (
                        shouldClaimHorizontalSwipe(
                            dx = drag.x,
                            dy = drag.y,
                            touchSlop = touchSlop,
                            dominance = TAB_SWIPE_DOMINANCE,
                        )
                    ) {
                        claimed = true
                    } else if (abs(drag.x) > touchSlop || abs(drag.y) > touchSlop) {
                        break
                    }
                }

                if (claimed) change.consume()
            }
        }
    }
}
