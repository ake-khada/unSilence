package com.unsilence.app.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/** Same directional thresholds as the tab shell; only visibility flips are observable. */
internal class ScrollAwayState {
    var visible by mutableStateOf(true)
        private set
    private var accumulated = 0f

    fun show() { accumulated = 0f; visible = true }

    fun scroll(dy: Float) {
        if ((dy < 0 && accumulated > 0) || (dy > 0 && accumulated < 0)) accumulated = 0f
        accumulated += dy
        if ((accumulated < -60f && visible) || (accumulated > 30f && !visible)) {
            visible = !visible
            accumulated = 0f
        }
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            scroll(available.y)
            return Offset.Zero
        }
    }
}

/** Translation only: never feed this animated offset into a list's padding or bounds. */
@Composable
internal fun Modifier.scrollAwayHeader(
    visible: Boolean,
    motionEnabled: Boolean = rememberAnimatorDurationScale() > 0f && !rememberPowerSaveMode(),
): Modifier {
    var height by remember { mutableIntStateOf(0) }
    val offset = animateFloatAsState(
        targetValue = if (visible) 0f else -height.toFloat(),
        animationSpec = tween(if (motionEnabled) 250 else 0, easing = FastOutSlowInEasing),
        label = "screenControlsOffset",
    )
    return this.onSizeChanged { height = it.height }
        .offset { IntOffset(0, offset.value.roundToInt()) }
}
