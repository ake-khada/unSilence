package com.unsilence.app.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Favorite
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.common.ShimmerNoteCard
import com.unsilence.app.ui.feed.ArticleReaderScreen
import com.unsilence.app.ui.feed.EventCard
import com.unsilence.app.ui.feed.NoteActionsViewModel
import com.unsilence.app.ui.feed.engagementId
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.shared.EngagementSnapshot
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

@Composable
fun ThreadScreen(
    eventId: String,
    onDismiss: () -> Unit,
    onQuote: (String) -> Unit = {},
    onAuthorClick: (pubkey: String) -> Unit = {},
    viewModel: ThreadViewModel = hiltViewModel(),
    actionsViewModel: NoteActionsViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onDismiss)
    DisposableEffect(Unit) { onDispose { viewModel.clearThread() } }
    LaunchedEffect(eventId) { viewModel.loadThread(eventId) }
    LaunchedEffect(viewModel.published) {
        if (viewModel.published) onDismiss()
    }

    val state           by viewModel.uiState.collectAsStateWithLifecycle()
    val reactedIds      by actionsViewModel.reactedEventIds.collectAsStateWithLifecycle()
    val repostedIds     by actionsViewModel.repostedEventIds.collectAsStateWithLifecycle()
    val zappedIds       by actionsViewModel.zappedEventIds.collectAsStateWithLifecycle()
    val zapLoadingIds   by actionsViewModel.zapLoading.collectAsStateWithLifecycle()
    val optimisticSats  by actionsViewModel.optimisticZapSats.collectAsStateWithLifecycle()
    val zapFlash        by actionsViewModel.zapFlashState.collectAsStateWithLifecycle()
    val isNwcConfigured = actionsViewModel.isNwcConfigured
    val showSnackbar = LocalShowSnackbar.current
    var replyText by remember { mutableStateOf("") }
    var articleRow by remember { mutableStateOf<FeedRow?>(null) }

    // ── Zap failure snackbar (lifted from per-card LaunchedEffect) ────────────
    LaunchedEffect(zapFlash) {
        val flash = zapFlash ?: return@LaunchedEffect
        if (!flash.success) showSnackbar("Zap failed: ${flash.message ?: "unknown error"}")
    }
    // ── React/repost failure snackbar ────────────────────────────────────────
    LaunchedEffect(Unit) {
        actionsViewModel.actionError.collect { showSnackbar(it) }
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
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        // Focused (OP) note — plain NoteCard, no border decoration
                        state.focusedNote?.let { note ->
                            item(key = note.id) {
                                val focusedEngagement = remember(reactedIds, repostedIds, zappedIds, isNwcConfigured, zapLoadingIds, optimisticSats, zapFlash) {
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
                                val focusedModel = actionsViewModel.getEventModel(note.id)
                                if (focusedModel != null) {
                                    EventCard(
                                        model               = focusedModel,
                                        row                 = note,
                                        role                = if (note.kind == 30023) CardRole.Article else CardRole.Thread,
                                        engagement          = focusedEngagement,
                                        onNoteClick         = { /* already on thread */ },
                                        onAuthorClick       = onAuthorClick,
                                        onQuote             = onQuote,
                                        onArticleClick      = { articleRow = it },
                                        onReact             = { actionsViewModel.react(note.id, note.pubkey) },
                                        onRepost            = { actionsViewModel.repost(note.id, note.pubkey, note.relayUrl) },
                                        onZap               = { amt -> actionsViewModel.zap(note.id, note.pubkey, note.relayUrl, amt) },
                                        onSaveNwcUri        = { uri -> actionsViewModel.saveNwcUri(uri) },
                                        lookupProfile       = actionsViewModel::lookupProfile,
                                        lookupEvent         = { id, hints -> actionsViewModel.lookupEvent(id, hints) },
                                        lookupEventWithAuthor = { id, hints, authorPk -> actionsViewModel.lookupEvent(id, hints, authorPk) },
                                        lookupModel         = actionsViewModel::getEventModel,
                                        fetchOgMetadata     = actionsViewModel::fetchOgMetadata,
                                        profileFlow         = null,
                                        imageDimensionCache = actionsViewModel.imageDimensionCache,
                                        thumbnailCache      = actionsViewModel.videoThumbnailCache,
                                    )
                                } else {
                                    ShimmerNoteCard(showMedia = true)
                                }
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
                                val indent = (depth * 16).dp
                                val lineColor = Color.White.copy(alpha = 0.10f)

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .drawBehind {
                                            for (d in 1..depth) {
                                                val x = (d * 16).dp.toPx()
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
                                    val replyEngagement = remember(reactedIds, repostedIds, zappedIds, isNwcConfigured, zapLoadingIds, optimisticSats, zapFlash) {
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
                                    val replyModel = actionsViewModel.getEventModel(reply.id)
                                    if (replyModel != null) {
                                        EventCard(
                                            model               = replyModel,
                                            row                 = reply,
                                            role                = CardRole.Reply,
                                            engagement          = replyEngagement,
                                            onNoteClick         = { /* already viewing thread */ },
                                            onAuthorClick       = onAuthorClick,
                                            onQuote             = onQuote,
                                            onArticleClick      = { articleRow = it },
                                            onReact             = { actionsViewModel.react(reply.id, reply.pubkey) },
                                            onRepost            = { actionsViewModel.repost(reply.id, reply.pubkey, reply.relayUrl) },
                                            onZap               = { amt -> actionsViewModel.zap(reply.id, reply.pubkey, reply.relayUrl, amt) },
                                            onSaveNwcUri        = { uri -> actionsViewModel.saveNwcUri(uri) },
                                            lookupProfile       = actionsViewModel::lookupProfile,
                                            lookupEvent         = { id, hints -> actionsViewModel.lookupEvent(id, hints) },
                                        lookupEventWithAuthor = { id, hints, authorPk -> actionsViewModel.lookupEvent(id, hints, authorPk) },
                                            lookupModel         = actionsViewModel::getEventModel,
                                            fetchOgMetadata     = actionsViewModel::fetchOgMetadata,
                                            profileFlow         = null,
                                            imageDimensionCache = actionsViewModel.imageDimensionCache,
                                            thumbnailCache      = actionsViewModel.videoThumbnailCache,
                                        )
                                    } else {
                                        ShimmerNoteCard(showMedia = false)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)

            // ── Reply input ───────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Black)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val focused = state.focusedNote

                // Like button for focused note
                IconButton(
                    onClick  = { focused?.let { actionsViewModel.react(it.id, it.pubkey) } },
                    modifier = Modifier.size(36.dp),
                    enabled  = focused != null,
                ) {
                    Icon(
                        imageVector        = Icons.Filled.Favorite,
                        contentDescription = "Like",
                        tint               = if (focused != null && focused.engagementId in reactedIds)
                                                 Color(0xFFE91E63) else TextSecondary,
                        modifier           = Modifier.size(20.dp),
                    )
                }

                Spacer(Modifier.width(Spacing.small))

                BasicTextField(
                    value         = replyText,
                    onValueChange = { replyText = it },
                    textStyle     = TextStyle(color = Color.White, fontSize = AppType.bodyLarge),
                    cursorBrush   = SolidColor(Cyan),
                    modifier      = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (replyText.isEmpty()) {
                            Text("Reply…", color = TextSecondary, fontSize = AppType.bodyLarge)
                        }
                        inner()
                    },
                )

                Spacer(Modifier.width(Spacing.small))

                // Send arrow
                IconButton(
                    onClick  = {
                        if (replyText.isNotBlank() && focused != null) {
                            val rootId = focused.rootId ?: focused.id
                            viewModel.publishReply(
                                content       = replyText.trim(),
                                rootId        = rootId,
                                replyToId     = focused.id,
                                replyToPubkey = focused.pubkey,
                            )
                        }
                    },
                    enabled  = replyText.isNotBlank() && focused != null,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send reply",
                        tint               = if (replyText.isNotBlank()) Cyan else TextSecondary,
                        modifier           = Modifier.size(20.dp),
                    )
                }
            }
        }
    }

    articleRow?.let { row ->
        ArticleReaderScreen(
            row             = row,
            onDismiss       = { articleRow = null },
            onQuote         = onQuote,
            onReact         = { actionsViewModel.react(row.id, row.pubkey) },
            onRepost        = { actionsViewModel.repost(row.id, row.pubkey, row.relayUrl) },
            onZap           = { amt -> actionsViewModel.zap(row.id, row.pubkey, row.relayUrl, amt) },
            onSaveNwcUri    = { uri -> actionsViewModel.saveNwcUri(uri) },
            hasReacted      = row.engagementId in reactedIds,
            hasReposted     = row.engagementId in repostedIds,
            hasZapped       = row.engagementId in zappedIds,
            isNwcConfigured = isNwcConfigured,
            isZapLoading    = row.id in zapLoadingIds,
            extraZapSats    = optimisticSats[row.id] ?: 0L,
            zapFlash        = zapFlash,
        )
    }
}
