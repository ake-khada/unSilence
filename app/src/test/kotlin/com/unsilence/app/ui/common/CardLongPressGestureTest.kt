package com.unsilence.app.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardLongPressGestureTest {
    @Test fun `system cancel is a consumed up and must end the long press`() {
        val cancel = change(pressed = false, consumed = true)
        // This was the old detector's check: it misses Compose's synthetic CANCEL.
        assertFalse(cancel.changedToUp())
        assertTrue(cancels(cancel))
    }

    @Test fun `ordinary release ends the long press`() {
        assertTrue(cancels(change(pressed = false)))
    }

    @Test fun `missing original pointer cancels instead of transferring to another finger`() {
        assertTrue(cancels(null))
    }

    @Test fun `clickable child consuming a stationary press does not disable deliberate holds`() {
        assertFalse(cancels(change(pressed = true, consumed = true)))
    }

    @Test fun `movement beyond slop cancels even when consumed by a child`() {
        assertTrue(cancels(change(pressed = true, consumed = true, position = Offset(11f, 0f))))
    }

    @Test fun `stationary hold and movement within slop remain eligible`() {
        assertFalse(cancels(change(pressed = true)))
        assertFalse(cancels(change(pressed = true, position = Offset(6f, 8f))))
    }

    private fun cancels(change: PointerInputChange?) =
        shouldCancelCardLongPress(change, downPosition = Offset.Zero, touchSlop = 10f)

    private fun change(
        pressed: Boolean,
        consumed: Boolean = false,
        position: Offset = Offset.Zero,
    ) = PointerInputChange(
        id = PointerId(1),
        uptimeMillis = 1,
        position = position,
        pressed = pressed,
        previousUptimeMillis = 0,
        previousPosition = Offset.Zero,
        previousPressed = true,
        isInitiallyConsumed = consumed,
    )
}
