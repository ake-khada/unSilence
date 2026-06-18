package com.unsilence.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Wraps a compact glyph/pill in a ≥[minSize] clickable hit area without
 * enlarging the visual. Lets icons/pills stay small while meeting the 48dp
 * touch-target accessibility minimum — the content is centered and the touch
 * area is the surrounding box. The inner content keeps its own contentDescription.
 */
@Composable
fun TouchTarget(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minSize: Dp = 48.dp,
    // Adjacent controls (e.g. side-by-side toggles) can tighten width while
    // keeping the full-height tap area, so they don't drift apart.
    minWidth: Dp = minSize,
    minHeight: Dp = minSize,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .sizeIn(minWidth = minWidth, minHeight = minHeight)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
