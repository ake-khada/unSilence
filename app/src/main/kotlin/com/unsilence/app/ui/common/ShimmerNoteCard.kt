package com.unsilence.app.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Surface2

private val ShimmerDark = Surface1
private val ShimmerLight = Surface2
private val ShimmerColors = listOf(ShimmerDark, ShimmerLight, ShimmerDark)

/** Draws the animated shimmer gradient at draw time — reading [offset] inside
 *  the draw lambda keeps the per-frame animation out of composition. */
private fun Modifier.shimmerBackground(offset: () -> Float): Modifier = drawBehind {
    val x = offset()
    drawRect(
        Brush.linearGradient(
            colors = ShimmerColors,
            start = Offset(x, 0f),
            end = Offset(x + 300f, 0f),
        ),
    )
}

@Composable
fun ShimmerNoteCard(showMedia: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerOffset",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        Row {
            // Avatar placeholder
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .shimmerBackground { offset },
            )

            Spacer(Modifier.width(Spacing.small))

            Column(modifier = Modifier.weight(1f)) {
                // Author name
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerBackground { offset },
                )
                Spacer(Modifier.height(Spacing.small))
                // Content line 1
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerBackground { offset },
                )
                Spacer(Modifier.height(Spacing.small))
                // Content line 2
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerBackground { offset },
                )
            }
        }

        if (showMedia) {
            Spacer(Modifier.height(Spacing.small))
            // Media placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .shimmerBackground { offset },
            )
        }

        Spacer(Modifier.height(Spacing.small))
    }
}
