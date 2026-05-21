package com.unsilence.app.ui.compose

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BrandDeep
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap

@Composable
fun ComposeScreen(
    onDismiss: () -> Unit,
    replyToEventId: String? = null,
    quoteEventId: String? = null,
    viewModel: ComposeViewModel = hiltViewModel(),
) {
    val pubkeyHex      = viewModel.pubkeyHex
    val userAvatarUrl by viewModel.userAvatarUrl.collectAsStateWithLifecycle()
    val userEntity    by viewModel.userEntity.collectAsStateWithLifecycle()
    val blocks        by viewModel.blocks.collectAsStateWithLifecycle()
    val canPublish    by viewModel.canPublish.collectAsStateWithLifecycle()

    val isReply = replyToEventId != null
    val isQuote = quoteEventId != null
    val replyToRow = viewModel.replyToRow
    val quoteRow = viewModel.quoteRow

    // Reset ViewModel state on open (activity-scoped VM survives recomposition)
    LaunchedEffect(Unit) {
        viewModel.reset()
        if (replyToEventId != null) viewModel.loadReplyTo(replyToEventId)
        if (quoteEventId != null) viewModel.loadQuoteTo(quoteEventId)
    }

    // Auto-dismiss once the note is published
    LaunchedEffect(viewModel.published) {
        if (viewModel.published) onDismiss()
    }

    // ── Photo picker ────────────────────────────────────────────────────────
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 8),
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addAttachments(uris)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Sizing.topBarHeight)
                    .padding(start = Spacing.medium, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        isReply -> "Replying"
                        isQuote -> "Quoting"
                        else    -> "New note"
                    },
                    color = TextSecondary,
                    fontSize = AppType.body,
                    letterSpacing = 0.5.sp,
                )

                Spacer(Modifier.weight(1f))

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = TextSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            // ── Reply context strip (cyan left border) ────────────────────────
            if (isReply && replyToRow != null) {
                val parentName = replyToRow.authorDisplayName?.takeIf { it.isNotBlank() }
                    ?: replyToRow.authorName?.takeIf { it.isNotBlank() }
                    ?: "${replyToRow.pubkey.take(6)}…${replyToRow.pubkey.takeLast(4)}"
                val borderColor = BrandDeep
                Row(
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(0.dp, 8.dp, 8.dp, 0.dp))
                        .background(BrandDeep.copy(alpha = 0.06f))
                        .drawBehind {
                            drawRect(
                                color = borderColor,
                                topLeft = Offset.Zero,
                                size = Size(2.dp.toPx(), size.height),
                            )
                        }
                        .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 10.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape),
                    ) {
                        IdentIcon(pubkey = replyToRow.pubkey, modifier = Modifier.size(24.dp))
                        if (!replyToRow.authorPicture.isNullOrBlank()) {
                            AsyncImage(
                                model              = rememberAvatarImageRequest(replyToRow.authorPicture, 24.dp),
                                contentDescription = null,
                                contentScale       = ContentScale.Crop,
                                modifier           = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text     = parentName,
                            color    = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text       = replyToRow.content.take(140),
                            color      = Color.White.copy(alpha = 0.82f),
                            fontSize   = 13.sp,
                            lineHeight = 18.sp,
                            maxLines   = 2,
                            overflow   = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // ── Author header ────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(Sizing.avatar)
                        .clip(CircleShape),
                ) {
                    if (pubkeyHex != null) {
                        IdentIcon(pubkey = pubkeyHex, modifier = Modifier.size(Sizing.avatar))
                    } else {
                        Box(modifier = Modifier.size(Sizing.avatar).background(Color(0xFF333333)))
                    }
                    if (!userAvatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model              = rememberAvatarImageRequest(userAvatarUrl, Sizing.avatar),
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize(),
                        )
                    }
                }

                Spacer(Modifier.width(Spacing.small))

                Column {
                    val displayName = userEntity?.displayName?.takeIf { it.isNotBlank() }
                        ?: userEntity?.name?.takeIf { it.isNotBlank() }
                        ?: pubkeyHex?.let { "${it.take(6)}…${it.takeLast(4)}" } ?: ""
                    Text(
                        text       = displayName,
                        color      = Color.White,
                        fontSize   = AppType.body,
                        fontWeight = FontWeight.SemiBold,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                    )
                    val nip05 = userEntity?.nip05
                    if (!nip05.isNullOrBlank()) {
                        Text(
                            text     = nip05,
                            color    = TextSecondary,
                            fontSize = AppType.caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // ── Block list ──────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                val firstTextId = blocks.firstOrNull { it is ComposeBlock.Text }?.id
                blocks.forEach { block ->
                    key(block.id) {
                        when (block) {
                            is ComposeBlock.Text -> {
                                TextBlock(
                                    initialText = block.content,
                                    onTextChange = { viewModel.updateTextBlock(block.id, it) },
                                    autoFocus = true,
                                    placeholder = when {
                                        block.id != firstTextId -> ""
                                        isReply -> "Write your reply\u2026"
                                        isQuote -> "Add your comment\u2026"
                                        else -> "Break the silence..."
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            is ComposeBlock.Attachment -> {
                                AttachmentBlock(
                                    state = block.state,
                                    onRemove = { viewModel.removeAttachment(block.state.id) },
                                    onRetry = { viewModel.retryAttachment(block.state.id) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                // ── Quote preview card ──────────────────────────────────────
                if (isQuote && quoteRow != null) {
                    val quoteName = quoteRow.authorDisplayName?.takeIf { it.isNotBlank() }
                        ?: quoteRow.authorName?.takeIf { it.isNotBlank() }
                        ?: "${quoteRow.pubkey.take(6)}…${quoteRow.pubkey.takeLast(4)}"
                    val borderColor = BrandDeep
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(0.dp, 8.dp, 8.dp, 0.dp))
                            .background(BrandDeep.copy(alpha = 0.06f))
                            .drawBehind {
                                drawRect(
                                    color = borderColor,
                                    topLeft = Offset.Zero,
                                    size = Size(2.dp.toPx(), size.height),
                                )
                            }
                            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 10.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape),
                        ) {
                            IdentIcon(pubkey = quoteRow.pubkey, modifier = Modifier.size(24.dp))
                            if (!quoteRow.authorPicture.isNullOrBlank()) {
                                AsyncImage(
                                    model              = rememberAvatarImageRequest(quoteRow.authorPicture, 24.dp),
                                    contentDescription = null,
                                    contentScale       = ContentScale.Crop,
                                    modifier           = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text     = quoteName,
                                color    = TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text       = quoteRow.content.take(200),
                                color      = Color.White.copy(alpha = 0.82f),
                                fontSize   = 13.sp,
                                lineHeight = 18.sp,
                                maxLines   = 3,
                                overflow   = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // ── Action row (pinned bottom, keyboard-aware) ─────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = {
                        pickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.AddPhotoAlternate,
                        contentDescription = "Add media",
                        tint = BrandDeep,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Spacer(Modifier.weight(1f))

                // Char counter — visible only past 280 chars
                val typedChars by remember(blocks) {
                    derivedStateOf {
                        blocks.filterIsInstance<ComposeBlock.Text>()
                            .sumOf { it.content.length }
                    }
                }
                if (typedChars > 280) {
                    Text(
                        text = "$typedChars",
                        color = if (typedChars > 9000) Zap else TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }

                // Post pill
                Button(
                    onClick = {
                        if (isReply) viewModel.publishReply() else viewModel.publishNote()
                    },
                    enabled = canPublish,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandDeep,
                        contentColor = Color.Black,
                        disabledContainerColor = BrandDeep.copy(alpha = 0.3f),
                        disabledContentColor = Color.Black.copy(alpha = 0.5f),
                    ),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "Post",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.ArrowUpward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // ── Error banner ─────────────────────────────────────────────────
            viewModel.publishError?.let { error ->
                Text(
                    text     = error,
                    color    = Color(0xFFFF5252),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                )
            }
        }
    }
}
