package com.unsilence.app.ui.feed

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.unsilence.app.data.wallet.ZapRequest
import com.unsilence.app.ui.common.LocalOpenZapSettings
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.common.LocalZapPreferences
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Zap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Full-width engagement action bar: chevron · reply · repost/quote · react · zap · share.
 *
 * Owns the repost dropdown, zap picker/wallet dialogs, and share intent.
 * Consumes engagement state and action callbacks directly.
 */
@Composable
internal fun EventActionBar(
    noteId: String,
    replyCount: Int,
    repostCount: Int,
    reactionCount: Int,
    zapTotalSats: Long,
    hasReacted: Boolean,
    hasReposted: Boolean,
    hasZapped: Boolean,
    isNwcConfigured: Boolean,
    isZapLoading: Boolean,
    extraZapSats: Long,
    zapFlash: NoteActionsViewModel.ZapFlashState?,
    zapEnabled: Boolean = true,
    drawerOpen: Boolean = false,
    onChevronTap: () -> Unit = {},
    onCountClick: (() -> Unit)? = null,
    onNoteClick: () -> Unit,
    onComment: () -> Unit = {},
    onReact: () -> Unit,
    onReactLongPress: () -> Unit = {},
    pinnedEmojis: List<com.unsilence.app.data.memory.CustomEmoji> = emptyList(),
    onReactWithEmoji: (com.unsilence.app.data.memory.CustomEmoji) -> Unit = {},
    onRepost: () -> Unit,
    onQuote: (String) -> Unit,
    onZap: (ZapRequest) -> Unit,
    onSaveNwcUri: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val showSnackbar = LocalShowSnackbar.current
    val openZapSettings = LocalOpenZapSettings.current
    val prefs = LocalZapPreferences.current
    val firstPreset = prefs.presets.firstOrNull()
    val defaultZapAmount = firstPreset?.amountSats ?: 21L
    val defaultZapMessage = firstPreset?.message
    val defaultIsPrivate = prefs.defaultPrivate
    var showRepostMenu    by remember { mutableStateOf(false) }
    var showZapPicker     by remember { mutableStateOf(false) }

    var zapFlashTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(zapFlash) {
        if (zapFlash != null && zapFlash.noteId == noteId && zapFlash.success) {
            zapFlashTrigger++
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onNoteClick() }
            .padding(bottom = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 5 action slots: reply · repost/quote · react · zap · dropdown chevron
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            EventActionButton(
                icon               = Icons.AutoMirrored.Filled.Chat,
                count              = replyCount,
                contentDescription = "Replies",
                onClick            = onComment,
                onCountClick       = onCountClick,
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box {
                EventActionButton(
                    icon               = Icons.Filled.Repeat,
                    count              = repostCount,
                    contentDescription = "Reposts",
                    highlighted        = hasReposted,
                    onClick            = { showRepostMenu = true },
                    onCountClick       = onCountClick,
                )
                DropdownMenu(
                    expanded         = showRepostMenu,
                    onDismissRequest = { showRepostMenu = false },
                    modifier         = Modifier.background(Black),
                ) {
                    DropdownMenuItem(
                        text    = { Text("Boost", color = Color.White, fontSize = AppType.body) },
                        onClick = { onRepost(); showRepostMenu = false; showSnackbar("Boosted") },
                    )
                    DropdownMenuItem(
                        text    = { Text("Quote", color = Color.White, fontSize = AppType.body) },
                        onClick = { onQuote(noteId); showRepostMenu = false },
                    )
                }
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            EventReactButton(
                count            = reactionCount,
                hasReacted       = hasReacted,
                onCountClick     = onCountClick,
                onTap            = onReact,
                onOpenFullPicker = onReactLongPress,
                pinnedEmojis     = pinnedEmojis,
                onSelectEmoji    = onReactWithEmoji,
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            EventZapButton(
                sats         = zapTotalSats + extraZapSats,
                hasZapped    = hasZapped,
                isLoading    = isZapLoading,
                flashTrigger = zapFlashTrigger,
                enabled      = zapEnabled,
                onSatsClick  = onCountClick,
                onTap        = {
                    if (!zapEnabled) { showSnackbar("This author hasn't set up Lightning."); return@EventZapButton }
                    if (isNwcConfigured) onZap(
                        ZapRequest(defaultZapAmount, defaultZapMessage, defaultIsPrivate)
                    ) else openZapSettings()
                },
                onLongPress  = {
                    if (!zapEnabled) { showSnackbar("This author hasn't set up Lightning."); return@EventZapButton }
                    if (isNwcConfigured) showZapPicker = true else openZapSettings()
                },
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            EventActionButton(
                icon               = if (drawerOpen) Icons.Default.ExpandLess
                                     else Icons.Default.ExpandMore,
                count              = 0,
                contentDescription = if (drawerOpen) "Hide engagement" else "Show engagement",
                onClick            = onChevronTap,
            )
        }
    }

    if (showZapPicker) {
        ZapAmountDialog(
            onZap = { req ->
                onZap(req)
                showZapPicker = false
            },
            onDismiss = { showZapPicker = false },
        )
    }
}

/** Single action bar button: vector icon + optional count. Turns [highlightColor] when [highlighted]. */
@Composable
internal fun EventActionButton(
    icon: ImageVector,
    count: Int,
    contentDescription: String,
    highlighted: Boolean = false,
    highlightColor: Color = Brand,
    onClick: (() -> Unit)? = null,
    onCountClick: (() -> Unit)? = null,
) {
    val tint = if (highlighted) highlightColor else ActionTint
    val rowModifier = if (onClick != null)
        Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clickable(onClick = onClick)
    else
        Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)

    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier              = rowModifier,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = contentDescription,
            tint               = tint,
            modifier           = Modifier.size(Sizing.actionIcon),
        )
        if (count > 0) {
            Spacer(Modifier.width(Spacing.micro))
            Text(
                text     = formatCount(count),
                color    = tint,
                fontSize = AppType.footnote,
                modifier = if (onCountClick != null) Modifier.clickable(onClick = onCountClick) else Modifier,
            )
        }
    }
}

/** React button: Like-red when reacted, combinedClickable for tap (default +) and long-press (emoji picker).
 *  When [pinnedEmojis] is non-empty, long-press shows an inline [EmojiQuickStrip] Popup centered
 *  above the heart. When empty, long-press calls [onOpenFullPicker] directly. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EventReactButton(
    count: Int,
    hasReacted: Boolean,
    onCountClick: (() -> Unit)? = null,
    onTap: () -> Unit,
    onOpenFullPicker: () -> Unit,
    pinnedEmojis: List<com.unsilence.app.data.memory.CustomEmoji> = emptyList(),
    onSelectEmoji: (com.unsilence.app.data.memory.CustomEmoji) -> Unit = {},
) {
    val tint = if (hasReacted) Like else ActionTint
    var showStrip by remember { mutableStateOf(false) }

    Box {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier              = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .combinedClickable(
                    onClick     = onTap,
                    onLongClick = {
                        if (pinnedEmojis.isNotEmpty()) showStrip = true
                        else onOpenFullPicker()
                    },
                ),
        ) {
            Icon(
                imageVector        = Icons.Filled.Favorite,
                contentDescription = "Reactions",
                tint               = tint,
                modifier           = Modifier.size(Sizing.actionIcon),
            )
            if (count > 0) {
                Spacer(Modifier.width(Spacing.micro))
                Text(
                    text     = formatCount(count),
                    color    = tint,
                    fontSize = AppType.footnote,
                    modifier = if (onCountClick != null) Modifier.clickable(onClick = onCountClick) else Modifier,
                )
            }
        }

        if (showStrip) {
            androidx.compose.ui.window.Popup(
                alignment = Alignment.BottomCenter,
                onDismissRequest = { showStrip = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                EmojiQuickStrip(
                    pinnedEmojis = pinnedEmojis,
                    onSelect = { emoji ->
                        onSelectEmoji(emoji)
                        showStrip = false
                    },
                    onOpenFullPicker = {
                        showStrip = false
                        onOpenFullPicker()
                    },
                )
            }
        }
    }
}

/** Zap button: Amber when user has zapped, loading spinner during payment, energy pulse on success. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EventZapButton(
    sats: Long,
    hasZapped: Boolean,
    isLoading: Boolean = false,
    flashTrigger: Int = 0,
    enabled: Boolean = true,
    onSatsClick: (() -> Unit)? = null,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val iconScale = remember { Animatable(1f) }
    val flashAlpha = remember { Animatable(0f) }

    LaunchedEffect(flashTrigger) {
        if (flashTrigger > 0) {
            coroutineScope {
                launch {
                    iconScale.snapTo(1.8f)
                    iconScale.animateTo(
                        1f,
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                    )
                }
                launch {
                    flashAlpha.snapTo(1f)
                    flashAlpha.animateTo(0f, tween(400))
                }
            }
        }
    }

    val baseTint = if (!enabled) ActionTint.copy(alpha = 0.38f)
        else if (hasZapped) Zap else ActionTint
    val tint = if (flashAlpha.value > 0f) {
        lerp(baseTint, Color.White, flashAlpha.value)
    } else baseTint

    Box(contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier              = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .combinedClickable(
                    onClick     = onTap,
                    onLongClick = onLongPress,
                ),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color       = Brand,
                    modifier    = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector        = Icons.Filled.ElectricBolt,
                    contentDescription = "Zap",
                    tint               = tint,
                    modifier           = Modifier
                        .size(Sizing.actionIcon)
                        .graphicsLayer {
                            scaleX = iconScale.value
                            scaleY = iconScale.value
                        },
                )
            }
            if (sats > 0) {
                Spacer(Modifier.width(Spacing.micro))
                Text(
                    text     = sats.toCompactSats(),
                    color    = tint,
                    fontSize = AppType.footnote,
                    modifier = if (onSatsClick != null) Modifier.clickable(onClick = onSatsClick) else Modifier,
                )
            }
        }
    }
}
