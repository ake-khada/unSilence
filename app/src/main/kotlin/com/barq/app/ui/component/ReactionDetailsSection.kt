package com.barq.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.barq.app.nostr.ProfileData

private val ICON_COL_WIDTH = 26.dp
private val AVATAR_SIZE = 24
private val BADGE_BG = Color(0xCC000000)
private val ZAP_ORANGE = Color(0xFFFF8C00)
private val BOOST_GREEN = Color(0xFF4CAF50)

// ---------------------------------------------------------------------------
// Public API — StackedAvatarRow kept for NotificationsScreen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StackedAvatarRow(
    pubkeys: List<String>,
    resolveProfile: (String) -> ProfileData?,
    onProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isFollowing: ((String) -> Boolean)? = null,
    highlightFirst: Boolean = false,
    maxAvatars: Int = 5,
    onProfileLongPress: ((String) -> Unit)? = null,
    showAll: Boolean = false
) {
    if (showAll) {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy((-9).dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            pubkeys.forEachIndexed { index, pubkey ->
                val profile = resolveProfile(pubkey)
                ProfilePicture(
                    url = profile?.picture,
                    pubkey = pubkey,
                    size = AVATAR_SIZE,
                    showFollowBadge = isFollowing?.invoke(pubkey) ?: false,
                    highlighted = highlightFirst && index == 0,
                    onClick = { onProfileClick(pubkey) },
                    onLongPress = onProfileLongPress?.let { { it(pubkey) } },
                    modifier = Modifier.zIndex((pubkeys.size - index).toFloat())
                )
            }
        }
    } else {
        val displayed = if (pubkeys.size <= maxAvatars) pubkeys else pubkeys.take(maxAvatars)
        val overflow = pubkeys.size - displayed.size
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            Box {
                displayed.forEachIndexed { index, pubkey ->
                    val profile = resolveProfile(pubkey)
                    ProfilePicture(
                        url = profile?.picture,
                        pubkey = pubkey,
                        size = AVATAR_SIZE,
                        showFollowBadge = isFollowing?.invoke(pubkey) ?: false,
                        highlighted = highlightFirst && index == 0,
                        onClick = { onProfileClick(pubkey) },
                        onLongPress = onProfileLongPress?.let { { it(pubkey) } },
                        modifier = Modifier
                            .zIndex((displayed.size - index).toFloat())
                            .offset(x = (27 * index).dp)
                    )
                }
            }
            Spacer(Modifier.width((27 * (displayed.size - 1) + AVATAR_SIZE).dp))
            if (overflow > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "+$overflow",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// Keep ZapRow in public API — used directly in some screens
@Composable
fun ZapRow(
    pubkey: String,
    sats: Long,
    message: String,
    profile: ProfileData?,
    onProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val name = profile?.displayString ?: (pubkey.take(8) + "...")
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfilePicture(url = profile?.picture, pubkey = pubkey, size = 20, onClick = { onProfileClick(pubkey) })
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.clickable { onProfileClick(pubkey) }
            )
            if (message.isNotBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ElectricBolt, contentDescription = null, tint = ZAP_ORANGE, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(2.dp))
            Text(text = formatSats(sats), style = MaterialTheme.typography.labelMedium, color = ZAP_ORANGE)
        }
    }
}

