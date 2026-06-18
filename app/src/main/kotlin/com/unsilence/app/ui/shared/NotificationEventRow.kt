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
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.data.memory.NotificationRow
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.feed.relativeTime
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.SurfaceVariant
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap

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
    when (row) {
        is NotificationRow.Single -> SingleNotificationRow(row, onNoteClick)
        is NotificationRow.Grouped -> GroupedNotificationRow(row, onNoteClick)
    }
}

@Composable
private fun SingleNotificationRow(
    row: NotificationRow.Single,
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
        // Actor avatar with notification type icon below
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(Sizing.avatar)
                    .clip(CircleShape),
            ) {
                IdentIcon(pubkey = row.actorPubkey, modifier = Modifier.fillMaxSize())
                if (!row.actorPicture.isNullOrBlank()) {
                    AsyncImage(
                        model = rememberAvatarImageRequest(row.actorPicture, Sizing.avatar),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(14.dp),
            )
        }

        Spacer(Modifier.width(Spacing.small))

        // Content column
        Column(modifier = Modifier.weight(1f)) {
            // Actor + action label
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = actorLabel,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = AppType.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = actionText,
                    color = TextSecondary,
                    fontSize = AppType.bodySmall,
                )
            }

            // For replies: show the parent note (what was replied to) then the reply
            if (row.notifType == "reply" && row.parentNoteContent.isNotBlank()) {
                CompactNotePreview(
                    content = row.parentNoteContent,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // Compact embedded note preview — plain text for replies/reactions, grey box for reposts/zaps/mentions
            if (row.targetNoteContent.isNotBlank()) {
                if (row.notifType == "reply" || row.notifType == "reaction") {
                    Text(
                        text = row.targetNoteContent.trim(),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (row.notifType == "reply") 0.85f else 0.7f),
                        fontSize = AppType.bodySmall,
                        lineHeight = 18.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    CompactNotePreview(
                        content = row.targetNoteContent,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        Spacer(Modifier.width(Spacing.small))

        // Timestamp
        Text(
            text = relativeTime(row.createdAt),
            color = TextSecondary,
            fontSize = AppType.caption,
        )
    }
}

/**
 * Grouped reactions/reposts/zaps. INTERIM Phase-1 visuals — first actor's avatar
 * + count + verb (+ summed sats for zaps) + target preview. The overlapping actor
 * strip and tap-to-open actor sheet land in later phases.
 */
@Composable
private fun GroupedNotificationRow(
    row: NotificationRow.Grouped,
    onNoteClick: (String) -> Unit,
) {
    val (icon, iconTint, verb) = notifMeta(row.notifType)
    val firstActor = row.actors.firstOrNull()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = row.targetNoteId != null) { row.targetNoteId?.let { onNoteClick(it) } }
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.Top,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(Sizing.avatar).clip(CircleShape)) {
                val pk = firstActor?.pubkey
                if (pk != null) {
                    IdentIcon(pubkey = pk, modifier = Modifier.fillMaxSize())
                    if (!firstActor.picture.isNullOrBlank()) {
                        AsyncImage(
                            model = rememberAvatarImageRequest(firstActor.picture, Sizing.avatar),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Surface2))
                }
            }
            Spacer(Modifier.height(4.dp))
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(14.dp))
        }

        Spacer(Modifier.width(Spacing.small))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.people.toString(),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = AppType.bodySmall,
                )
                Spacer(Modifier.width(4.dp))
                Text(text = verb, color = TextSecondary, fontSize = AppType.bodySmall)
                if (row.notifType == "zap" && row.sumSats > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "· ${formatSats(row.sumSats)} sats",
                        color = Zap,
                        fontWeight = FontWeight.Medium,
                        fontSize = AppType.bodySmall,
                    )
                }
            }
            if (row.targetNoteContent.isNotBlank()) {
                CompactNotePreview(content = row.targetNoteContent, modifier = Modifier.padding(top = 4.dp))
            }
        }

        Spacer(Modifier.width(Spacing.small))

        Text(
            text = relativeTime(row.mostRecentAt),
            color = TextSecondary,
            fontSize = AppType.caption,
        )
    }
}

private fun formatSats(sats: Long): String = when {
    sats >= 1_000_000 -> "%.1fM".format(sats / 1_000_000.0)
    sats >= 1_000 -> "%.1fk".format(sats / 1_000.0)
    else -> sats.toString()
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
            .background(SurfaceVariant)
            .border(0.5.dp, Surface2, RoundedCornerShape(6.dp))
            .padding(horizontal = Spacing.small, vertical = 6.dp),
    ) {
        Text(
            text = content,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            fontSize = AppType.bodySmall,
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
    "reaction" -> NotifMeta(Icons.Filled.Favorite, Like, "liked your note")
    "reply" -> NotifMeta(Icons.AutoMirrored.Filled.Chat, Brand, "replied to your note")
    "repost" -> NotifMeta(Icons.Filled.Repeat, Brand, "boosted your note")
    "zap" -> NotifMeta(Icons.Filled.ElectricBolt, Zap, "zapped your note")
    else -> NotifMeta(Icons.Filled.AlternateEmail, TextSecondary, "mentioned you")
}

