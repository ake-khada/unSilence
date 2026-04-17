package com.unsilence.app.ui.feed

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.common.rememberFullWidthImageRequest
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.SurfaceVariant
import com.unsilence.app.ui.theme.TextSecondary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private val ArticleCardBackground = SurfaceVariant

/** Parses the first value for [key] from a NIP-23 tags JSON string. */
private fun tagValue(tagsJson: String, key: String): String? = runCatching {
    Json.parseToJsonElement(tagsJson).jsonArray
        .firstOrNull { tag ->
            tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == key
        }
        ?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content
        ?.takeIf { it.isNotBlank() }
}.getOrNull()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleCard(
    row: FeedRow,
    onClick: () -> Unit,
    onNoteClick: (String) -> Unit = {},
    onReact: () -> Unit = {},
    onRepost: () -> Unit = {},
    onQuote: (String) -> Unit = {},
    onZap: (amountSats: Long) -> Unit = {},
    onSaveNwcUri: (String) -> Unit = {},
    hasReacted: Boolean = false,
    hasReposted: Boolean = false,
    hasZapped: Boolean = false,
    isNwcConfigured: Boolean = false,
    isZapLoading: Boolean = false,
    extraZapSats: Long = 0L,
    zapFlash: NoteActionsViewModel.ZapFlashState? = null,
) {
    val title   = tagValue(row.tags, "title")
    val summary = tagValue(row.tags, "summary")
        ?: row.content.take(150).replace('\n', ' ').ifBlank { null }
    val image   = tagValue(row.tags, "image")

    val authorLabel = row.displayName ?: "${row.pubkey.take(6)}…${row.pubkey.takeLast(4)}"

    val context = LocalContext.current
    val showSnackbar = LocalShowSnackbar.current
    var showRepostMenu    by remember { mutableStateOf(false) }
    var showConnectWallet by remember { mutableStateOf(false) }
    var showZapPicker     by remember { mutableStateOf(false) }

    var zapFlashTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(zapFlash) {
        if (zapFlash != null && zapFlash.noteId == row.id && zapFlash.success) {
            zapFlashTrigger++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small)
            .clickable { onClick() },
    ) {
        // ── Author row (no background — sits on app's black) ───────────────────
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.micro, vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier          = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(Sizing.avatar).clip(CircleShape)) {
                    IdentIcon(pubkey = row.pubkey, modifier = Modifier.fillMaxSize())
                    if (!row.authorPicture.isNullOrBlank()) {
                        AsyncImage(
                            model              = rememberAvatarImageRequest(row.authorPicture, Sizing.avatar),
                            contentDescription = null,
                            modifier           = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.width(Spacing.small))
                Text(
                    text       = authorLabel,
                    color      = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = AppType.bodySmall,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier.weight(1f, fill = false),
                )
                if (!row.authorNip05.isNullOrBlank()) {
                    Spacer(Modifier.width(Spacing.micro))
                    Icon(
                        imageVector        = Icons.Filled.Verified,
                        contentDescription = "NIP-05 verified",
                        tint               = Cyan,
                        modifier           = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(Spacing.micro))
                    Text(
                        text     = nip05Domain(row.authorNip05),
                        color    = TextSecondary,
                        fontSize = AppType.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(Spacing.micro))
            Text(
                text     = articleRelativeTime(row.createdAt),
                color    = TextSecondary,
                fontSize = AppType.footnote,
            )
        }

        // ── Card body (image + text + actions — grey background with rounded corners) ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
                .background(ArticleCardBackground),
        ) {
        // ── Banner image (16:9) ──────────────────────────────────────────────
        if (!image.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model              = rememberFullWidthImageRequest(image, aspectRatio = 16f / 9f),
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )
        }

        // ── Article title ──────────────────────────────────────────────────────
        if (!title.isNullOrBlank()) {
            Text(
                text       = title,
                color      = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize   = AppType.subheading,
                lineHeight = 22.sp,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium)
                    .padding(top = Spacing.small, bottom = Spacing.micro),
            )
        }

        // ── Article summary ────────────────────────────────────────────────────
        if (!summary.isNullOrBlank()) {
            Text(
                text     = summary,
                color    = TextSecondary,
                fontSize = AppType.body,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.small),
            )
        }

        // ── Action bar ─────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ActionButton(
                    icon               = Icons.AutoMirrored.Filled.Chat,
                    count              = row.replyCount,
                    contentDescription = "Replies",
                    onClick            = { onNoteClick(row.id) },
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box {
                    ActionButton(
                        icon               = Icons.Filled.Repeat,
                        count              = row.repostCount,
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
                            onClick = { onRepost(); showRepostMenu = false },
                        )
                        DropdownMenuItem(
                            text    = { Text("Quote", color = Color.White, fontSize = AppType.body) },
                            onClick = { onQuote(row.id); showRepostMenu = false },
                        )
                    }
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ActionButton(
                    icon               = Icons.Filled.Favorite,
                    count              = row.reactionCount,
                    contentDescription = "Reactions",
                    highlighted        = hasReacted,
                    onClick            = onReact,
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ZapButton(
                    sats          = row.zapTotalSats + extraZapSats,
                    hasZapped     = hasZapped,
                    isLoading     = isZapLoading,
                    flashTrigger  = zapFlashTrigger,
                    onTap         = {
                        if (isNwcConfigured) onZap(21L) else showConnectWallet = true
                    },
                    onLongPress   = {
                        if (isNwcConfigured) showZapPicker = true else showConnectWallet = true
                    },
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ActionButton(
                    icon               = Icons.Filled.Share,
                    count              = 0,
                    contentDescription = "Share",
                    onClick            = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            putExtra(Intent.EXTRA_TEXT, "https://njump.me/${row.id}")
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, null))
                    },
                )
            }
        }
        } // end card body Column
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


private fun articleRelativeTime(createdAtSeconds: Long): String {
    val diffMs = System.currentTimeMillis() - createdAtSeconds * 1000L
    return when {
        diffMs < TimeUnit.MINUTES.toMillis(1) -> "now"
        diffMs < TimeUnit.HOURS.toMillis(1)   -> "${TimeUnit.MILLISECONDS.toMinutes(diffMs)}m"
        diffMs < TimeUnit.DAYS.toMillis(1)    -> "${TimeUnit.MILLISECONDS.toHours(diffMs)}h"
        diffMs < TimeUnit.DAYS.toMillis(7)    -> "${TimeUnit.MILLISECONDS.toDays(diffMs)}d"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(createdAtSeconds * 1000L))
    }
}
