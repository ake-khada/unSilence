package com.unsilence.app.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.unsilence.app.ui.theme.DividerColor

internal val FeedDividerBrush = Brush.horizontalGradient(
    0f to Color.Transparent,
    0.3f to DividerColor,
    0.7f to DividerColor,
    1f to Color.Transparent,
)

@Composable
fun FeedDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FeedDividerBrush),
    )
}
