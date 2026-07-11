package com.unsilence.app.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.data.memory.NotificationActor
import com.unsilence.app.data.memory.NotificationRow
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.feed.relativeTime
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap

/**
 * Notification rows share ONE typography rhythm across every type — replies,
 * mentions, likes, boosts, zaps — so the grouped and single layouts read as one
 * system (see notifications_grouped_hybrid_layout.html).
 *
 *   • Single  → 32dp avatar + corner type badge · "Name action · time" / 1-line text
 *   • Grouped → type icon → overlapping actor strip → time · "N verb · sats" / 1-line preview
 */
@Composable
fun NotificationEventRow(
    row: NotificationRow,
    wotLookups: Map<String, WotLookup> = emptyMap(),
    feedWotDisplayMode: FeedWotDisplayMode = FeedWotDisplayMode.NUMBERS,
    onNoteClick: (String) -> Unit,
    onProfileClick: (String) -> Unit,
) {
    when (row) {
        is NotificationRow.Single -> SingleNotificationRow(row, wotLookups, feedWotDisplayMode, onNoteClick, onProfileClick)
        is NotificationRow.Grouped -> GroupedNotificationRow(row, wotLookups, feedWotDisplayMode, onNoteClick, onProfileClick)
    }
}

// ── Shared notification typography ──────────────────────────────────────────
// One rhythm for every row type. Line height = AppType.bodyLarge (15sp): the
// type-scale step that fits two bodySmall (13sp) lines just inside the 32dp
// avatar. The φ spacing splits (12/20) bracket it — 12 would clip 13sp text, 20
// is too airy — so the type-scale step is the correct compact anchor, snapped to
// a named token rather than a raw literal.
private val NotifLineHeight = AppType.bodyLarge

// Trim the leading above the first line and below the last, and center glyphs
// within each line box. Without this, lineHeight (15sp) on 13sp text distributes
// the extra leading proportionally — more above each line — so the glyph mass
// sits low and the block reads slightly below the avatar's centre. Trimming lets
// the Row's CenterVertically center the actual glyphs, not the padded box.
private val NotifTextStyle = TextStyle(
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

/** Name / action / count — bodySmall, tight line height, single line. */
@Composable
private fun NotificationPrimaryText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = AppType.bodySmall,
        lineHeight = NotifLineHeight,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = NotifTextStyle,
    )
}

/** Target/preview text — same metrics, dim, always a single line. */
@Composable
private fun NotificationPreviewText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        color = TextSecondary.copy(alpha = 0.7f),
        fontSize = AppType.bodySmall,
        lineHeight = NotifLineHeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = NotifTextStyle,
    )
}

/** Right-edge timestamp — caption scale. */
@Composable
private fun NotificationTimestamp(
    createdAt: Long,
    lookup: WotLookup? = null,
    mode: FeedWotDisplayMode = FeedWotDisplayMode.OFF,
    modifier: Modifier = Modifier,
) {
    WotFeedMetaTimestamp(
        lookup = lookup,
        mode = mode,
        timestamp = relativeTime(createdAt),
        modifier = modifier,
        timestampColor = TextSecondary,
    )
}

@Composable
private fun NotificationPreviewWithTimestamp(
    text: String,
    createdAt: Long,
    lookup: WotLookup? = null,
    mode: FeedWotDisplayMode = FeedWotDisplayMode.OFF,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NotificationPreviewText(
            text = text,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Spacing.small))
        NotificationTimestamp(createdAt, lookup = lookup, mode = mode)
    }
}

