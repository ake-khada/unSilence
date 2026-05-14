package com.unsilence.app.ui.feed

import android.content.Intent
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Zap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Full-width engagement action bar: reply · repost/quote · react · zap · share.
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
    onNoteClick: () -> Unit,
    onReact: () -> Unit,
    onRepost: () -> Unit,
    onQuote: (String) -> Unit,
    onZap: (Long) -> Unit,
    onSaveNwcUri: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val showSnackbar = LocalShowSnackbar.current
    var showRepostMenu    by remember { mutableStateOf(false) }
    var showConnectWallet by remember { mutableStateOf(false) }
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
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            EventActionButton(
                icon               = Icons.AutoMirrored.Filled.Chat,
                count              = replyCount,
                contentDescription = "Replies",
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
            EventActionButton(
                icon               = Icons.Filled.Favorite,
                count              = reactionCount,
                contentDescription = "Reactions",
                highlighted        = hasReacted,
                onClick            = onReact,
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            EventZapButton(
                sats         = zapTotalSats + extraZapSats,
                hasZapped    = hasZapped,
                isLoading    = isZapLoading,
                flashTrigger = zapFlashTrigger,
                onTap        = {
                    if (isNwcConfigured) onZap(21L) else showConnectWallet = true
                },
                onLongPress  = {
                    if (isNwcConfigured) showZapPicker = true else showConnectWallet = true
                },
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            EventActionButton(
                icon               = Icons.Filled.Share,
                count              = 0,
                contentDescription = "Share",
                onClick            = {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        putExtra(Intent.EXTRA_TEXT, "https://njump.me/$noteId")
                        type = "text/plain"
                    }
                    context.startActivity(Intent.createChooser(sendIntent, null))
                },
            )
        }
    }

    if (showConnectWallet) {
        ConnectWalletDialog(
            onConnect = { uri ->
                onSaveNwcUri(uri)
                showConnectWallet = false
            },
            onDismiss = { showConnectWallet = false },
        )
    }

    if (showZapPicker) {
        ZapAmountDialog(
            onZap = { amount ->
                onZap(amount)
                showZapPicker = false
            },
            onDismiss = { showZapPicker = false },
        )
    }
}

/** Single action bar button: vector icon + optional count. Turns Brand when [highlighted]. */
@Composable
internal fun EventActionButton(
    icon: ImageVector,
    count: Int,
    contentDescription: String,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val tint = if (highlighted) Brand else ActionTint
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
            )
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

    val baseTint = if (hasZapped) Zap else ActionTint
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
                )
            }
        }
    }
}
