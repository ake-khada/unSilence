package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.unsilence.app.data.memory.ReactionContent
import com.unsilence.app.data.memory.ReactionInfo
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.ZapDetail
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap
import kotlinx.coroutines.flow.StateFlow

enum class EngagementSection { REPLIES, REPOSTS, REACTIONS, ZAPS }

/** Dark gradient under sats labels in zap chips — hoisted so recompositions reuse one instance. */
private val ZapChipGradientColors = listOf(
    Color.Transparent,
    Color.Black.copy(alpha = 0.85f),
    Color.Black.copy(alpha = 0.9f),
)
private val ZapChipGradient = Brush.verticalGradient(colors = ZapChipGradientColors)

private data class ReactionGroup(
    val displayContent: ReactionContent,
    val pubkeys: List<String>,
)

private data class DrawerData(
    val zaps: List<ZapDetail> = emptyList(),
    val anonymousZapSats: Long = 0L,
    val reposts: List<String> = emptyList(),
    val reactionGroups: List<ReactionGroup> = emptyList(),
)

/**
 * Inline engagement drawer — icon-grouped rows with real avatars.
 * Order: zaps (per-zap rows with messages) · reposts · per-emoji reactions.
 * Layout: 5-slot grid mirroring the action bar. Trailing icon in slot 5
 * aligned under the chevron. Minimal vertical gap between categories.
 * Collects statsFlow as invalidation signal, re-reads MES indexes on each emission.
 */
