package com.unsilence.app.ui.compose

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.unsilence.app.data.drafts.Draft
import com.unsilence.app.data.drafts.DraftContext
import com.unsilence.app.data.model.ContentParser
import com.unsilence.app.ui.feed.EmojiPickerSheet
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.ui.drafts.CloseDraftSheet
import com.unsilence.app.ui.feed.ComposePreviewCard
import com.unsilence.app.ui.feed.NoteActionsViewModel
import com.unsilence.app.ui.feed.eventCardServices
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BorderFaint
import com.unsilence.app.ui.theme.BrandDeep
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Warn
import kotlinx.coroutines.delay

internal enum class ComposerBackAction { DISMISS, CONFIRM_DISCARD }

internal fun composerBackAction(hasUnsavedDraftChanges: Boolean): ComposerBackAction =
    if (hasUnsavedDraftChanges) ComposerBackAction.CONFIRM_DISCARD else ComposerBackAction.DISMISS

@Composable
fun ComposeScreen(
    onDismiss: () -> Unit,
    replyToEventId: String? = null,
    quoteEventId: String? = null,
    articleCommentTarget: ArticleCommentTarget? = null,
    initialDraft: Draft? = null,
    viewModel: ComposeViewModel = hiltViewModel(
        key = "compose-${LocalAppSessionKey.current}",
    ),
    actionsViewModel: NoteActionsViewModel = hiltViewModel(
        key = "note-actions-${LocalAppSessionKey.current}",
    ),
) {
    val pubkeyHex      = viewModel.pubkeyHex
    val userAvatarUrl by viewModel.userAvatarUrl.collectAsStateWithLifecycle()
    val userEntity    by viewModel.userEntity.collectAsStateWithLifecycle()
    val blocks        by viewModel.blocks.collectAsStateWithLifecycle()
    val pollDraft     by viewModel.pollDraft.collectAsStateWithLifecycle()
    val canPublish    by viewModel.canPublish.collectAsStateWithLifecycle()
    val sendState     by viewModel.sendState.collectAsStateWithLifecycle()
    val hasDraftableContent by viewModel.hasDraftableContent.collectAsStateWithLifecycle()
    val hasUnsavedMedia by viewModel.hasUnsavedMedia.collectAsStateWithLifecycle()
    val hasUnsavedDraftChanges by viewModel.hasUnsavedDraftChanges.collectAsStateWithLifecycle()

    val mentionPickerOpen by viewModel.mentionPickerOpen.collectAsStateWithLifecycle()
    val mentionQuery      by viewModel.mentionQuery.collectAsStateWithLifecycle()
    val mentionFollows    by viewModel.mentionFollows.collectAsStateWithLifecycle()
    val mentionSearchResults by viewModel.mentionSearchResults.collectAsStateWithLifecycle()
    val mentionWotLookups by viewModel.mentionWotLookups.collectAsStateWithLifecycle()
    val pendingMention    by viewModel.pendingMentionInsert.collectAsStateWithLifecycle()
    val isSensitive       by viewModel.isSensitive.collectAsStateWithLifecycle()

    val emojiPickerOpen   by viewModel.emojiPickerOpen.collectAsStateWithLifecycle()
    val resolvedEmojis    by viewModel.resolvedEmojis.collectAsStateWithLifecycle()
    val emojiCategories   by viewModel.emojiCategories.collectAsStateWithLifecycle()
    val pinnedShortcodes  by viewModel.pinnedEmojiShortcodes.collectAsStateWithLifecycle()
    val pendingEmoji      by viewModel.pendingEmojiInsert.collectAsStateWithLifecycle()
    val showSnackbar = LocalShowSnackbar.current
    val previewServices = remember(actionsViewModel) { actionsViewModel.eventCardServices() }

    // Article comments preview/post like a reply (parent card + "Replying" chrome);
    // the VM routes to the NIP-22 kind-1111 path via articleCommentTarget.
    val initialContext = initialDraft?.context
    val effectiveArticleTarget = articleCommentTarget
        ?: (initialContext as? DraftContext.ArticleComment)?.toArticleCommentTarget()
    val effectiveReplyToEventId = replyToEventId
        ?: (initialContext as? DraftContext.Reply)?.parentId
    val effectiveQuoteEventId = quoteEventId
        ?: (initialContext as? DraftContext.Quote)?.eventId
    val composeSessionKey = remember(
        effectiveReplyToEventId,
        effectiveQuoteEventId,
        effectiveArticleTarget,
        initialDraft?.key,
    ) {
        when {
            initialDraft != null -> "draft:${initialDraft.key}"
            effectiveArticleTarget != null ->
                "article:${effectiveArticleTarget.articleCoord}:${effectiveArticleTarget.parentId ?: "root"}"
            effectiveReplyToEventId != null -> "reply:$effectiveReplyToEventId"
            effectiveQuoteEventId != null -> "quote:$effectiveQuoteEventId"
            else -> "new"
        }
    }
    val isReply = effectiveReplyToEventId != null || effectiveArticleTarget != null
    val isQuote = effectiveQuoteEventId != null
    val replyToRow = viewModel.replyToRow
    val replyToModel = viewModel.replyToModel
    val quoteRow = viewModel.quoteRow
    val quoteModel = viewModel.quoteModel
    val isConfirming = sendState is SendState.Confirming
    val isPublishing = sendState is SendState.Publishing
    val isFailed = sendState is SendState.Failed
    val keyboardController = LocalSoftwareKeyboardController.current
    var showCloseDraftSheet by remember { mutableStateOf(false) }
    var saveNotice by remember { mutableStateOf<String?>(null) }
    var initialized by remember(
        effectiveReplyToEventId,
        effectiveQuoteEventId,
        effectiveArticleTarget,
        initialDraft?.key,
    ) { mutableStateOf(false) }

    fun finishAndDismiss() {
        viewModel.finishComposeSession(composeSessionKey)
        onDismiss()
    }

    fun requestDismiss() {
        when {
            isConfirming -> viewModel.cancelSend()
            hasUnsavedDraftChanges && !isPublishing -> showCloseDraftSheet = true
            else -> finishAndDismiss()
        }
    }

    // Initialize once per explicit compose session. Reattaching the composition
    // after an Activity recreation must not reset the activity-scoped editor VM.
    LaunchedEffect(composeSessionKey) {
        val beginsNewSession = viewModel.beginComposeSession(composeSessionKey)
        if (beginsNewSession) {
            viewModel.reset()
        }
        // Reference models are cheap to rebind and are not stored in saved state.
        if (effectiveReplyToEventId != null) viewModel.loadReplyTo(effectiveReplyToEventId)
        if (effectiveQuoteEventId != null) viewModel.loadQuoteTo(effectiveQuoteEventId)
        if (effectiveArticleTarget != null) viewModel.loadArticleComment(effectiveArticleTarget)
        if (beginsNewSession && initialDraft != null) viewModel.restoreDraft(initialDraft)
        initialized = true
    }

    // Auto-dismiss once the note is published
    LaunchedEffect(viewModel.published, initialized) {
        if (initialized && viewModel.published) finishAndDismiss()
    }

    // Dismiss on Sent state
    LaunchedEffect(sendState, initialized) {
        if (initialized && sendState is SendState.Sent) finishAndDismiss()
        if (sendState is SendState.Confirming) keyboardController?.hide()
    }

    // System back during confirm → cancel (blocked during publishing)
    BackHandler(enabled = isConfirming || isFailed) {
        viewModel.cancelSend()
    }

    // Always consume system back while composing. A dirty editor asks before
    // discarding; an untouched editor closes immediately. Leaving this handler
    // disabled for an empty editor lets back fall through to the tab beneath the
    // full-screen composer instead of dismissing it.
    BackHandler(enabled = sendState is SendState.Composing) {
        when (composerBackAction(hasUnsavedDraftChanges)) {
            ComposerBackAction.CONFIRM_DISCARD -> showCloseDraftSheet = true
            ComposerBackAction.DISMISS -> finishAndDismiss()
        }
    }

    LaunchedEffect(saveNotice) {
        if (saveNotice != null) {
            delay(1_800)
            saveNotice = null
        }
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
                        pollDraft.enabled -> "New poll"
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
                        requestDismiss()
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
            if (isReply && replyToRow != null && !isConfirming) {
                val parentPubkey = replyToModel?.pubkey ?: replyToRow.pubkey
                val rowDescribesParent = replyToRow.pubkey == parentPubkey
                val parentName = replyToRow.authorDisplayName
                    ?.takeIf { rowDescribesParent && it.isNotBlank() }
                    ?: replyToRow.authorName?.takeIf { rowDescribesParent && it.isNotBlank() }
                    ?: "${parentPubkey.take(6)}…${parentPubkey.takeLast(4)}"
                val parentText = replyToModel?.displayContent
                    ?: replyToRow.content.takeIf { replyToRow.kind != 6 && replyToRow.kind != 16 }
                    ?: "Post unavailable"
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
                        IdentIcon(pubkey = parentPubkey, modifier = Modifier.size(24.dp))
                        if (rowDescribesParent && !replyToRow.authorPicture.isNullOrBlank()) {
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
                            text       = parentText.take(140),
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
                                                pollDraft.enabled -> "Ask a question\u2026"
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

                        if (pollDraft.enabled) {
                            PollComposer(
                                poll = pollDraft,
                                onOptionChange = viewModel::updatePollOption,
                                onAddOption = viewModel::addPollOption,
                                onRemoveOption = viewModel::removePollOption,
                                onMultipleChoiceChange = viewModel::setPollMultipleChoice,
                                onDurationChange = viewModel::setPollDuration,
                            )
                        }

                        // ── Quote preview card ──────────────────────────────
                        if (isQuote && quoteRow != null) {
                            val quotePubkey = quoteModel?.pubkey ?: quoteRow.pubkey
                            val rowDescribesQuoteAuthor = quoteRow.pubkey == quotePubkey
                            val quoteName = quoteRow.authorDisplayName
                                ?.takeIf { rowDescribesQuoteAuthor && it.isNotBlank() }
                                ?: quoteRow.authorName
                                    ?.takeIf { rowDescribesQuoteAuthor && it.isNotBlank() }
                                ?: "${quotePubkey.take(6)}…${quotePubkey.takeLast(4)}"
                            val quoteText = quoteModel?.displayContent
                                ?: quoteRow.content.takeIf { quoteRow.kind != 6 && quoteRow.kind != 16 }
                                ?: "Post unavailable"
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
                                    IdentIcon(pubkey = quotePubkey, modifier = Modifier.size(24.dp))
                                    if (rowDescribesQuoteAuthor && !quoteRow.authorPicture.isNullOrBlank()) {
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
                                        text       = quoteText.take(200),
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

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .padding(horizontal = Spacing.medium),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        saveNotice?.let { message ->
                            Text(
                                text = message,
                                color = if (message == "Draft saved") Mint else TextSecondary,
                                fontSize = AppType.caption,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // ── Action row (pinned bottom, keyboard-aware) ──────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(
                                WindowInsets.ime.union(WindowInsets.navigationBars)
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
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

                        if (!isReply && !isQuote) {
                            IconButton(
                                onClick = viewModel::togglePoll,
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Poll,
                                    contentDescription = if (pollDraft.enabled) "Remove poll" else "Create poll",
                                    tint = if (pollDraft.enabled) BrandDeep else TextSecondary.copy(alpha = 0.65f),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }

                        IconButton(
                            onClick = { viewModel.toggleSensitive() },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.WarningAmber,
                                contentDescription = if (isSensitive) "Marked sensitive" else "Mark as sensitive",
                                tint = if (isSensitive) Warn else TextSecondary.copy(alpha = 0.5f),
                                modifier = Modifier.size(24.dp),
                            )
                        }

                        IconButton(
                            onClick = {
                                saveNotice = if (viewModel.saveCurrentDraft()) "Draft saved" else "Nothing to save"
                            },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Save,
                                contentDescription = "Save draft",
                                tint = if (hasDraftableContent) BrandDeep else TextSecondary.copy(alpha = 0.55f),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        }

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
                                color = if (typedChars > 9000) Warn else TextSecondary,
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
                    // The card renders the exact immutable payload that will be signed.
                    val previewModel = remember(state.payload) {
                        runCatching {
                            ContentParser.parse(
                                id = "preview",
                                pubkey = pubkeyHex ?: "",
                                kind = state.payload.kind,
                                content = state.payload.content,
                                tagsJson = state.payload.tagsJson(),
                                createdAt = state.payload.createdAt,
                                relayUrl = "",
                                replyToId = state.payload.replyToId,
                                rootId = state.payload.rootId,
                                hasContentWarning = state.payload.hasContentWarning,
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
                        state.payload.threadedReplyTargetId()?.let {
                            val replyPubkey = when (val target = state.payloadState.target) {
                                is PublishTarget.Reply -> target.parentPubkey
                                is PublishTarget.ArticleComment ->
                                    target.target.parentPubkey ?: target.target.articlePubkey
                                else -> null
                            }
                            val replyName = replyToRow?.let { parent ->
                                parent.authorDisplayName?.takeIf(String::isNotBlank)
                                    ?: parent.authorName?.takeIf(String::isNotBlank)
                                    ?: "${parent.pubkey.take(6)}…${parent.pubkey.takeLast(4)}"
                            } ?: state.notifyCandidates
                                .firstOrNull { it.source == NotifySource.ReplyParent }
                                ?.displayName
                                ?.takeIf(String::isNotBlank)
                                ?: replyPubkey?.let { "${it.take(6)}…${it.takeLast(4)}" }
                                ?: "note"
                            Text(
                                text = "Replying to @${replyName.removePrefix("@")}",
                                color = BrandDeep,
                                fontSize = AppType.caption,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(
                                    horizontal = Spacing.medium,
                                    vertical = Spacing.small,
                                ),
                            )
                        }
                        if (state.payload.hasContentWarning) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.medium, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.WarningAmber,
                                    contentDescription = null,
                                    tint = Warn,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Marked sensitive \u2014 viewers see a blur until tap",
                                    color = Warn,
                                    fontSize = AppType.caption,
                                )
                            }
                        }
                        if (previewModel != null && pubkeyHex != null) {
                            ComposePreviewCard(
                                model = previewModel,
                                ownPubkey = pubkeyHex,
                                ownProfile = userEntity,
                                services = previewServices,
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
                            .windowInsetsPadding(
                                WindowInsets.ime.union(WindowInsets.navigationBars)
                            )
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
                            .windowInsetsPadding(WindowInsets.navigationBars)
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
            wotLookups = mentionWotLookups,
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

    if (showCloseDraftSheet) {
        CloseDraftSheet(
            hasDraftableContent = hasDraftableContent,
            hasUnsavedMedia = hasUnsavedMedia,
            onSaveDraft = {
                val saved = viewModel.saveCurrentDraft()
                showCloseDraftSheet = false
                if (saved) showSnackbar("Draft saved")
                finishAndDismiss()
            },
            onDiscard = {
                viewModel.discardCurrentDraft()
                showCloseDraftSheet = false
                finishAndDismiss()
            },
            onContinueEditing = { showCloseDraftSheet = false },
        )
    }
}

@Composable
private fun PollComposer(
    poll: PollDraft,
    onOptionChange: (String, String) -> Unit,
    onAddOption: () -> Unit,
    onRemoveOption: (String) -> Unit,
    onMultipleChoiceChange: (Boolean) -> Unit,
    onDurationChange: (Long?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, BorderFaint, RoundedCornerShape(6.dp)),
        ) {
            listOf(false to "Single choice", true to "Multiple choice").forEach { (multiple, label) ->
                val selected = poll.multipleChoice == multiple
                Surface(
                    onClick = { onMultipleChoiceChange(multiple) },
                    color = if (selected) BrandDeep.copy(alpha = 0.16f) else Color.Transparent,
                    modifier = Modifier.weight(1f).height(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = label,
                            color = if (selected) BrandDeep else TextSecondary,
                            fontSize = AppType.caption,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        Text(
            text = "Duration",
            color = TextSecondary,
            fontSize = AppType.caption,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, BorderFaint, RoundedCornerShape(6.dp)),
        ) {
            listOf(
                null to "None",
                86_400L to "1d",
                259_200L to "3d",
                604_800L to "7d",
            ).forEach { (seconds, label) ->
                val selected = poll.durationSeconds == seconds
                Surface(
                    onClick = { onDurationChange(seconds) },
                    color = if (selected) BrandDeep.copy(alpha = 0.16f) else Color.Transparent,
                    modifier = Modifier.weight(1f).height(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = label,
                            color = if (selected) BrandDeep else TextSecondary,
                            fontSize = AppType.caption,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        poll.options.forEachIndexed { index, option ->
            OutlinedTextField(
                value = option.label,
                onValueChange = { onOptionChange(option.id, it) },
                placeholder = { Text("Option ${index + 1}") },
                singleLine = true,
                shape = RoundedCornerShape(6.dp),
                trailingIcon = if (poll.options.size > 2) {
                    {
                        IconButton(onClick = { onRemoveOption(option.id) }) {
                            Icon(Icons.Outlined.DeleteOutline, "Remove option", tint = TextSecondary)
                        }
                    }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (poll.options.size < 10) {
            TextButton(onClick = onAddOption) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add option")
            }
        }
    }
}

private fun DraftContext.ArticleComment.toArticleCommentTarget(): ArticleCommentTarget =
    ArticleCommentTarget(
        articleId = articleId,
        articleCoord = articleCoord,
        articlePubkey = articlePubkey,
        articleRelayHint = articleRelayHint,
        parentId = parentId,
        parentKind = parentKind,
        parentPubkey = parentPubkey,
        parentRelayHint = parentRelayHint,
    )

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
                    RelayPublishStatus.TimedOut -> Icons.Filled.ErrorOutline to Warn
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
