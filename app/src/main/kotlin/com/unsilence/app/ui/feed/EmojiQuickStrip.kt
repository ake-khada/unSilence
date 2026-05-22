package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.unsilence.app.data.memory.CustomEmoji
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.TextSecondary

private const val QUICK_STRIP_SLOTS = 5

/**
 * Compact 6-tile horizontal strip: up to 5 pinned emoji + trailing "…" to open full picker.
 * Shown as a popup/overlay near the heart button on long-press.
 * If no pinned emoji exist, shows nothing (caller opens full sheet directly).
 */
@Composable
internal fun EmojiQuickStrip(
    pinnedEmojis: List<CustomEmoji>,
    onSelect: (CustomEmoji) -> Unit,
    onOpenFullPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2)
            .padding(horizontal = Spacing.small, vertical = Spacing.micro),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pinnedEmojis.take(QUICK_STRIP_SLOTS).forEach { emoji ->
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(emoji) },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = emoji.url,
                    contentDescription = emoji.shortcode,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        // Trailing "…" opens full picker
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onOpenFullPicker() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "\u2026",
                color = TextSecondary,
                fontSize = AppType.heading,
            )
        }
    }
}
