package com.unsilence.app.ui.compose

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.unsilence.app.data.model.ContentParser
import com.unsilence.app.ui.feed.EmojiPickerSheet
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.ui.feed.ComposePreviewCard
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BrandDeep
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Mint
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
    val sendState     by viewModel.sendState.collectAsStateWithLifecycle()

    val mentionPickerOpen by viewModel.mentionPickerOpen.collectAsStateWithLifecycle()
    val mentionQuery      by viewModel.mentionQuery.collectAsStateWithLifecycle()
    val mentionFollows    by viewModel.mentionFollows.collectAsStateWithLifecycle()
    val mentionSearchResults by viewModel.mentionSearchResults.collectAsStateWithLifecycle()
    val pendingMention    by viewModel.pendingMentionInsert.collectAsStateWithLifecycle()
    val isSensitive       by viewModel.isSensitive.collectAsStateWithLifecycle()

    val emojiPickerOpen   by viewModel.emojiPickerOpen.collectAsStateWithLifecycle()
    val resolvedEmojis    by viewModel.resolvedEmojis.collectAsStateWithLifecycle()
    val emojiCategories   by viewModel.emojiCategories.collectAsStateWithLifecycle()
    val pinnedShortcodes  by viewModel.pinnedEmojiShortcodes.collectAsStateWithLifecycle()
    val pendingEmoji      by viewModel.pendingEmojiInsert.collectAsStateWithLifecycle()

    val isReply = replyToEventId != null
    val isQuote = quoteEventId != null
    val replyToRow = viewModel.replyToRow
    val quoteRow = viewModel.quoteRow
    val isConfirming = sendState is SendState.Confirming
    val isPublishing = sendState is SendState.Publishing
    val isFailed = sendState is SendState.Failed
    val keyboardController = LocalSoftwareKeyboardController.current

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

    // Dismiss on Sent state
    LaunchedEffect(sendState) {
        if (sendState is SendState.Sent) onDismiss()
        if (sendState is SendState.Confirming) keyboardController?.hide()
    }

    // System back during confirm → cancel (blocked during publishing)
    BackHandler(enabled = isConfirming || isFailed) {
        viewModel.cancelSend()
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
                        isPublishing -> "Publishing"
                        isFailed -> "Failed"
                        isConfirming -> "Confirm"
                        isReply -> "Replying"
                        isQuote -> "Quoting"
                        else    -> "New note"
                    },
                    color = if (isFailed) Like else TextSecondary,
                    fontSize = AppType.body,
                    letterSpacing = 0.5.sp,
                )

                Spacer(Modifier.weight(1f))

                // No close button during Publishing — event already broadcast
                if (!isPublishing) {
                    IconButton(onClick = {
                        if (isConfirming) viewModel.cancelSend() else onDismiss()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
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

            // ── Body area: Composing vs Confirming ──────────────────────────
            when (val state = sendState) {
                is SendState.Composing -> {
                    // ── Author header ────────────────────────────────────────
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

                    // ── Block list ──────────────────────────────────────────
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
                                        val mentionForBlock = pendingMention
                                            ?.takeIf { it.first == block.id }?.second
                                        val emojiForBlock = pendingEmoji
                                            ?.takeIf { it.first == block.id }?.second
                                        val insertForBlock = mentionForBlock ?: emojiForBlock
                                        TextBlock(
                                            blockId = block.id,
                                            initialText = block.content,
                                            onTextChange = { viewModel.updateTextBlock(block.id, it) },
                                            onFocused = { viewModel.setFocusedBlock(it) },
                                            pendingInsert = insertForBlock,
                                            onInsertConsumed = {
                                                if (mentionForBlock != null) viewModel.consumeMentionInsert()
                                                else viewModel.consumeEmojiInsert()
                                            },
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
                                            onUpload = { viewModel.startUpload(block.state.id) },
                                            onQualityChange = { quality ->
                                                viewModel.updateAttachmentQuality(block.state.id, quality)
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }

                        // ── Quote preview card ──────────────────────────────
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

                    // ── Action row (pinned bottom, keyboard-aware) ──────────
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

                        IconButton(
                            onClick = { viewModel.openMentionPicker() },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AlternateEmail,
                                contentDescription = "Mention",
                                tint = BrandDeep,
                                modifier = Modifier.size(24.dp),
                            )
                        }

                        IconButton(
                            onClick = { viewModel.openEmojiPicker() },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SentimentSatisfied,
                                contentDescription = "Insert emoji",
                                tint = BrandDeep,
                                modifier = Modifier.size(24.dp),
                            )
                        }

                        IconButton(
                            onClick = { viewModel.toggleSensitive() },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.WarningAmber,
                                contentDescription = if (isSensitive) "Marked sensitive" else "Mark as sensitive",
                                tint = if (isSensitive) Zap else TextSecondary.copy(alpha = 0.5f),
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
                            onClick = { viewModel.requestPublish(isReply) },
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
                }

                is SendState.Confirming -> {
                    // ── Preview via ContentFlow ─────────────────────────────
                    val previewModel = remember(state) {
                        runCatching {
                            ContentParser.parse(
                                id = "preview",
                                pubkey = pubkeyHex ?: "",
                                kind = 1,
                                content = state.previewContent,
                                tagsJson = state.previewTagsJson,
                                createdAt = System.currentTimeMillis() / 1000,
                                relayUrl = "",
                                replyToId = replyToEventId,
                                rootId = null,
                                hasContentWarning = false,
                                contentWarningReason = null,
                            )
                        }.getOrNull()
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (isSensitive) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.medium, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.WarningAmber,
                                    contentDescription = null,
                                    tint = Zap,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Marked sensitive \u2014 viewers see a blur until tap",
                                    color = Zap,
                                    fontSize = AppType.caption,
                                )
                            }
                        }
                        if (previewModel != null && pubkeyHex != null) {
                            ComposePreviewCard(
                                model = previewModel,
                                ownPubkey = pubkeyHex,
                                ownProfile = userEntity,
                                lookupProfile = { viewModel.lookupProfile(it) },
                                lookupEvent = { id, _ -> viewModel.lookupEvent(id) },
                                lookupModel = { viewModel.lookupModel(it) },
                                fetchOgMetadata = { viewModel.fetchOgMetadata(it) },
                                imageDimensionCache = viewModel.imageDimensionCache,
                                thumbnailCache = viewModel.videoThumbnailCache,
                            )
                        } else {
                            Text(
                                text = "Preview unavailable",
                                color = TextSecondary,
                                modifier = Modifier.padding(Spacing.medium),
                            )
                        }
                    }

                    // ── Notify-whom toggle row ────────────────────────────
                    NotifyToggleRow(
                        candidates = state.notifyCandidates,
                        active = state.notifyActive,
                        onToggle = { viewModel.toggleNotify(it) },
                    )

                    // ── Confirm + Cancel row ───────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Cancel pill (outline)
                        Surface(
                            onClick = { viewModel.cancelSend() },
                            shape = RoundedCornerShape(24.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, BrandDeep),
                            modifier = Modifier.height(36.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 22.dp),
                            ) {
                                Text("Cancel", color = BrandDeep, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        // Confirm pill (filled)
                        Button(
                            onClick = { viewModel.confirmPublish() },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BrandDeep,
                                contentColor = Color.Black,
                            ),
                            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = "Confirm",
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
                }

                is SendState.Publishing -> {
                    PublishStatusPanel(
                        statuses = state.statuses,
                        modifier = Modifier.weight(1f),
                    )
                }

                is SendState.Failed -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(Spacing.medium),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = state.reason,
                            color = Like,
                            fontSize = AppType.body,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(Spacing.large))
                        Button(
                            onClick = { viewModel.retryPublish() },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BrandDeep,
                                contentColor = Color.Black,
                            ),
                            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
                        ) {
                            Text("Retry", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                is SendState.Sent -> {
                    // Brief — navigation observer will pop
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    // ── Mention picker sheet ────────────────────────────────────────────
    if (mentionPickerOpen) {
        MentionPickerSheet(
            follows = mentionFollows,
            searchResults = mentionSearchResults,
            query = mentionQuery,
            onQueryChange = viewModel::setMentionQuery,
            onSelect = viewModel::selectMention,
            onDismiss = viewModel::closeMentionPicker,
        )
    }

    // ── Emoji picker sheet ──────────────────────────────────────────────
    if (emojiPickerOpen) {
        EmojiPickerSheet(
            emojis = resolvedEmojis,
            pinnedShortcodes = pinnedShortcodes,
            onSelect = viewModel::selectEmoji,
            onTogglePin = viewModel::toggleEmojiPin,
            onOpenSettings = com.unsilence.app.ui.common.LocalOpenEmojiSettings.current,
            onDismiss = viewModel::closeEmojiPicker,
            categories = emojiCategories,
        )
    }
}

// ── Notify-whom toggle ──────────────────────────────────────────────────

@Composable
private fun NotifyToggleRow(
    candidates: List<NotifyCandidate>,
    active: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (candidates.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        Text(
            text = "Notify",
            color = TextSecondary,
            fontSize = AppType.caption,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(candidates, key = { it.pubkey }) { candidate ->
                NotifyChip(
                    candidate = candidate,
                    isActive = candidate.pubkey in active,
                    onClick = { onToggle(candidate.pubkey) },
                )
            }
        }
    }
}

@Composable
private fun NotifyChip(
    candidate: NotifyCandidate,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val displayName = candidate.displayName
        ?: "${candidate.pubkey.take(6)}\u2026${candidate.pubkey.takeLast(4)}"
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (isActive) BrandDeep.copy(alpha = 0.18f)
                else Color.Transparent
            )
            .border(
                width = 0.5.dp,
                color = if (isActive) BrandDeep
                        else Color.White.copy(alpha = 0.18f),
                shape = RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(20.dp).clip(CircleShape)) {
            IdentIcon(pubkey = candidate.pubkey, modifier = Modifier.size(20.dp))
            if (!candidate.picture.isNullOrBlank()) {
                AsyncImage(
                    model = rememberAvatarImageRequest(candidate.picture, 20.dp),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (isActive) 1f else 0.4f),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = displayName,
            color = if (isActive) Color.White else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
            textDecoration = if (isActive) null else TextDecoration.LineThrough,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp),
        )
    }
}

// ── Publish status panel ───────────────────────────────────────────────────

@Composable
private fun PublishStatusPanel(
    statuses: Map<String, RelayPublishStatus>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.medium),
    ) {
        val accepted = statuses.values.count { it == RelayPublishStatus.Accepted }
        val total = statuses.size
        Text(
            text = if (accepted == 0) "Publishing to $total relays\u2026"
                   else "Published to $accepted of $total relays",
            color = Color.White,
            fontSize = AppType.body,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(Spacing.medium))
        statuses.entries.sortedBy { it.key }.forEach { (relay, status) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 1.dp),
            ) {
                val (icon, tint) = when (status) {
                    RelayPublishStatus.Pending  -> Icons.Filled.HourglassEmpty to TextSecondary
                    RelayPublishStatus.Accepted -> Icons.Filled.Check to Mint
                    RelayPublishStatus.Rejected -> Icons.Filled.Close to Like
                    RelayPublishStatus.TimedOut -> Icons.Filled.ErrorOutline to Zap
                }
                Icon(icon, contentDescription = null, tint = tint,
                    modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = compactRelayName(relay),
                    color = TextSecondary,
                    fontSize = AppType.caption,
                )
            }
        }
    }
}

/** Strip "wss://" prefix for compact relay display. */
private fun compactRelayName(url: String): String =
    url.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
