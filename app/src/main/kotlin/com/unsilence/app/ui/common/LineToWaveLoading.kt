package com.unsilence.app.ui.common

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unsilence.app.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

private const val TWO_PI = 6.2831855f

@Composable
fun LineToWaveLoading(
    modifier: Modifier = Modifier,
    color: Color = Brand,
    height: Dp = 48.dp,
    strokeWidth: Dp = 2.dp,
    static: Boolean = false,
    appearDelayMs: Long = 150L,
) {
    var visible by remember { mutableStateOf(appearDelayMs <= 0L) }
    LaunchedEffect(appearDelayMs) {
        if (!visible) {
            delay(appearDelayMs)
            visible = true
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "waveAlpha",
    )

    val context = LocalContext.current
    val animationsDisabled = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
    val frozen = static || animationsDisabled

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

    val envelope = remember { Animatable(if (frozen) 1f else 0f) }
    LaunchedEffect(frozen) {
        if (frozen) {
            envelope.snapTo(1f)
        } else {
            envelope.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            )
        }
    }

    val path = remember { Path() }
    Canvas(
        modifier = modifier
            .height(height)
            .graphicsLayer { this.alpha = alpha },
    ) {
        val width = size.width
        val midY = size.height / 2f
        val amplitude = size.height * 0.38f * envelope.value * breath.value
        val waveLength = width / 1.5f
        val steps = 48

        path.reset()
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val x = width * t
            val window = sin(PI.toFloat() * t)
            val y = midY - amplitude * window * sin(TWO_PI * x / waveLength - phase.value)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
        )
    }
}
