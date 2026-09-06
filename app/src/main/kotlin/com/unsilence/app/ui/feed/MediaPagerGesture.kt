package com.unsilence.app.ui.feed

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.unsilence.app.ui.common.shouldClaimHorizontalSwipe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val MEDIA_PAGER_SWIPE_DOMINANCE = 1f
internal const val MEDIA_PAGER_SNAP_POSITIONAL_THRESHOLD = 0.4f

/**
 * Horizontal pager input that yields diagonal and vertical drags to the surrounding list.
 * The stock pager still owns scrolling and fling physics after this gate claims a gesture.
 */
internal fun Modifier.mediaPagerGestures(
    pagerState: PagerState,
    flingBehavior: TargetedFlingBehavior,
): Modifier = pointerInput(pagerState, flingBehavior) {
    val touchSlop = viewConfiguration.touchSlop
    val pointerScope = CoroutineScope(currentCoroutineContext())

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.isConsumed) return@awaitEachGesture

        val velocityTracker = VelocityTracker().apply {
            addPosition(down.uptimeMillis, down.position)
        }
        var drag = Offset.Zero
        var firstDelta = Offset.Zero

        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Main)
                .changes
                .firstOrNull { it.id == down.id }
                ?: return@awaitEachGesture
            if (change.isConsumed) return@awaitEachGesture

            velocityTracker.addPosition(change.uptimeMillis, change.position)
            val delta = change.positionChange()
            drag += delta

            if (!change.pressed) return@awaitEachGesture
            if (
                shouldClaimHorizontalSwipe(
                    dx = drag.x,
                    dy = drag.y,
                    touchSlop = touchSlop,
                    dominance = MEDIA_PAGER_SWIPE_DOMINANCE,
                )
            ) {
                firstDelta = delta
                change.consume()
                break
            }
            if (abs(drag.x) > touchSlop || abs(drag.y) > touchSlop) {
                return@awaitEachGesture
            }
        }

        val dragDeltas = Channel<Float>(capacity = Channel.UNLIMITED)
        val releaseVelocity = CompletableDeferred<Float?>()
        pointerScope.launch {
            pagerState.scroll(MutatePriority.UserInput) {
                for (delta in dragDeltas) scrollBy(delta)
                releaseVelocity.await()?.let { velocity ->
                    with(flingBehavior) { performFling(velocity) }
                }
            }
        }
        dragDeltas.trySend(-firstDelta.x)

        var released = false
        try {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Main)
                    .changes
                    .firstOrNull { it.id == down.id }
                    ?: break
                if (change.isConsumed) break

                velocityTracker.addPosition(change.uptimeMillis, change.position)
                val delta = change.positionChange()
                change.consume()
                dragDeltas.trySend(-delta.x)

                if (!change.pressed) {
                    releaseVelocity.complete(-velocityTracker.calculateVelocity().x)
                    released = true
                    break
                }
            }
        } finally {
            if (!released) releaseVelocity.complete(null)
            dragDeltas.close()
        }
    }
}
