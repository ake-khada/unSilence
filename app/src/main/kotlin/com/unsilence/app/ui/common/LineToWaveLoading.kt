package com.unsilence.app.ui.common

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unsilence.app.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

private const val TWO_PI = 6.2831855f
private const val WAVELENGTHS = 1.5f
private const val WAVE_STEPS = 64
private const val AMPLITUDE_RATIO = 0.38f

/**
 * Unit-amplitude y displacement at normalized position [t] in 0..1.
 * The window pins both endpoints to the baseline while the carrier travels.
 */
internal fun waveDisplacement(t: Float, phase: Float, wavelengths: Float = WAVELENGTHS): Float =
    sin(PI.toFloat() * t) * sin(TWO_PI * wavelengths * t - phase)

/**
 * Startup loading indicator: a flat horizontal line whose amplitude grows
 * into a travelling sine wave, then loops until the caller removes it.
 *
 * Both fade-in and line-to-wave envelope start after [appearDelayMs], so the
 * flat-line moment is visible instead of being consumed during the anti-flash
 * delay. Per-frame values are read in the draw lambda only.
 */
@Composable
fun LineToWaveLoading(
    modifier: Modifier = Modifier,
    color: Color = Brand,
    height: Dp = 48.dp,
    strokeWidth: Dp = 2.dp,
    static: Boolean = false,
    appearDelayMs: Long = 150L,
) {
    val context = LocalContext.current
    val animationsDisabled = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
    val frozen = static || animationsDisabled

    val appearAlpha = remember { Animatable(if (appearDelayMs <= 0L) 1f else 0f) }
    val envelope = remember { Animatable(if (frozen) 1f else 0f) }
    LaunchedEffect(frozen, appearDelayMs) {
        if (appearAlpha.value < 1f) delay(appearDelayMs)
        if (frozen) {
            appearAlpha.snapTo(1f)
            envelope.snapTo(1f)
        } else {
            launch { appearAlpha.animateTo(1f, tween(durationMillis = 250)) }
            envelope.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            )
        }
    }

    val phase: State<Float>
    val breath: State<Float>
    if (frozen) {
        phase = remember { mutableFloatStateOf(0f) }
        breath = remember { mutableFloatStateOf(1f) }
    } else {
        val transition = rememberInfiniteTransition(label = "lineWave")
        phase = transition.animateFloat(
            initialValue = 0f,
            targetValue = TWO_PI,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "wavePhase",
        )
        breath = transition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "waveBreath",
        )
    }

    val path = remember { Path() }
    Canvas(modifier = modifier.height(height)) {
        val alpha = appearAlpha.value
        if (alpha <= 0f) return@Canvas

        val amplitude = size.height * AMPLITUDE_RATIO * envelope.value * breath.value
        val currentPhase = phase.value
        val midY = size.height / 2f
        path.reset()
        for (i in 0..WAVE_STEPS) {
            val t = i / WAVE_STEPS.toFloat()
            val x = size.width * t
            val y = midY - amplitude * waveDisplacement(t, currentPhase)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            alpha = alpha,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
        )
    }
}