@Composable
internal fun EngagementDrawer(
    eventId: String,
    statsFlow: ((String) -> StateFlow<com.unsilence.app.data.memory.EventStats>)?,
    zapDetailsForEvent: ((String) -> List<ZapDetail>)?,
    repostPubkeysForEvent: ((String) -> List<String>)?,
    reactionsForEvent: ((String) -> List<ReactionInfo>)?,
    profileFlow: ((String) -> StateFlow<UserEntity?>)?,
    lookupProfile: (suspend (String) -> UserEntity?)?,
    onProfileTap: (String) -> Unit,
) {
    val stats = if (statsFlow != null) {
        key(eventId) {
            statsFlow(eventId).collectAsStateWithLifecycle().value
        }
    } else null

    val drawerData = produceState(DrawerData(), eventId, stats) {
        val rawZaps = zapDetailsForEvent?.invoke(eventId) ?: emptyList()
        val (namedZaps, anonZaps) = rawZaps.partition { it.senderPubkey != null }
        val zaps = namedZaps.sortedByDescending { it.sats }
        val anonymousZapSats = anonZaps.sumOf { it.sats }
        val reposts = repostPubkeysForEvent?.invoke(eventId) ?: emptyList()
        val reactions = reactionsForEvent?.invoke(eventId) ?: emptyList()
        val groups = reactions
            .filter { info ->
                (info.content as? ReactionContent.Standard)?.emoji != "-"
            }
            .groupBy { info ->
                when (val c = info.content) {
                    is ReactionContent.Custom -> "custom:${c.shortcode}"
                    is ReactionContent.Standard -> "std:${c.emoji}"
                }
            }
            .map { (_, infos) ->
                ReactionGroup(
                    displayContent = infos.first().content,
                    pubkeys = infos.map { it.pubkey }.distinct(),
                )
            }
            .sortedByDescending { it.pubkeys.size }
        value = DrawerData(zaps, anonymousZapSats, reposts, groups)
    }.value

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.micro, bottom = Spacing.small),
    ) {
        // Zaps — one row per named zap with optional comment, then anonymous aggregate
        if (drawerData.zaps.isNotEmpty() || drawerData.anonymousZapSats > 0) {
            drawerData.zaps.forEachIndexed { index, zap ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Slots 1–4: avatar + message
                    Row(
                        modifier = Modifier.weight(4f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        if (!zap.comment.isNullOrBlank()) {
                            Text(
                                text = zap.comment,
                                color = TextSecondary,
                                fontSize = AppType.footnote,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(Spacing.small))
                        }
                        ZapChip(
                            zap = zap,
                            profileFlow = profileFlow,
                            lookupProfile = lookupProfile,
                            onTap = { zap.senderPubkey?.let { onProfileTap(it) } },
                        )
                    }

                    // Slot 5: zap icon on FIRST row only
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (index == 0) {
                            Icon(
                                imageVector = Icons.Filled.ElectricBolt,
                                contentDescription = null,
                                tint = Zap,
                                modifier = Modifier.size(Sizing.actionIcon),
                            )
                        }
                    }
                }
            }
            // Anonymous zap aggregate — single chip with QuestionMark icon
            if (drawerData.anonymousZapSats > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(4f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        AnonymousZapChip(sats = drawerData.anonymousZapSats)
                    }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Zap icon only if no named zaps above (otherwise already shown)
                        if (drawerData.zaps.isEmpty()) {
                            Icon(
                                imageVector = Icons.Filled.ElectricBolt,
                                contentDescription = null,
                                tint = Zap,
                                modifier = Modifier.size(Sizing.actionIcon),
                            )
                        }
                    }
                }
            }
        }

        // Reposts — unchanged FlowRow grouping
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

        // Per-emoji reactions — grouped by content type
        for (group in drawerData.reactionGroups) {
            when (val content = group.displayContent) {
                is ReactionContent.Custom -> {
                    EngagementRow(
                        emojiImageUrl = content.url,
                        tint = ActionTint,
                    ) {
                        group.pubkeys.forEach { pubkey ->
                            AvatarChip(
                                pubkey = pubkey,
                                profileFlow = profileFlow,
                                lookupProfile = lookupProfile,
                                onTap = { onProfileTap(pubkey) },
                            )
                        }
                    }
                }
                is ReactionContent.Standard -> {
                    val displayEmoji = if (content.emoji == "+") "\u2764\uFE0F" else content.emoji
                    EngagementRow(
                        emojiText = displayEmoji,
                        tint = if (content.emoji == "+") Like else ActionTint,
                    ) {
                        group.pubkeys.forEach { pubkey ->
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
    }
}

/**
 * Single engagement category row — mirrors the action bar's 5-slot grid.
 * Slots 1–4: wrapping FlowRow of avatars, right-aligned.
 * Slot 5: icon/emoji centered, directly under the action bar chevron.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EngagementRow(
    icon: ImageVector? = null,
    emojiText: String? = null,
    emojiImageUrl: String? = null,
    tint: Color,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(
            modifier = Modifier.weight(4f),
            horizontalArrangement = Arrangement.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            content()
        }

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
            } else if (emojiImageUrl != null) {
                AsyncImage(
                    model = emojiImageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
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

/**
 * Zap contributor: 32dp circular avatar with a dark gradient on the lower half
 * and bold amber sats label overlaid for readability.
 */
@Composable
private fun ZapChip(
    zap: ZapDetail,
    profileFlow: ((String) -> StateFlow<UserEntity?>)?,
    lookupProfile: (suspend (String) -> UserEntity?)?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sender = zap.senderPubkey
    Box(
        modifier = modifier
            .size(Sizing.avatar)
            .clip(CircleShape)
            .then(if (sender != null) Modifier.clickable(onClick = onTap) else Modifier),
    ) {
        if (sender != null) {
            AvatarImage(
                pubkey = sender,
                picture = null,
                sizeDp = Sizing.avatar,
                profileFlow = profileFlow,
                lookupProfile = lookupProfile,
                modifier = Modifier.size(Sizing.avatar),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(Sizing.avatar)
                    .background(Color(0xFF1A1A1A), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.QuestionMark,
                    contentDescription = "Anonymous",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // Dark gradient covering lower 65% — strong enough for sats readability at 32dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.65f)
                .align(Alignment.BottomCenter)
                .background(ZapChipGradient),
        )
        Text(
            text = zap.sats.toCompactSats(),
            color = Zap,
            fontSize = AppType.caption,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 1.dp),
        )
    }
}

/** Anonymous zap aggregate: QuestionMark icon + total sats. Non-clickable. */
@Composable
private fun AnonymousZapChip(sats: Long, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(Sizing.avatar)
            .clip(CircleShape),
    ) {
        Box(
            modifier = Modifier
                .size(Sizing.avatar)
                .background(Color(0xFF1A1A1A), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.QuestionMark,
                contentDescription = "Anonymous zaps",
                tint = TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.65f)
                .align(Alignment.BottomCenter)
                .background(ZapChipGradient),
        )
        Text(
            text = sats.toCompactSats(),
            color = Zap,
            fontSize = AppType.caption,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 1.dp),
        )
    }
}
