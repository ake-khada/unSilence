package com.unsilence.app.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import coil3.compose.AsyncImage
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.toEventModel
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.common.ShimmerNoteCard
import com.unsilence.app.ui.feed.ArticleReaderScreen
import com.unsilence.app.ui.feed.EventCard
import com.unsilence.app.ui.feed.FullScreenVideoDialog
import com.unsilence.app.ui.feed.NoteActionsViewModel
import com.unsilence.app.ui.feed.engagementId
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.shared.EngagementSnapshot
import com.unsilence.app.ui.shared.PostActionsHost
import com.unsilence.app.ui.shared.forEvent
import com.unsilence.app.ui.shared.rememberVideoPlaybackScope
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BorderFaint
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample

@Composable
fun ThreadScreen(
    eventId: String,
    onDismiss: () -> Unit,
    onQuote: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onAuthorClick: (pubkey: String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {},
    viewModel: ThreadViewModel = hiltViewModel(
        key = "thread-${LocalAppSessionKey.current}-$eventId",
    ),
    actionsViewModel: NoteActionsViewModel = hiltViewModel(
        key = "note-actions-${LocalAppSessionKey.current}",
    ),
) {
    BackHandler(onBack = onDismiss)
    DisposableEffect(Unit) { onDispose { viewModel.clearThread() } }
    LaunchedEffect(eventId) { viewModel.loadThread(eventId) }
    val state           by viewModel.uiState.collectAsStateWithLifecycle()
    val sensitiveMode   by viewModel.sensitiveContentMode.collectAsStateWithLifecycle()
    val wotLookups      by viewModel.wotLookups.collectAsStateWithLifecycle()
    val feedWotDisplayMode by viewModel.feedWotDisplayMode.collectAsStateWithLifecycle()
    val reactedIds      by actionsViewModel.reactedEventIds.collectAsStateWithLifecycle()
    val repostedIds     by actionsViewModel.repostedEventIds.collectAsStateWithLifecycle()
    val zappedIds       by actionsViewModel.zappedEventIds.collectAsStateWithLifecycle()
    val zapLoadingIds   by actionsViewModel.zapLoading.collectAsStateWithLifecycle()
    val optimisticSats  by actionsViewModel.optimisticZapSats.collectAsStateWithLifecycle()
    val zapFlash        by actionsViewModel.zapFlashState.collectAsStateWithLifecycle()
    val isNwcConfigured = actionsViewModel.isNwcConfigured
    val showSnackbar = LocalShowSnackbar.current
    var articleRow by remember { mutableStateOf<FeedRow?>(null) }
    var actionsRow by remember { mutableStateOf<FeedRow?>(null) }

    // ── Emoji reaction picker state ─────────────────────────────────────────
    var emojiReactTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showFullEmojiPicker by remember { mutableStateOf(false) }
    val openEmojiSettings = com.unsilence.app.ui.common.LocalOpenEmojiSettings.current
    val pinnedShortcodes by actionsViewModel.pinnedEmojiShortcodes.collectAsStateWithLifecycle()
    // Resolved once per screen recomposition — getPinnedEmojis() allocates a
    // fresh list per call, which defeats Compose skipping when called per card.
    val pinnedEmojis = actionsViewModel.getPinnedEmojis()
    val listState = rememberLazyListState()
    val cardWidthPx = LocalWindowInfo.current.containerSize.width
    var didScrollToFocus by remember { mutableStateOf(false) }

    // Single engagement snapshot for ALL cards in the thread — same remember
    // keys as the previous per-item snapshots, built once instead of N+1 times.
    val engagement = remember(reactedIds, repostedIds, zappedIds, isNwcConfigured, zapLoadingIds, optimisticSats, zapFlash) {
        EngagementSnapshot(
            reactedIds      = reactedIds,
            repostedIds     = repostedIds,
            zappedIds       = zappedIds,
            isNwcConfigured = isNwcConfigured,
            zapLoadingIds   = zapLoadingIds,
            optimisticZapSats = optimisticSats,
            zapFlash        = zapFlash,
        )
    }

    // ── Video playback scope ────────────────────────────────────────────────
    val allThreadRows = remember(state.focusedNote, state.replies) {
        listOfNotNull(state.focusedNote) + state.replies.map { it.row }
    }
    val videoScope = rememberVideoPlaybackScope(
        ownerId            = "thread-$eventId",
        holder             = actionsViewModel.sharedPlayerHolder,
        events             = allThreadRows,
        listState          = listState,
        videoModelProvider = actionsViewModel::getVideoRenderModels,
        cachedModelProvider = actionsViewModel::getCachedEventModel,
    )

    @OptIn(FlowPreview::class)
    LaunchedEffect(allThreadRows, state.focusedNote?.id, cardWidthPx) {
        if (allThreadRows.isEmpty()) return@LaunchedEffect
        val hasFocused = state.focusedNote != null
        val replyStartIndex = if (hasFocused) 2 else 1
        val replyDataOffset = if (hasFocused) 1 else 0
        fun visibleRowsFromLayout(): List<Int> =
            listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                val lazyIndex = item.index
                when {
                    hasFocused && lazyIndex == 0 -> 0
                    lazyIndex >= replyStartIndex -> replyDataOffset + lazyIndex - replyStartIndex
                    else -> null
                }?.takeIf { it in allThreadRows.indices }
            }

        fun warmVisibleRows(visibleRows: List<Int>) {
            val first = visibleRows.minOrNull() ?: return
            val last = visibleRows.maxOrNull() ?: first
            actionsViewModel.warmCardWindow(
                rows = allThreadRows,
                first = first,
                last = last,
                cardWidthPx = cardWidthPx,
                hydrateEngagement = true,
            )
        }

        if (cardWidthPx > 0) {
            val initialRows = visibleRowsFromLayout().ifEmpty {
                listOf(0, allThreadRows.lastIndex.coerceAtMost(8))
            }
            warmVisibleRows(initialRows)
        }

        snapshotFlow {
            visibleRowsFromLayout()
        }.sample(100).collect { visibleRows ->
            warmVisibleRows(visibleRows)
        }
    }

    // ── Action failure snackbar ──────────────────────────────────────────────
    LaunchedEffect(Unit) {
        actionsViewModel.actionError.collect { showSnackbar(it) }
    }

    // ── Scroll to focused reply (when thread opened via a deep reply) ────
    LaunchedEffect(state.focusedReplyId, state.replies) {
        val focusId = state.focusedReplyId ?: return@LaunchedEffect
        if (didScrollToFocus) return@LaunchedEffect
        val replyIdx = state.replies.indexOfFirst { it.row.id == focusId }
        if (replyIdx >= 0) {
            // Leading items: focused note (1) + reply count header (1) = 2
            listState.scrollToItem(2 + replyIdx)
            didScrollToFocus = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .background(Black)
                    .statusBarsPadding()
                    .height(Sizing.topBarHeight)
                    .padding(horizontal = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint               = Color.White,
                    )
                }
                Text(
                    text     = "Thread",
                    color    = Color.White,
                    fontSize = AppType.subheading,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)

            // ── Content ───────────────────────────────────────────────────────
            when {
                state.loading -> {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        item { ShimmerNoteCard(showMedia = true) }
                        items(2) { ShimmerNoteCard(showMedia = false) }
                    }
                }

                else -> {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
                        // Focused (OP) note — plain NoteCard, no border decoration
                        state.focusedNote?.let { note ->
                            item(key = note.id) {
                                val focusedModel = remember(note.id) {
                                    actionsViewModel.getEventModel(note.id) ?: note.toEventModel()
                                }
                                EventCard(
                                    model               = focusedModel,
                                    row                 = note,
                                    role                = if (note.kind == 30023) CardRole.Article else CardRole.Thread,
                                    engagement          = engagement.forEvent(focusedModel.engagementId),
                                    isFocused           = state.focusedReplyId == null,
                                    onNoteClick         = { /* already on thread */ },
                                    onComment           = { onComment(note.id) },
                                    onAuthorClick       = onAuthorClick,
                                    onQuote             = onQuote,
                                    onArticleClick      = { articleRow = it },
                                    onReact             = { actionsViewModel.react(note.id, note.pubkey) },
                                    onReactLongPress    = {
                                        emojiReactTarget = note.id to note.pubkey
                                        showFullEmojiPicker = true
                                    },
                                    pinnedEmojis        = pinnedEmojis,
                                    onReactWithEmoji    = { emoji ->
                                        actionsViewModel.react(note.id, note.pubkey, ":${emoji.shortcode}:", emoji.url)
                                    },
                                    onRepost            = { actionsViewModel.repost(note.id, note.pubkey, note.relayUrl) },
                                    onZap               = { req -> actionsViewModel.zap(note.id, note.pubkey, note.relayUrl, req) },
                                    onSaveNwcUri        = { uri -> actionsViewModel.saveNwcUri(uri) },
                                    lookupProfile       = actionsViewModel::lookupProfile,
                                    lookupEvent         = { id, hints -> actionsViewModel.lookupEvent(id, hints) },
                                    lookupEventWithAuthor = { id, hints, authorPk -> actionsViewModel.lookupEvent(id, hints, authorPk) },
                                    lookupModel         = actionsViewModel::getEventModel,
                                    fetchOgMetadata     = actionsViewModel::fetchOgMetadata,
                                    hasCachedOgMetadata = actionsViewModel::hasCachedOgMetadata,
                                    profileFlow         = viewModel::profileFlow,
                                    statsFlow           = viewModel::statsFlow,
                                    zapDetailsForEvent  = viewModel::zapDetailsForEvent,
                                    repostPubkeysForEvent = viewModel::repostPubkeysForEvent,
                                    reactionsForEvent   = viewModel::reactionsForEvent,
                                    imageDimensionCache = actionsViewModel.imageDimensionCache,
                                    thumbnailCache      = actionsViewModel.videoThumbnailCache,
                                    exoPlayer           = videoScope.exoPlayer,
                                    isMuted             = videoScope.isMuted,
                                    onToggleMute        = { videoScope.toggleMute() },
                                    isActiveVideo       = videoScope.isActiveVideo(note.id),
                                    activeVideoUrl      = videoScope.activeVideoUrl,
                                    isFullscreen        = videoScope.showFullscreenVideo,
                                    onOpenFullscreen    = { videoScope.openFullscreen(note.id) },
                                    onVideoModelsResolved = { models ->
                                        videoScope.registerVideoModels(note.id, models)
                                    },
                                    sensitiveMode       = sensitiveMode,
                                    isSensitive         = note.hasContentWarning,
                                    contentWarningReason = note.contentWarningReason,
                                    onLongPress         = { actionsRow = note },
                                    wotLookup           = { key -> wotLookups[key] },
                                    feedWotDisplayMode  = feedWotDisplayMode,
                                )
                                HorizontalDivider(
                                    color     = BorderFaint,
                                    thickness = 1.dp,
                                    modifier  = Modifier.padding(horizontal = Spacing.medium),
                                )
                            }
                        }

                        if (state.replies.isNotEmpty()) {
                            item {
                                Text(
                                    text     = "${state.replies.size} ${if (state.replies.size == 1) "reply" else "replies"}",
                                    color    = TextSecondary,
                                    fontSize = AppType.footnote,
                                    modifier = Modifier.padding(
                                        horizontal = Spacing.medium,
                                        vertical   = Spacing.small,
                                    ),
                                )
                            }
                            items(state.replies, key = { it.row.id }) { depthRow ->
                                val reply = depthRow.row
                                val depth = depthRow.depth
                                val indent = (depth * 12).dp
                                val lineColor = Color.White.copy(alpha = 0.10f)

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .drawBehind {
                                            for (d in 1..depth) {
                                                val x = (d * 12).dp.toPx()
                                                drawLine(
                                                    color       = lineColor,
                                                    start       = Offset(x, 0f),
                                                    end         = Offset(x, size.height),
                                                    strokeWidth = 1.dp.toPx(),
                                                )
                                            }
                                        }
                                        .padding(start = indent),
                                ) {
                                    val replyModel = remember(reply.id) {
                                        actionsViewModel.getEventModel(reply.id) ?: reply.toEventModel()
                                    }
                                    EventCard(
                                        model               = replyModel,
                                        row                 = reply,
                                        role                = CardRole.Reply,
                                        engagement          = engagement.forEvent(replyModel.engagementId),
                                        isFocused           = reply.id == state.focusedReplyId,
                                        onNoteClick         = { /* already viewing thread */ },
                                        onComment           = { onComment(reply.id) },
                                        onAuthorClick       = onAuthorClick,
                                        onQuote             = onQuote,
                                        onArticleClick      = { articleRow = it },
                                        onReact             = { actionsViewModel.react(reply.id, reply.pubkey) },
                                        onReactLongPress    = {
                                            emojiReactTarget = reply.id to reply.pubkey
                                            showFullEmojiPicker = true
                                        },
                                        pinnedEmojis        = pinnedEmojis,
                                        onReactWithEmoji    = { emoji ->
                                            actionsViewModel.react(reply.id, reply.pubkey, ":${emoji.shortcode}:", emoji.url)
                                        },
                                        onRepost            = { actionsViewModel.repost(reply.id, reply.pubkey, reply.relayUrl) },
                                        onZap               = { req -> actionsViewModel.zap(reply.id, reply.pubkey, reply.relayUrl, req) },
                                        onSaveNwcUri        = { uri -> actionsViewModel.saveNwcUri(uri) },
                                        lookupProfile       = actionsViewModel::lookupProfile,
                                        lookupEvent         = { id, hints -> actionsViewModel.lookupEvent(id, hints) },
                                        lookupEventWithAuthor = { id, hints, authorPk -> actionsViewModel.lookupEvent(id, hints, authorPk) },
                                        lookupModel         = actionsViewModel::getEventModel,
                                        fetchOgMetadata     = actionsViewModel::fetchOgMetadata,
                                        hasCachedOgMetadata = actionsViewModel::hasCachedOgMetadata,
                                        profileFlow         = viewModel::profileFlow,
                                        statsFlow           = viewModel::statsFlow,
                                        zapDetailsForEvent  = viewModel::zapDetailsForEvent,
                                        repostPubkeysForEvent = viewModel::repostPubkeysForEvent,
                                        reactionsForEvent   = viewModel::reactionsForEvent,
                                        imageDimensionCache = actionsViewModel.imageDimensionCache,
                                        thumbnailCache      = actionsViewModel.videoThumbnailCache,
                                        exoPlayer           = videoScope.exoPlayer,
                                        isMuted             = videoScope.isMuted,
                                        onToggleMute        = { videoScope.toggleMute() },
                                        isActiveVideo       = videoScope.isActiveVideo(reply.id),
                                        activeVideoUrl      = videoScope.activeVideoUrl,
                                        isFullscreen        = videoScope.showFullscreenVideo,
                                        onOpenFullscreen    = { videoScope.openFullscreen(reply.id) },
                                        onVideoModelsResolved = { models ->
                                            videoScope.registerVideoModels(reply.id, models)
                                        },
                                        sensitiveMode       = sensitiveMode,
                                        isSensitive         = reply.hasContentWarning,
                                        contentWarningReason = reply.contentWarningReason,
                                        onLongPress         = { actionsRow = reply },
                                        wotLookup           = { key -> wotLookups[key] },
                                        feedWotDisplayMode  = feedWotDisplayMode,
                                    )
                                }
                            }
                        }
                    }
                }
            }

        }
    }

    articleRow?.let { row ->
        // Effective engagement target (kind-6/16 reposts → original event).
        val model = remember(row.id, row.content, row.tags) { row.toEventModel() }
        ArticleReaderScreen(
            row             = row,
            onDismiss       = { articleRow = null },
            onQuote         = onQuote,
            onReact         = { actionsViewModel.react(model.engagementId, model.pubkey) },
            onReactLongPress = {
                emojiReactTarget = model.engagementId to model.pubkey
                showFullEmojiPicker = true
            },
            pinnedEmojis    = pinnedEmojis,
            onReactWithEmoji = { emoji ->
                actionsViewModel.react(model.engagementId, model.pubkey, ":${emoji.shortcode}:", emoji.url)
            },
            onRepost        = { actionsViewModel.repost(model.engagementId, model.pubkey, row.relayUrl) },
            onZap           = { req -> actionsViewModel.zap(model.engagementId, model.pubkey, row.relayUrl, req) },
            onSaveNwcUri    = { uri -> actionsViewModel.saveNwcUri(uri) },
            hasReacted      = row.engagementId in reactedIds,
            hasReposted     = row.engagementId in repostedIds,
            hasZapped       = row.engagementId in zappedIds,
            isNwcConfigured = isNwcConfigured,
            isZapLoading    = model.engagementId in zapLoadingIds,
            extraZapSats    = optimisticSats[model.engagementId] ?: 0L,
            zapFlash        = zapFlash,
            onAuthorClick   = onAuthorClick,
            onHashtagClick  = onHashtagClick,
            lookupProfile   = actionsViewModel::lookupProfile,
            profileFlow     = viewModel::profileFlow,
            statsFlow       = viewModel::statsFlow,
            zapDetailsForEvent    = viewModel::zapDetailsForEvent,
            repostPubkeysForEvent = viewModel::repostPubkeysForEvent,
            reactionsForEvent     = viewModel::reactionsForEvent,
        )
    }

    // ── Fullscreen video dialog ────────────────────────────────────────────
    if (videoScope.showFullscreenVideo) {
        FullScreenVideoDialog(
            exoPlayer = videoScope.exoPlayer,
            videoUrl  = videoScope.activeVideoUrl,
            onDismiss = { videoScope.dismissFullscreen() },
        )
    }

    PostActionsHost(
        row = actionsRow,
        profileFlow = viewModel::profileFlow,
        canDelete = { row -> actionsViewModel.isOwnPubkey(row.pubkey) },
        onMuteUser = actionsViewModel::muteUser,
        onReport = { row, type -> actionsViewModel.reportEvent(row.id, row.pubkey, type) },
        onDelete = { row -> actionsViewModel.deleteEvent(row.id, row.pubkey, row.relayUrl) },
        onDismiss = { actionsRow = null },
    )

    // ── Full emoji picker sheet ─────────────────────────────────────────────
    if (showFullEmojiPicker && emojiReactTarget != null) {
        val (eventId, pubkey) = emojiReactTarget!!
        com.unsilence.app.ui.feed.EmojiPickerSheet(
            emojis = actionsViewModel.getSubscribedEmojis(),
            pinnedShortcodes = pinnedShortcodes,
            onSelect = { emoji ->
                actionsViewModel.react(eventId, pubkey, ":${emoji.shortcode}:", emoji.url)
                showFullEmojiPicker = false
                emojiReactTarget = null
            },
            onTogglePin = { actionsViewModel.togglePinnedEmoji(it) },
            onOpenSettings = openEmojiSettings,
            onDismiss = {
                showFullEmojiPicker = false
                emojiReactTarget = null
            },
            categories = actionsViewModel.getSubscribedEmojisBySet(),
        )
    }
}
