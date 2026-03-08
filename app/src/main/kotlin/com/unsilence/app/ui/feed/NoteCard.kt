package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private val ActionTint = Color(0xFF555555)
private val MediaPlaceholder = Color(0xFF1A1A1A)

// Matches URLs ending in image extensions, or from known Nostr image hosts.
private val IMAGE_URL_REGEX = Regex(
    """https?://\S+\.(?:jpg|jpeg|png|gif|webp)(?:\?\S*)?|https?://(?:image\.nostr\.build|i\.nostr\.build|nostr\.build|blossom\.primal\.net)/\S+""",
    RegexOption.IGNORE_CASE,
)

@Composable
fun NoteCard(row: FeedRow, modifier: Modifier = Modifier) {
    val imageUrls = IMAGE_URL_REGEX.findAll(row.content).map { it.value }.toList()
    val textContent = IMAGE_URL_REGEX.replace(row.content, "").trim()

    Column(modifier = modifier.fillMaxWidth()) {

        // ── Header row: avatar + name + timestamp ─────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarImage(
                pubkey   = row.pubkey,
                picture  = row.authorPicture,
                modifier = Modifier.size(Sizing.avatar),
            )
            Spacer(Modifier.width(Spacing.small))
            Text(
                text       = row.displayName ?: "${row.pubkey.take(8)}…",
                color      = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 14.sp,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.micro))
            Text(
                text     = relativeTime(row.createdAt),
                color    = TextSecondary,
                fontSize = 12.sp,
            )
        }

        // ── Full-width text content ────────────────────────────────────────────
        if (textContent.isNotBlank()) {
            Text(
                text       = textContent,
                color      = MaterialTheme.colorScheme.onSurface,
                fontSize   = 15.sp,
                lineHeight = 22.sp,
                modifier   = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small),
            )
        }

        // ── Full-width inline images ───────────────────────────────────────────
        imageUrls.forEach { url ->
            AsyncImage(
                model              = url,
                contentDescription = null,
                contentScale       = ContentScale.FillWidth,
                modifier           = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small)
                    .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
                    .background(MediaPlaceholder),
            )
        }

        // ── Full-width action bar ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.medium)
                .padding(bottom = Spacing.small),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            ActionButton(
                icon               = Icons.AutoMirrored.Filled.Chat,
                count              = row.replyCount,
                contentDescription = "Replies",
            )
            ActionButton(
                icon               = Icons.Filled.Repeat,
                count              = 0,
                contentDescription = "Reposts",
            )
            ActionButton(
                icon               = Icons.Filled.Favorite,
                count              = row.reactionCount,
                contentDescription = "Reactions",
            )
            ActionButton(
                icon               = Icons.Filled.ElectricBolt,
                count              = 0,
                contentDescription = "Zaps",
            )
            ActionButton(
                icon               = Icons.Filled.Share,
                count              = 0,
                contentDescription = "Share",
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)
    }
}

// ── Sub-composables ────────────────────────────────────────────────────────────

/**
 * IdentIcon always renders as the background/fallback.
 * AsyncImage overlays on top when a picture URL is available.
 * If the network load fails, the IdentIcon underneath remains visible.
 */
@Composable
private fun AvatarImage(pubkey: String, picture: String?, modifier: Modifier = Modifier) {
    Box(modifier = modifier.clip(CircleShape)) {
        IdentIcon(pubkey = pubkey, modifier = Modifier.fillMaxSize())
        if (!picture.isNullOrBlank()) {
            AsyncImage(
                model              = picture,
                contentDescription = null,
                modifier           = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Single action bar button: vector icon (16dp) + optional count (12sp).
 * All icons and counts use a uniform muted tint — no activation state yet (Sprint 3).
 */
@Composable
private fun ActionButton(
    icon: ImageVector,
    count: Int,
    contentDescription: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier.defaultMinSize(minWidth = 48.dp),
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = contentDescription,
            tint               = ActionTint,
            modifier           = Modifier.size(Sizing.actionIcon),
        )
        if (count > 0) {
            Spacer(Modifier.width(Spacing.micro))
            Text(
                text     = formatCount(count),
                color    = ActionTint,
                fontSize = 12.sp,
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private val FeedRow.displayName: String?
    get() = authorDisplayName?.takeIf { it.isNotBlank() }
         ?: authorName?.takeIf { it.isNotBlank() }

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

private fun formatCount(n: Int): String = when {
    n < 1_000  -> "$n"
    n < 10_000 -> "%.1fk".format(n / 1_000f)
    else        -> "${n / 1_000}k"
}
