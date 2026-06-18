package com.unsilence.app.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.unsilence.app.data.memory.NotificationActor
import com.unsilence.app.data.memory.NotificationRow
import com.unsilence.app.data.memory.ReactionContent
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap

/**
 * Notification-scoped actor sheet — the "who" behind a grouped reaction / repost
 * / zap row. Deliberately NOT the EngagementDrawer: this lists exactly the
 * actors folded into THIS notification (recency-sorted, deduped), with their
 * reaction emoji (reactions) or sats (zaps), and taps route to the profile.
 * Anonymous zaps stay collapsed into one aggregate row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationActorSheet(
    row: NotificationRow.Grouped,
    onProfileClick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val (icon, iconTint, verb) = notifMeta(row.notifType)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary) },
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            // Header: "N verb (· sats)".
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.small))
                Text(
                    text = "${row.people} $verb",
                    color = Color.White,
                    fontSize = AppType.subheading,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.notifType == "zap" && row.sumSats > 0) {
                    Spacer(Modifier.width(Spacing.small))
                    Text(
                        text = "· ${formatSats(row.sumSats)} sats",
                        color = Zap,
                        fontWeight = FontWeight.Medium,
                        fontSize = AppType.subheading,
                    )
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(row.actors, key = { it.pubkey ?: "anon-${it.createdAt}" }) { actor ->
                    ActorSheetRow(
                        actor = actor,
                        notifType = row.notifType,
                        onClick = {
                            actor.pubkey?.let {
                                onProfileClick(it)
                                onDismiss()
                            }
                        },
                    )
                }
                if (row.anonymousCount > 0) {
                    item(key = "anon-aggregate") {
                        AnonymousActorRow(
                            count = row.anonymousCount,
                            sats = row.anonymousSats,
                            notifType = row.notifType,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActorSheetRow(
    actor: NotificationActor,
    notifType: String,
    onClick: () -> Unit,
) {
    val label = actor.displayName?.takeIf { it.isNotBlank() }
        ?: actor.name?.takeIf { it.isNotBlank() }
        ?: actor.pubkey?.let { "${it.take(6)}…${it.takeLast(4)}" }
        ?: "anonymous"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = actor.pubkey != null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(36.dp).clip(CircleShape)) {
            val pk = actor.pubkey
            if (pk != null) {
                IdentIcon(pubkey = pk, modifier = Modifier.fillMaxSize())
                if (!actor.picture.isNullOrBlank()) {
                    AsyncImage(
                        model = rememberAvatarImageRequest(actor.picture, 36.dp),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Surface2))
            }
        }
        Spacer(Modifier.width(Spacing.medium))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = Color.White,
            fontSize = AppType.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(Spacing.small))
        when (notifType) {
            "reaction" -> actor.reaction?.let { ReactionGlyph(it) }
            "zap" -> if (actor.sats > 0) {
                Text(
                    text = "${formatSats(actor.sats)} sats",
                    color = Zap,
                    fontWeight = FontWeight.Medium,
                    fontSize = AppType.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AnonymousActorRow(count: Int, sats: Long, notifType: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(Surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.ElectricBolt,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(Spacing.medium))
        Text(
            text = if (count == 1) "1 anonymous" else "$count anonymous",
            modifier = Modifier.weight(1f),
            color = TextSecondary,
            fontSize = AppType.bodyLarge,
            maxLines = 1,
        )
        if (notifType == "zap" && sats > 0) {
            Text(
                text = "${formatSats(sats)} sats",
                color = Zap,
                fontWeight = FontWeight.Medium,
                fontSize = AppType.bodySmall,
            )
        }
    }
}

/** Render an actor's reaction — standard emoji as text, custom as its image. */
@Composable
private fun ReactionGlyph(reaction: ReactionContent) {
    when (reaction) {
        is ReactionContent.Standard -> Text(
            text = if (reaction.emoji == "+" || reaction.emoji.isBlank()) "❤" else reaction.emoji,
            fontSize = AppType.subheading,
        )
        is ReactionContent.Custom -> AsyncImage(
            model = reaction.url,
            contentDescription = reaction.shortcode,
            modifier = Modifier.size(20.dp),
        )
    }
}
