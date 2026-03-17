package com.unsilence.app.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.unsilence.app.data.db.dao.NotificationRow
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.ZapAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Unified notification row that renders actor info + notification type icon +
 * an embedded compact note preview using the shared rendering pipeline.
 *
 * Replaces the old custom NotificationItem that had its own inline text
 * preview instead of sharing the same event rendering logic.
 */
@Composable
fun NotificationEventRow(
    row: NotificationRow,
    onNoteClick: (String) -> Unit,
) {
    val (icon, iconTint, actionText) = notifMeta(row.notifType)
    val actorLabel = row.actorDisplayName?.takeIf { it.isNotBlank() }
        ?: row.actorName?.takeIf { it.isNotBlank() }
        ?: "${row.actorPubkey.take(6)}…${row.actorPubkey.takeLast(4)}"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = row.targetNoteId != null) {
                row.targetNoteId?.let { onNoteClick(it) }
            }
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.Top,
    ) {
        // Actor avatar
        Box(
            modifier = Modifier
                .size(Sizing.avatar)
                .clip(CircleShape),
        ) {
            IdentIcon(pubkey = row.actorPubkey, modifier = Modifier.fillMaxSize())
            if (!row.actorPicture.isNullOrBlank()) {
                AsyncImage(
                    model = row.actorPicture,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(Modifier.width(Spacing.small))

        // Content column
        Column(modifier = Modifier.weight(1f)) {
            // Actor + action label
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = actorLabel,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = actionText,
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            }

            // Compact embedded note preview using shared rendering style
            if (row.targetNoteContent.isNotBlank()) {
                CompactNotePreview(
                    content = row.targetNoteContent,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Spacer(Modifier.width(Spacing.small))

        // Timestamp
        Text(
            text = relativeTime(row.createdAt),
            color = TextSecondary,
            fontSize = 11.sp,
        )
    }
}

/**
 * Compact note content preview — used inside notification rows and anywhere
 * a minimal inline event display is needed. Consistent with the embedded
 * quote card style but without the border for notifications.
 */
@Composable
private fun CompactNotePreview(
    content: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0D0D0D))
            .border(0.5.dp, Color(0xFF222222), RoundedCornerShape(6.dp))
            .padding(horizontal = Spacing.small, vertical = 6.dp),
    ) {
        Text(
            text = content,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private data class NotifMeta(
    val icon: ImageVector,
    val tint: Color,
    val actionText: String,
)

private fun notifMeta(notifType: String): NotifMeta = when (notifType) {
    "reaction" -> NotifMeta(Icons.Filled.Favorite, Color(0xFFE91E63), "liked your note")
    "reply" -> NotifMeta(Icons.AutoMirrored.Filled.Chat, Cyan, "replied to your note")
    "repost" -> NotifMeta(Icons.Filled.Repeat, Cyan, "boosted your note")
    "zap" -> NotifMeta(Icons.Filled.ElectricBolt, ZapAmber, "zapped your note")
    else -> NotifMeta(Icons.Filled.AlternateEmail, TextSecondary, "mentioned you")
}

private fun relativeTime(createdAtSeconds: Long): String {
    val diffMs = System.currentTimeMillis() - createdAtSeconds * 1000L
    return when {
        diffMs < TimeUnit.MINUTES.toMillis(1) -> "now"
        diffMs < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diffMs)}m"
        diffMs < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diffMs)}h"
        diffMs < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(diffMs)}d"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(createdAtSeconds * 1000L))
    }
}