@Composable
private fun SingleNotificationRow(
    row: NotificationRow.Single,
    wotLookups: Map<String, WotLookup>,
    feedWotDisplayMode: FeedWotDisplayMode,
    onNoteClick: (String) -> Unit,
    onProfileClick: (String) -> Unit,
) {
    // Compact: 32dp avatar with a corner type badge, "Name action · time" on one
    // line, then the reply/mention text on a single dim line — no grey box.
    val (badgeIcon, badgeTint, action) = when (row.notifType) {
        "reply" -> Triple(Icons.AutoMirrored.Filled.Chat, Brand, "replied")
        "poll_vote" -> Triple(Icons.Filled.HowToVote, Brand, "voted on your poll")
        else -> Triple(Icons.Filled.AlternateEmail, TextSecondary, "mentioned you")
    }
    val actorLabel = row.actorDisplayName?.takeIf { it.isNotBlank() }
        ?: row.actorName?.takeIf { it.isNotBlank() }
        ?: "${row.actorPubkey.take(6)}…${row.actorPubkey.takeLast(4)}"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = row.targetNoteId != null) {
                row.targetNoteId?.let { onNoteClick(it) }
            }
            .padding(horizontal = Spacing.medium, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar with a corner type badge — taps it open the actor's profile
        // (the rest of the row opens the note).
        Box(modifier = Modifier.size(Sizing.avatar).clickable { onProfileClick(row.actorPubkey) }) {
            Box(modifier = Modifier.fillMaxSize().clip(CircleShape)) {
                IdentIcon(pubkey = row.actorPubkey, modifier = Modifier.fillMaxSize())
                if (!row.actorPicture.isNullOrBlank()) {
                    AsyncImage(
                        model = rememberAvatarImageRequest(row.actorPicture, Sizing.avatar),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(badgeTint)
                    .border(2.dp, Color.Black, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(badgeIcon, contentDescription = null, tint = Color.Black, modifier = Modifier.size(9.dp))
            }
        }

        Spacer(Modifier.width(Spacing.small))

        // Natural-height text block, centered against the avatar by the Row's
        // CenterVertically + tight line heights → two lines land just inside the
        // avatar height, aligned, without clipping.
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val lookup = wotLookups[row.actorPubkey]
                NotificationPrimaryText(
                    text = actorLabel,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(4.dp))
                NotificationPrimaryText(text = action, color = TextSecondary)
                if (row.targetNoteContent.isBlank()) {
                    Spacer(Modifier.width(Spacing.small))
                    NotificationTimestamp(row.createdAt, lookup = lookup, mode = feedWotDisplayMode)
                }
            }
            if (row.targetNoteContent.isNotBlank()) {
                NotificationPreviewWithTimestamp(
                    text = row.targetNoteContent.trim(),
                    createdAt = row.createdAt,
                    lookup = wotLookups[row.actorPubkey],
                    mode = feedWotDisplayMode,
                )
            }
        }
    }
}

/**
 * Grouped reactions/reposts/zaps. Three-line rhythm matching the mockup:
 *   1. type icon → overlapping actor strip → timestamp (right edge)
 *   2. "N liked/boosted/zapped your note" (+ summed sats for zaps)
 *   3. one-line dim target preview
 * Uses the same primary/preview text metrics as single rows. Tap-to-open actor
 * sheet lands in a later phase.
 */