// ---------------------------------------------------------------------------
// Main redesigned section
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionDetailsSection(
    reactionDetails: Map<String, List<String>>,
    zapDetails: List<Triple<String, Long, String>>,
    repostDetails: List<String> = emptyList(),
    resolveProfile: (String) -> ProfileData?,
    onProfileClick: (String) -> Unit,
    reactionEmojiUrls: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // ── Zaps ─────────────────────────────────────────────────────────────
        if (zapDetails.isNotEmpty()) {
            val sortedZaps = zapDetails.sortedByDescending { it.second }
            SectionRow(
                icon = {
                    Icon(
                        Icons.Outlined.ElectricBolt,
                        contentDescription = "Zaps",
                        tint = ZAP_ORANGE,
                        modifier = Modifier.size(14.dp)
                    )
                }
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    sortedZaps.forEach { (pubkey, sats, _) ->
                        ZapAvatarChip(
                            profile = resolveProfile(pubkey),
                            sats = sats,
                            onClick = { onProfileClick(pubkey) },
                            pubkey = pubkey
                        )
                    }
                }
            }
        }

        // ── Boosts ───────────────────────────────────────────────────────────
        if (repostDetails.isNotEmpty()) {
            SectionRow(
                icon = {
                    Icon(
                        Icons.Outlined.Repeat,
                        contentDescription = "Boosts",
                        tint = BOOST_GREEN,
                        modifier = Modifier.size(14.dp)
                    )
                }
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    repostDetails.forEach { pubkey ->
                        ProfilePicture(
                            url = resolveProfile(pubkey)?.picture,
                            pubkey = pubkey,
                            size = AVATAR_SIZE,
                            onClick = { onProfileClick(pubkey) }
                        )
                    }
                }
            }
        }

        // ── Reactions ─────────────────────────────────────────────────────────
        reactionDetails.entries
            .sortedByDescending { it.value.size }
            .forEach { (emoji, pubkeys) ->
                val emojiUrl = reactionEmojiUrls[emoji]
                SectionRow(
                    icon = {
                        if (emojiUrl != null) {
                            AsyncImage(
                                model = emojiUrl,
                                contentDescription = emoji,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Text(text = emoji, fontSize = 18.sp, lineHeight = 20.sp)
                        }
                    }
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        pubkeys.forEach { pubkey ->
                            ProfilePicture(
                                url = resolveProfile(pubkey)?.picture,
                                pubkey = pubkey,
                                size = AVATAR_SIZE,
                                onClick = { onProfileClick(pubkey) }
                            )
                        }
                    }
                }
            }
    }
}

// ---------------------------------------------------------------------------
// SeenOnSection — unchanged
// ---------------------------------------------------------------------------

@Composable
fun SeenOnSection(
    relayIcons: List<Pair<String, String?>>,
    onRelayClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxIcons: Int = 5
) {
    val displayed = if (relayIcons.size <= maxIcons) relayIcons else relayIcons.take(maxIcons)
    val overflow = relayIcons.size - displayed.size

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Seen on",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Box {
                displayed.forEachIndexed { index, (relayUrl, iconUrl) ->
                    RelayIcon(
                        iconUrl = iconUrl,
                        relayUrl = relayUrl,
                        size = 24.dp,
                        modifier = Modifier
                            .zIndex((displayed.size - index).toFloat())
                            .offset(x = (18 * index).dp)
                            .clickable { onRelayClick(relayUrl) }
                    )
                }
            }
            Spacer(Modifier.width((18 * (displayed.size - 1) + 24).dp))
            if (overflow > 0) {
                Spacer(Modifier.width(4.dp))
                Text(text = "+$overflow", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

@Composable
private fun SectionRow(
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .width(ICON_COL_WIDTH)
                .height(AVATAR_SIZE.dp),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        content()
    }
}

@Composable
private fun ZapAvatarChip(
    profile: ProfileData?,
    sats: Long,
    onClick: () -> Unit,
    pubkey: String? = null
) {
    Box(contentAlignment = Alignment.BottomCenter) {
        ProfilePicture(
            url = profile?.picture,
            pubkey = pubkey,
            size = AVATAR_SIZE,
            onClick = onClick
        )
        // Sats badge overlaid at bottom center
        Text(
            text = formatSatsCompact(sats),
            style = MaterialTheme.typography.labelSmall,
            color = ZAP_ORANGE,
            fontSize = 8.sp,
            modifier = Modifier
                .offset(y = 2.dp)
                .background(color = BADGE_BG, shape = RoundedCornerShape(4.dp))
                .padding(horizontal = 3.dp, vertical = 1.dp)
                .widthIn(max = (AVATAR_SIZE).dp)
        )
    }
}

private fun formatSats(sats: Long): String = when {
    sats >= 1_000_000 -> "${sats / 1_000_000}M sats"
    sats >= 1_000 -> "${sats / 1_000}K sats"
    else -> "$sats sats"
}

private fun formatSatsCompact(sats: Long): String = when {
    sats >= 1_000_000 -> "${sats / 1_000_000}M"
    sats >= 1_000 -> "${sats / 1_000}K"
    else -> "$sats"
}
