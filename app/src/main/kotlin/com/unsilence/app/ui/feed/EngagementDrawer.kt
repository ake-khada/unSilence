package com.unsilence.app.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.ZapDetail
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Zap
import kotlinx.coroutines.flow.StateFlow

enum class EngagementSection { REPLIES, REPOSTS, REACTIONS, ZAPS }

private data class DrawerData(
    val zaps: List<ZapDetail> = emptyList(),
    val reposts: List<String> = emptyList(),
    val reactionsByEmoji: List<Pair<String, List<String>>> = emptyList(),
)

/**
 * Inline engagement drawer — icon-grouped rows with real avatars.
 * Order: zaps · reposts · per-emoji reactions. Replies excluded.
 * Layout: wrapping FlowRow of avatars in slots 1–4, trailing icon in slot 5
 * (aligned under the action bar dropdown chevron). Minimal vertical gap between categories.
 * Collects statsFlow as invalidation signal, re-reads MES indexes on each emission.
 */
@Composable
internal fun EngagementDrawer(
    eventId: String,
    statsFlow: ((String) -> StateFlow<com.unsilence.app.data.memory.EventStats>)?,
    zapDetailsForEvent: ((String) -> List<ZapDetail>)?,
    repostPubkeysForEvent: ((String) -> List<String>)?,
    reactionsForEvent: ((String) -> List<Pair<String, String>>)?,
    profileFlow: ((String) -> StateFlow<UserEntity?>)?,
    lookupProfile: (suspend (String) -> UserEntity?)?,
    onProfileTap: (String) -> Unit,
) {
    val stats = statsFlow?.invoke(eventId)?.collectAsState()?.value

    val drawerData = produceState(DrawerData(), eventId, stats) {
        val zaps = zapDetailsForEvent?.invoke(eventId)
            ?.sortedByDescending { it.sats } ?: emptyList()
        val reposts = repostPubkeysForEvent?.invoke(eventId) ?: emptyList()
        val grouped = reactionsForEvent?.invoke(eventId)
            ?.filter { it.second != "-" }
            ?.groupBy { it.second }
            ?.mapValues { (_, pairs) -> pairs.map { it.first }.distinct() }
            ?.toList()
            ?.sortedByDescending { it.second.size }
            ?: emptyList()
        value = DrawerData(zaps, reposts, grouped)
    }.value

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.micro, bottom = Spacing.small),
    ) {
        // Zaps
        if (drawerData.zaps.isNotEmpty()) {
            EngagementRow(
                icon = Icons.Filled.ElectricBolt,
                tint = Zap,
            ) {
                drawerData.zaps.forEach { zap ->
                    ZapChip(
                        zap = zap,
                        profileFlow = profileFlow,
                        lookupProfile = lookupProfile,
                        onTap = { onProfileTap(zap.senderPubkey) },
                    )
                }
            }
        }

        // Reposts
        if (drawerData.reposts.isNotEmpty()) {
            EngagementRow(
                icon = Icons.Filled.Repeat,
                tint = ActionTint,
            ) {
                drawerData.reposts.forEach { pubkey ->
                    AvatarChip(
                        pubkey = pubkey,
                        profileFlow = profileFlow,
                        lookupProfile = lookupProfile,
                        onTap = { onProfileTap(pubkey) },
                    )
                }
            }
        }

        // Per-emoji reactions
        for ((emoji, pubkeys) in drawerData.reactionsByEmoji) {
            val displayEmoji = if (emoji == "+") "\u2764\uFE0F" else emoji
            EngagementRow(
                emojiText = displayEmoji,
                tint = if (emoji == "+") Like else ActionTint,
            ) {
                pubkeys.forEach { pubkey ->
                    AvatarChip(
                        pubkey = pubkey,
                        profileFlow = profileFlow,
                        lookupProfile = lookupProfile,
                        onTap = { onProfileTap(pubkey) },
                    )
                }
            }
        }
    }
}

/**
 * Single engagement category row — mirrors the action bar's 5-slot grid.
 * Slots 1–4: wrapping FlowRow of avatars, right-aligned.
 * Slot 5: icon/emoji centered, directly under the action bar chevron.
 * Minimal vertical spacing between rows for tight stacking.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EngagementRow(
    icon: ImageVector? = null,
    emojiText: String? = null,
    tint: androidx.compose.ui.graphics.Color,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Slots 1–4: wrapping avatar grid, right-aligned
        FlowRow(
            modifier = Modifier.weight(4f),
            horizontalArrangement = Arrangement.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            content()
        }

        // Slot 5: icon centered — matches action bar chevron position
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(Sizing.actionIcon),
                )
            } else if (emojiText != null) {
                Text(
                    text = emojiText,
                    fontSize = AppType.body,
                    modifier = Modifier.size(Sizing.actionIcon),
                )
            }
        }
    }
}

/** 32dp circular avatar, clickable to open profile. */
@Composable
private fun AvatarChip(
    pubkey: String,
    profileFlow: ((String) -> StateFlow<UserEntity?>)?,
    lookupProfile: (suspend (String) -> UserEntity?)?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AvatarImage(
        pubkey = pubkey,
        picture = null,
        sizeDp = Sizing.avatar,
        profileFlow = profileFlow,
        lookupProfile = lookupProfile,
        modifier = modifier
            .size(Sizing.avatar)
            .clickable(onClick = onTap),
    )
}

/** Zap contributor: avatar with bold sats label overlaid at the bottom. */
@Composable
private fun ZapChip(
    zap: ZapDetail,
    profileFlow: ((String) -> StateFlow<UserEntity?>)?,
    lookupProfile: (suspend (String) -> UserEntity?)?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(Sizing.avatar)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AvatarImage(
            pubkey = zap.senderPubkey,
            picture = null,
            sizeDp = Sizing.avatar,
            profileFlow = profileFlow,
            lookupProfile = lookupProfile,
            modifier = Modifier.size(Sizing.avatar),
        )
        Text(
            text = zap.sats.toCompactSats(),
            color = Zap,
            fontSize = AppType.caption,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
