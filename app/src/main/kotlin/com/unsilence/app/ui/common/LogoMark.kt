package com.unsilence.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.unsilence.app.ui.theme.Brand
import kotlinx.coroutines.delay

/**
 * unSilence waveform logo mark.
 *
 * Draws: muted "silence" line → 7 signal bars → blinking terminal cursor.
 * All coordinates from the spec (viewBox 0 0 200 200), scaled to [sizeDp].
 *
 * The terminal cursor switches on/off every 500ms, without a frame-clock animation.
 * [static] keeps it solid for reduced motion; [blinkActive] pauses offset-hidden
 * headers that remain composed. Only a RESUMED navigation entry may blink.
 */
@Composable
fun LogoMark(
    modifier: Modifier = Modifier,
    sizeDp: Dp = 88.dp,
    color: Color = Brand,
    firstBarColor: Color? = null,
    barHeightScale: () -> Float = { 1f },
    static: Boolean = false,
    blinkActive: Boolean = true,
) {
    val barColor = color
    val dimColor = color.copy(alpha = 0.5f)
    val cursorVisible = remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val blinkEnabled = !static && blinkActive
    LaunchedEffect(lifecycle, blinkEnabled) {
        runLogoCursorBlink(lifecycle, blinkEnabled) { cursorVisible.value = it }
    }

    Canvas(modifier = modifier.size(sizeDp)) {
        val s = size.width / 200f // scale factor from 200×200 viewBox

        // Muted "silence" line
        drawLine(
            color = dimColor,
            start = Offset(32f * s, 100f * s),
            end = Offset(68f * s, 100f * s),
            strokeWidth = 4f * s,
            cap = StrokeCap.Butt,
        )

        // Signal bars — heights are deliberate (24,56,40,88,64,36,20)
        val resolvedBarHeightScale = barHeightScale().coerceIn(1f, 1.6f)
        for (index in SIGNAL_BAR_HEIGHTS.indices) {
            val scaledHeight = SIGNAL_BAR_HEIGHTS[index] * resolvedBarHeightScale
            drawRect(
                color = if (index == 0) firstBarColor ?: barColor else barColor,
                topLeft = Offset((76f + 14f * index) * s, (100f - scaledHeight / 2f) * s),
                size = Size(8f * s, scaledHeight * s),
            )
        }

        // Terminal cursor (blinks)
        drawRect(
            // Read only during draw: a blink invalidates Canvas, not the header.
            color = barColor.copy(alpha = if (!blinkEnabled || cursorVisible.value) 1f else 0f),
            topLeft = Offset(172f * s, 94f * s),
            size = Size(4f * s, 12f * s),
        )
    }
}

private val SIGNAL_BAR_HEIGHTS = floatArrayOf(24f, 56f, 40f, 88f, 64f, 36f, 20f)

/** Delay schedules only the next edge; neither idle half-cycle requests vsync. */
internal suspend fun runLogoCursorBlink(
    lifecycle: Lifecycle,
    enabled: Boolean,
    onVisibleChanged: (Boolean) -> Unit,
) {
    onVisibleChanged(true)
    if (!enabled) return
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        var visible = true
        try {
            while (true) {
                delay(500)
                visible = !visible
                onVisibleChanged(visible)
            }
        } finally {
            // Pause/disposal leaves a solid cursor; resume waits a fresh 500ms.
            onVisibleChanged(true)
        }
    }
}
