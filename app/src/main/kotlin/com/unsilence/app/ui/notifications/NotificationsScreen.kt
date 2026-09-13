package com.unsilence.app.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.ui.common.EmptyState
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.feed.EmbeddedArticleCard
import com.unsilence.app.ui.feed.EventCardActions
import com.unsilence.app.ui.feed.EventCardHost
import com.unsilence.app.ui.feed.NoteActionsViewModel
import com.unsilence.app.ui.feed.eventCardHost
import com.unsilence.app.ui.shared.EmbeddedEventCard
import com.unsilence.app.ui.shared.FeedDivider
import com.unsilence.app.ui.shared.MutedContentHiddenCard
import com.unsilence.app.ui.shared.NotificationEventRow
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Activity headers plus the shared action-bar-free embedded content card.
 * Only the viewport's targets are resolved; no engagement hydration or autoplay.
 */
@Composable
fun NotificationsScreen(
    onNoteClick: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onHashtagClick: (String) -> Unit,
    onQuote: (String) -> Unit,
    actionsViewModel: NoteActionsViewModel,
    staticTopPadding: Dp = 0.dp,
    viewModel: NotificationsViewModel = hiltViewModel(
        key = "notif-${LocalAppSessionKey.current}",
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val wotLookups by viewModel.wotLookups.collectAsStateWithLifecycle()
    val feedWotDisplayMode by viewModel.feedWotDisplayMode.collectAsStateWithLifecycle()
    val sensitiveMode by viewModel.sensitiveMode.collectAsStateWithLifecycle()
    val previews by viewModel.previews.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var targets by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingTargets by remember { mutableStateOf<Set<String>>(emptySet()) }
    val cardWidthPx = LocalWindowInfo.current.containerSize.width
    val warmIds = remember(previews, sensitiveMode) {
        previews.mapNotNull { (id, preview) ->
            id.takeIf {
                preview is NotificationPreview.Ready &&
                    (sensitiveMode != SensitiveContentMode.HIDE || !preview.event.hasContentWarning)
            }
        }
    }
    LaunchedEffect(warmIds, cardWidthPx) {
        val rows = viewModel.previewWarmRows(warmIds)
        actionsViewModel.warmCardWindow(
            rows, 0, rows.lastIndex, cardWidthPx,
            hydrateEngagement = false,
            maxRows = NOTIFICATION_PREVIEW_WINDOW,
        )
    }

    LaunchedEffect(state.items, listState) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            notificationPreviewTargets(
                state.items,
                visible.firstOrNull()?.index ?: -1,
                visible.lastOrNull()?.index ?: -1,
            )
        }.distinctUntilChanged().collect { ids ->
            targets = ids
            viewModel.setPreviewTargets(ids)
        }
    }
    LaunchedEffect(targets) {
        loadingTargets = targets.toSet()
        viewModel.loadPreviewTargets(targets, actionsViewModel::lookupEvent) { id ->
            loadingTargets = loadingTargets - id
        }
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.setPreviewTargets(emptyList()) }
    }
    val host = remember(
        actionsViewModel, viewModel, onNoteClick, onProfileClick, onHashtagClick, onQuote,
        sensitiveMode, wotLookups, feedWotDisplayMode,
    ) {
        actionsViewModel.eventCardHost(
            actions = EventCardActions(
                onNoteClick = onNoteClick,
                onComment = { _, model -> onNoteClick(model.navigateId) },
                onAuthorClick = onProfileClick,
                onHashtagClick = onHashtagClick,
                onQuote = onQuote,
                onArticleClick = { onNoteClick(it.id) },
                onReactLongPress = null,
                onLongPress = null,
            ),
            profileFlow = viewModel::profileFlow,
            statsFlow = null,
            zapDetailsForEvent = null,
            repostPubkeysForEvent = null,
            reactionsForEvent = null,
            pinnedEmojis = emptyList(),
            videoScope = null,
            sensitiveMode = sensitiveMode,
            wotLookup = { wotLookups[it] },
            feedWotDisplayMode = feedWotDisplayMode,
            onWotSubjectsVisible = viewModel::requestPreviewWotHydration,
            pollActions = null,
            showTimestamps = false,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        when {
            state.loading -> {
                CircularProgressIndicator(
                    color    = Brand,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            state.items.isEmpty() -> {
                EmptyState(
                    icon    = Icons.Outlined.Notifications,
                    message = "No notifications yet",
                    hint    = "Interactions will appear here",
                )
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(top = staticTopPadding),
                ) {
                    items(state.items, key = { it.key }) { row ->
                        Column {
                            NotificationEventRow(
                                row = row,
                                wotLookups = wotLookups,
                                feedWotDisplayMode = feedWotDisplayMode,
                                onNoteClick = onNoteClick,
                                onProfileClick = onProfileClick,
                            )
                            row.previewTargetId()?.let { id ->
                                NotificationContentPreview(
                                    targetId = id,
                                    preview = previews[id],
                                    loading = id in loadingTargets,
                                    host = host,
                                )
                            }
                            FeedDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationContentPreview(
    targetId: String,
    preview: NotificationPreview?,
    loading: Boolean,
    host: EventCardHost,
) {
    val modifier = Modifier.padding(bottom = Spacing.medium)
    when (preview) {
        NotificationPreview.Muted -> MutedContentHiddenCard(
            modifier.padding(horizontal = Spacing.medium),
        )
        is NotificationPreview.Ready -> {
            val model = preview.model
            val cardHost = host.withRelayHints(listOf(preview.event.relayUrl))
            val dTag = model.article?.dTag
            if (model.effectiveKind == 30023 && dTag != null) {
                EmbeddedArticleCard(
                    segment = Segment.QuoteAddress(30023, model.pubkey, dTag, cardHost.relayHints),
                    host = cardHost,
                    nestDepth = 1,
                    modifier = modifier,
                )
            } else {
                EmbeddedEventCard(
                    event = preview.event,
                    model = model,
                    author = null,
                    host = cardHost,
                    videoOwnerId = targetId,
                    modifier = modifier,
                )
            }
        }
        else -> Text(
            text = if (loading) "Loading post…" else "Post unavailable · tap to open",
            color = TextSecondary,
            fontSize = AppType.footnote,
            modifier = modifier
                .fillMaxWidth()
                .clickable { host.actions.onNoteClick(targetId) }
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        )
    }
}
