package com.unsilence.app.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val AccentCyan = Color(0xFF22D3D3)
private val AccentDim = Color(0xFF14A3A3)

/**
 * unSilence waveform logo mark.
 *
 * Draws: muted "silence" line → 7 signal bars → blinking terminal cursor.
 * All coordinates from the spec (viewBox 0 0 200 200), scaled to [sizeDp].
 *
 * The terminal cursor blinks at 1 Hz (opacity 1→0→1). Pass [static] = true
 * to disable the blink (small sizes, reduced motion).
 */
@Composable
fun LogoMark(
    modifier: Modifier = Modifier,
    sizeDp: Dp = 88.dp,
    static: Boolean = false,
) {
    val cursorAlpha: Float = if (static) {
        1f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "cursorBlink")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1000
                    1f at 0
                    1f at 400
                    0f at 500
                    0f at 900
                    1f at 1000
                },
                repeatMode = RepeatMode.Restart,
            ),
            label = "cursorAlpha",
        )
        alpha
    }

    Canvas(modifier = modifier.size(sizeDp)) {
        val s = size.width / 200f // scale factor from 200×200 viewBox

        // Muted "silence" line
        drawLine(
            color = AccentDim,
            start = Offset(32f * s, 100f * s),
            end = Offset(68f * s, 100f * s),
            strokeWidth = 4f * s,
            cap = StrokeCap.Butt,
        )

        // Signal bars — heights are deliberate (24,56,40,88,64,36,20)
        val bars = arrayOf(
            floatArrayOf(76f, 88f, 8f, 24f),
            floatArrayOf(90f, 72f, 8f, 56f),
            floatArrayOf(104f, 80f, 8f, 40f),
            floatArrayOf(118f, 56f, 8f, 88f),
            floatArrayOf(132f, 68f, 8f, 64f),
            floatArrayOf(146f, 82f, 8f, 36f),
            floatArrayOf(160f, 90f, 8f, 20f),
        )
        for (bar in bars) {
            drawRect(
                color = AccentCyan,
                topLeft = Offset(bar[0] * s, bar[1] * s),
                size = Size(bar[2] * s, bar[3] * s),
            )
        }

        // Terminal cursor (blinks)
        drawRect(
            color = AccentCyan.copy(alpha = cursorAlpha),
            topLeft = Offset(172f * s, 94f * s),
            size = Size(4f * s, 12f * s),
        )
    }
}