@Composable
private fun GroupedNotificationRow(
    row: NotificationRow.Grouped,
    wotLookups: Map<String, WotLookup>,
    feedWotDisplayMode: FeedWotDisplayMode,
    onNoteClick: (String) -> Unit,
    onProfileClick: (String) -> Unit,
) {
    val (icon, iconTint, verb) = notifMeta(row.notifType)
    val groupedSignalLookup = row.actors
        .asSequence()
        .mapNotNull { actor -> actor.pubkey?.let { wotLookups[it] } }
        .firstOrNull { hasWotFeedSignal(it, feedWotDisplayMode) }
    var showActors by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = row.targetNoteId != null) { row.targetNoteId?.let { onNoteClick(it) } }
            .padding(horizontal = Spacing.medium, vertical = 9.dp),
    ) {
        // Content column: the timestamp lives on the note-preview line, so the
        // date follows the text ellipsis instead of floating on the actor strip.
        Column(modifier = Modifier.weight(1f)) {
            // Strip line: type icon → overlapping avatars.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacing.small))
                // Tapping the actor strip opens the actor sheet; the rest of the
                // row opens the note.
                ActorStrip(
                    actors = row.actors,
                    people = row.people,
                    onClick = { showActors = true },
                )
            }

            // Verb line: "N verb (· sats)".
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NotificationPrimaryText(text = row.people.toString(), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(4.dp))
                NotificationPrimaryText(text = verb, color = TextSecondary)
                if (row.notifType == "zap" && row.sumSats > 0) {
                    Spacer(Modifier.width(4.dp))
                    NotificationPrimaryText(
                        text = "· ${formatSats(row.sumSats)} sats",
                        color = Zap,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (row.targetNoteContent.isBlank()) {
                    Spacer(Modifier.width(Spacing.small))
                    NotificationTimestamp(row.mostRecentAt, lookup = groupedSignalLookup, mode = feedWotDisplayMode)
                }
            }

            // Preview line: one dim line, same metrics as single rows.
            if (row.targetNoteContent.isNotBlank()) {
                NotificationPreviewWithTimestamp(
                    text = row.targetNoteContent.trim(),
                    createdAt = row.mostRecentAt,
                    lookup = groupedSignalLookup,
                    mode = feedWotDisplayMode,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }

    if (showActors) {
        NotificationActorSheet(
            row = row,
            onProfileClick = onProfileClick,
            onDismiss = { showActors = false },
        )
    }
}

/**
 * Overlapping avatar strip. Up to five actor avatars at 27dp with a 2dp black
 * ring, drawn with a negative gap so each laps the previous; a "+N" chip closes
 * the strip when more actors remain.
 */
@Composable
private fun ActorStrip(
    actors: List<NotificationActor>,
    people: Int,
    onClick: () -> Unit,
) {
    val shown = actors.take(5)
    Row(
        horizontalArrangement = Arrangement.spacedBy((-7).dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        shown.forEach { actor ->
            Box(
                modifier = Modifier
                    .size(27.dp)
                    .clip(CircleShape)
                    .border(2.dp, Color.Black, CircleShape),
            ) {
                val pk = actor.pubkey
                if (pk != null) {
                    IdentIcon(pubkey = pk, modifier = Modifier.fillMaxSize())
                    if (!actor.picture.isNullOrBlank()) {
                        AsyncImage(
                            model = rememberAvatarImageRequest(actor.picture, 27.dp),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Surface2))
                }
            }
        }
        val extra = people - shown.size
        if (extra > 0) {
            Box(
                modifier = Modifier
                    .size(27.dp)
                    .clip(CircleShape)
                    .background(Surface2)
                    .border(2.dp, Color.Black, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$extra",
                    color = TextSecondary,
                    fontSize = AppType.caption,
                    maxLines = 1,
                )
            }
        }
    }
}

internal fun formatSats(sats: Long): String = when {
    sats >= 1_000_000 -> "%.1fM".format(sats / 1_000_000.0)
    sats >= 1_000 -> "%.1fk".format(sats / 1_000.0)
    else -> sats.toString()
}

// ── Helpers ───────────────────────────────────────────────────────────────────

internal data class NotifMeta(
    val icon: ImageVector,
    val tint: Color,
    val actionText: String,
)

internal fun notifMeta(notifType: String): NotifMeta = when (notifType) {
    "reaction" -> NotifMeta(Icons.Filled.Favorite, Like, "liked your note")
    "reply" -> NotifMeta(Icons.AutoMirrored.Filled.Chat, Brand, "replied to your note")
    "repost" -> NotifMeta(Icons.Filled.Repeat, Brand, "boosted your note")
    "zap" -> NotifMeta(Icons.Filled.ElectricBolt, Zap, "zapped your note")
    else -> NotifMeta(Icons.Filled.AlternateEmail, TextSecondary, "mentioned you")
}
