package com.unsilence.app.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.unsilence.app.data.db.dao.NotificationRow
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.ZapAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun NotificationsScreen(
    onNoteClick: (String) -> Unit = {},
    viewModel: NotificationsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        when {
            state.loading -> {
                CircularProgressIndicator(
                    color    = Cyan,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            state.items.isEmpty() -> {
                Text(
                    text     = "No notifications yet",
                    color    = TextSecondary,
                    fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            else -> {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(state.items, key = { it.id }) { row ->
                        NotificationItem(
                            row         = row,
                            onNoteClick = onNoteClick,
                        )
                        HorizontalDivider(
                            color     = MaterialTheme.colorScheme.surfaceVariant,
                            thickness = 0.5.dp,
                        )
                    }
                }
            }
        }
    }
}

// ── Single notification row ───────────────────────────────────────────────────

@Composable
private fun NotificationItem(
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
        // ── Actor avatar ──────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .size(Sizing.avatar)
                .clip(CircleShape),
        ) {
            IdentIcon(pubkey = row.actorPubkey, modifier = Modifier.fillMaxSize())
            if (!row.actorPicture.isNullOrBlank()) {
                AsyncImage(
                    model              = row.actorPicture,
                    contentDescription = null,
                    modifier           = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(Modifier.width(Spacing.small))

        // ── Content column ────────────────────────────────────────────────────
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    tint               = iconTint,
                    modifier           = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text       = actorLabel,
                    color      = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 13.sp,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text     = actionText,
                    color    = TextSecondary,
                    fontSize = 13.sp,
                )
            }

            if (row.targetNoteContent.isNotBlank()) {
                Text(
                    text     = row.targetNoteContent,
                    color    = TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        Spacer(Modifier.width(Spacing.small))

        // ── Timestamp ─────────────────────────────────────────────────────────
        Text(
            text     = relativeTime(row.createdAt),
            color    = TextSecondary,
            fontSize = 11.sp,
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
    "reaction" -> NotifMeta(Icons.Filled.Favorite,              Color(0xFFE91E63), "liked your note")
    "reply"    -> NotifMeta(Icons.AutoMirrored.Filled.Chat,     Cyan,              "replied to your note")
    "repost"   -> NotifMeta(Icons.Filled.Repeat,                Cyan,              "boosted your note")
    "zap"      -> NotifMeta(Icons.Filled.ElectricBolt,          ZapAmber,          "zapped your note")
    else       -> NotifMeta(Icons.Filled.AlternateEmail,        TextSecondary,     "mentioned you")
}

private fun relativeTime(createdAtSeconds: Long): String {
    val diffMs = System.currentTimeMillis() - createdAtSeconds * 1000L
    return when {
        diffMs < TimeUnit.MINUTES.toMillis(1) -> "now"
        diffMs < TimeUnit.HOURS.toMillis(1)   -> "${TimeUnit.MILLISECONDS.toMinutes(diffMs)}m"
        diffMs < TimeUnit.DAYS.toMillis(1)    -> "${TimeUnit.MILLISECONDS.toHours(diffMs)}h"
        diffMs < TimeUnit.DAYS.toMillis(7)    -> "${TimeUnit.MILLISECONDS.toDays(diffMs)}d"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(createdAtSeconds * 1000L))
    }
}
