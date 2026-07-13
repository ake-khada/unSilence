package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.unsilence.app.data.memory.CustomEmoji
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.TextSecondary

private const val QUICK_STRIP_SLOTS = 5

@Composable
internal fun AnchoredActionStrip(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!expanded) return
    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        content()
    }
}

@Composable
internal fun ActionQuickStrip(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2)
            .padding(horizontal = Spacing.small, vertical = Spacing.micro),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
internal fun ActionQuickStripTile(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * Compact 6-tile horizontal strip: up to 5 pinned emoji + trailing add action.
 * Rendered as a Popup inside [EventReactButton] on long-press, centered above the heart.
 * If no pinned emoji exist, the caller opens the full sheet directly.
 */
@Composable
internal fun EmojiQuickStrip(
    pinnedEmojis: List<CustomEmoji>,
    onSelect: (CustomEmoji) -> Unit,
    onOpenFullPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ActionQuickStrip(modifier) {
        pinnedEmojis.take(QUICK_STRIP_SLOTS).forEach { emoji ->
            ActionQuickStripTile(
                contentDescription = emoji.shortcode,
                onClick = { onSelect(emoji) },
            ) {
                AsyncImage(
                    model = emoji.url,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        ActionQuickStripTile(
            contentDescription = "More reactions",
            onClick = onOpenFullPicker,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
