package com.unsilence.app.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

internal fun shouldCancelCardLongPress(
    change: PointerInputChange?,
    downPosition: Offset,
    touchSlop: Float,
): Boolean = change == null || !change.pressed ||
    (change.position - downPosition).getDistance() > touchSlop

/**
 * Observe children without stealing their down event. A system CANCEL becomes a
 * consumed up in Compose: changedToUp() misses it, so inspect pressed directly.
 * Navigation/backgrounding cancels the entire detector, including its timeout;
 * resuming starts with a fresh down, never a held-over preview gesture.
 */
internal suspend fun PointerInputScope.detectCardLongPress(
    lifecycle: Lifecycle,
    onLongPress: () -> Unit,
) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@awaitEachGesture
            val cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (shouldCancelCardLongPress(change, down.position, viewConfiguration.touchSlop)) {
                        return@withTimeoutOrNull true
                    }
                }
                @Suppress("UNREACHABLE_CODE") true
            }
            if (cancelled == null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                onLongPress()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }
}
