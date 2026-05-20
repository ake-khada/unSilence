package com.unsilence.app.ui.feed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.Zap

enum class EngagementSection { REPLIES, REPOSTS, REACTIONS, ZAPS }

/**
 * Inline engagement drawer — icon-grouped rows below the action bar.
 * Order: zaps · reposts · reactions (replies excluded — shown in comments).
 * Each row: icon + count (Slice 4 adds avatar FlowRow from MES indexes).
 * Only rows with non-zero counts render.
 */
@Composable
internal fun EngagementDrawer(
    repostCount: Int,
    reactionCount: Int,
    zapSats: Long,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        // 32dp spacer matching the chevron width — aligns drawer content
        // with the action bar's first slot (comments icon column).
        Spacer(Modifier.width(32.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = Spacing.small),
        ) {
            if (zapSats > 0) {
                EngagementRow(
                    icon = Icons.Filled.ElectricBolt,
                    label = zapSats.toCompactSats(),
                    tint = Zap,
                )
            }
            if (repostCount > 0) {
                EngagementRow(
                    icon = Icons.Filled.Repeat,
                    label = formatCount(repostCount),
                    tint = ActionTint,
                )
            }
            if (reactionCount > 0) {
                EngagementRow(
                    icon = Icons.Filled.Favorite,
                    label = formatCount(reactionCount),
                    tint = Like,
                )
            }
        }
    }
}

/** Single engagement row: icon + count. Slice 4 adds avatar FlowRow after the label. */
@Composable
private fun EngagementRow(
    icon: ImageVector,
    label: String,
    tint: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.micro),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(Sizing.actionIcon),
        )
        Spacer(Modifier.width(Spacing.small))
        // Slice 4: replace with avatar FlowRow from MES contributor indexes
        Text(
            text = label,
            color = Text3,
            fontSize = AppType.footnote,
        )
    }
}
