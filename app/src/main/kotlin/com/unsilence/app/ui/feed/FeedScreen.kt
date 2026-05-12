package com.unsilence.app.ui.feed

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.data.memory.UserEntity
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.unsilence.app.ui.common.EmptyState
import com.unsilence.app.ui.common.LoadingScreen
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.shared.EngagementSnapshot
import com.unsilence.app.ui.shared.EventActionCallbacks
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.shared.eventFeedItems
import com.unsilence.app.ui.shared.rememberVideoPlaybackScope
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.White
import androidx.compose.foundation.layout.padding
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.lazy.items

/** Auto-page when the last visible item is within this distance of the
 *  end of the list (excluding the load-more sentinel). 5 rows is roughly
 *  half a viewport on a phone — far enough that the new batch arrives
 *  before the user reaches the bottom, near enough not to over-fetch
 *  while the user is mid-feed. */
private const val AUTO_PAGE_TRIGGER_DISTANCE = 5

@Composable
fun FeedScreen(
    scrollToTopTrigger: Int = 0,
    topBarShown: Boolean = true,
    staticTopPadding: Dp = 0.dp,
    onNoteClick: (String) -> Unit = {},
    onAuthorClick: (pubkey: String) -> Unit = {},
    onQuote: (String) -> Unit = {},
    viewModel: FeedViewModel = hiltViewModel(),
    actionsViewModel: NoteActionsViewModel = hiltViewModel(),
) {
    val contentFilter by viewModel.contentFilter.collectAsStateWithLifecycle()
    val reactedIds    by actionsViewModel.reactedEventIds.collectAsStateWithLifecycle()
    val repostedIds   by actionsViewModel.repostedEventIds.collectAsStateWithLifecycle()
    val zappedIds     by actionsViewModel.zappedEventIds.collectAsStateWithLifecycle()
    val zapLoadingIds by actionsViewModel.zapLoading.collectAsStateWithLifecycle()
    val optimisticSats by actionsViewModel.optimisticZapSats.collectAsStateWithLifecycle()
    val zapFlash      by actionsViewModel.zapFlashState.collectAsStateWithLifecycle()
    val isNwcConfigured = actionsViewModel.isNwcConfigured
    val showSnackbar    = LocalShowSnackbar.current
    val isLoadingV     by viewModel.isLoading.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val feedEvents    by viewModel.feedRows.collectAsStateWithLifecycle()
    val feedShowDot   by viewModel.showDot.collectAsStateWithLifecycle()
    val rawEventCount by viewModel.rawEventCount.collectAsStateWithLifecycle()

    val coldStartState by viewModel.coldStartState.collectAsStateWithLifecycle()
    val sensitiveMode  by viewModel.sensitiveContentMode.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    var articleRow by remember { mutableStateOf<FeedRow?>(null) }

    // ── Long-press bottom sheet state ────────────────────────────────────────
    var actionsRow by remember { mutableStateOf<FeedRow?>(null) }
    var reportRow by remember { mutableStateOf<FeedRow?>(null) }
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    // ── Zap failure snackbar (lifted from per-card LaunchedEffect) ────────────
    LaunchedEffect(zapFlash) {
        val flash = zapFlash ?: return@LaunchedEffect
        if (!flash.success) showSnackbar("Zap failed: ${flash.message ?: "unknown error"}")
    }

    // ── New-post flash animation tracking ──────────────────────────────────────
    val events = feedEvents
    val currentEvents by rememberUpdatedState(events)
    val newEventIds = remember { mutableStateMapOf<String, Boolean>() }
    var previousEventIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(events) {
        val currentIds = events.map { it.id }.toSet()
        if (previousEventIds.isNotEmpty()) {
            val freshIds = currentIds - previousEventIds
            for (id in freshIds) newEventIds[id] = true
            if (freshIds.isNotEmpty() && !feedShowDot) {
                val firstFreshIdx = events.indexOfFirst { it.id in freshIds }
                if (firstFreshIdx == 0) {
                    listState.scrollToItem(0)
                }
            }
        }
        previousEventIds = currentIds
    }

    // ── Shared video playback — all wiring in one call ───────────────────────
    val videoScope = rememberVideoPlaybackScope(
        ownerId = "feed",
        holder = actionsViewModel.sharedPlayerHolder,
        events = events,
        listState = listState,
        videoModelProvider = actionsViewModel::getVideoRenderModels,
    )

    // ── Shared callbacks + engagement snapshot ────────────────────────────────
    val engagement = remember(reactedIds, repostedIds, zappedIds, isNwcConfigured, zapLoadingIds, optimisticSats, zapFlash) {
        EngagementSnapshot(
            reactedIds = reactedIds,
            repostedIds = repostedIds,
            zappedIds = zappedIds,
            isNwcConfigured = isNwcConfigured,
            zapLoadingIds = zapLoadingIds,
            optimisticZapSats = optimisticSats,
            zapFlash = zapFlash,
        )
    }
    val callbacks = remember(viewModel, actionsViewModel) {
        EventActionCallbacks(
            onNoteClick = onNoteClick,
            onAuthorClick = onAuthorClick,
            onQuote = onQuote,
            onArticleClick = { articleRow = it },
            react = { id, pk -> actionsViewModel.react(id, pk) },
            repost = { id, pk, relay -> actionsViewModel.repost(id, pk, relay) },
            zap = { id, pk, relay, amt -> actionsViewModel.zap(id, pk, relay, amt) },
            saveNwcUri = { actionsViewModel.saveNwcUri(it) },
            lookupProfile = actionsViewModel::lookupProfile,
            lookupEvent = { id, hints -> actionsViewModel.lookupEvent(id, hints) },
            lookupEventWithAuthor = { id, hints, authorPk -> actionsViewModel.lookupEvent(id, hints, authorPk) },
            fetchOgMetadata = actionsViewModel::fetchOgMetadata,
            profileFlow = viewModel::profileFlow,
            statsFlow = viewModel::statsFlow,
            onLongPress = { row ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                actionsRow = row
            },
        )
    }

    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) listState.animateScrollToItem(0)
    }

    // Tab row: constant height, slides via offset (no height-collapse jerk)
    val tabRowHeight = 48.dp
    val totalTopPadding = staticTopPadding + tabRowHeight
    val tabRowOffset by animateDpAsState(
        targetValue   = if (topBarShown) 0.dp else -(totalTopPadding + 8.dp),
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label         = "tabRowOffset",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        // ── Swipe left/right to switch Notes ↔ Conversations ──────────
        val swipeDrag = remember { mutableFloatStateOf(0f) }

        Crossfade(
            targetState = when {
                coldStartState == FeedViewModel.ColdStartState.LOADING -> "loading"
                isLoadingV && events.isEmpty() -> "loading"
                !isLoadingV && events.isEmpty() && rawEventCount == 0 -> "empty"
                !isLoadingV && events.isEmpty() && rawEventCount > 0 -> "filtered_empty"
                else -> "content"
            },
            label = "feedState",
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(contentFilter) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val threshold = 100.dp.toPx()
                            if (swipeDrag.floatValue > threshold && contentFilter == FeedContentFilter.REPLIES_ONLY) {
                                viewModel.setContentFilter(FeedContentFilter.NOTES_ONLY)
                            } else if (swipeDrag.floatValue < -threshold && contentFilter == FeedContentFilter.NOTES_ONLY) {
                                viewModel.setContentFilter(FeedContentFilter.REPLIES_ONLY)
                            }
                            swipeDrag.floatValue = 0f
                        },
                        onDragCancel = { swipeDrag.floatValue = 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            swipeDrag.floatValue += dragAmount
                        },
                    )
                },
        ) { screenState ->
        when (screenState) {
            "loading" -> {
                LoadingScreen()
            }

            "empty" -> {
                EmptyState(
                    icon    = Icons.Outlined.Forum,
                    message = "No posts yet",
                    hint    = "Tap to retry",
                    modifier = Modifier.clickable { viewModel.refresh() },
                )
            }

            "filtered_empty" -> {
                EmptyState(
                    icon    = Icons.Outlined.Forum,
                    message = if (contentFilter == FeedContentFilter.REPLIES_ONLY)
                        "No conversations yet" else "No notes match",
                    hint    = "Loading more...",
                    modifier = Modifier,
                )
            }

            "failed" -> {
                EmptyState(
                    icon    = Icons.Filled.CloudOff,
                    message = "All relays failed",
                    hint    = "Tap to retry",
                    modifier = Modifier.clickable { viewModel.refresh() },
                )
            }

            else -> {
                LazyColumn(
                    state    = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(top = totalTopPadding),
                ) {
                    eventFeedItems(
                        events = events,
                        engagement = engagement,
                        callbacks = callbacks,
                        videoScope = videoScope,
                        role = CardRole.Feed,
                        newEventIds = newEventIds.keys,
                        onNewPostAnimated = { newEventIds.remove(it) },
                        thumbnailCache = actionsViewModel.videoThumbnailCache,
                        imageDimensionCache = actionsViewModel.imageDimensionCache,
                        showThreadParents = contentFilter == FeedContentFilter.REPLIES_ONLY,
                        eventModelProvider = actionsViewModel::getEventModel,
                        sensitiveBlur = sensitiveMode == SensitiveContentMode.BLUR,
                    )

                    // "Load more" button at the end of the current batch.
                    // Transitions to a spinner while loading.
                    if (events.isNotEmpty()) {
                        item(key = "load-more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Spacing.large),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isLoadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = TextSecondary,
                                        strokeWidth = 1.5.dp,
                                    )
                                } else {
                                    Text(
                                        text = "Load more",
                                        fontSize = AppType.bodySmall,
                                        color = TextSecondary,
                                        modifier = Modifier
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                            ) { viewModel.loadMore() }
                                            .background(
                                                color = Surface1,
                                                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                            )
                                            .padding(horizontal = Spacing.large, vertical = Spacing.small),
                                    )
                                }
                            }
                        }
                    }
                }

                // Viewport tracking — used for at-top detection AND auto-paging.
                //
                // When the user scrolls within AUTO_PAGE_TRIGGER_DISTANCE rows
                // of the bottom, fire viewModel.loadMore() so older events
                // stream in before the user runs out. Matches the infinite-
                // scroll feel of Jumble / Damus and removes the explicit
                // "Load more" tap unless the user actively pulls.
                //
                // isLoadingMore is read from the ViewModel via collectAsState
                // above; we also re-read it inside the snapshot block to avoid
                // stacking parallel loadMore calls. consumer.loadMore() itself
                // guards the same way, but stopping at the UI saves the round
                // trip through viewModelScope.launch.
                @OptIn(kotlinx.coroutines.FlowPreview::class)
                LaunchedEffect(Unit) {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
                        val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                        val total = info.totalItemsCount
                        Triple(first, last, total)
                    }.sample(100).collect { (first, last, total) ->
                        viewModel.onViewportChanged(first)
                        // total includes the "load-more" sentinel item when
                        // events.isNotEmpty(). last >= total - 1 - trigger
                        // means the user is within `trigger` rows of the bottom.
                        if (!isLoadingMore && total > 0 &&
                            last >= total - 1 - AUTO_PAGE_TRIGGER_DISTANCE
                        ) {
                            viewModel.loadMore()
                        }
                    }
                }
            }
        }
        } // Crossfade

        // ── Tab row overlay (slides with top bar via offset, no height collapse) ─
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(y = staticTopPadding + tabRowOffset)
                .fillMaxWidth()
                .height(tabRowHeight)
                .background(Black),
        ) {
            FeedContentTabs(
                selected = contentFilter,
                onSelect = { viewModel.setContentFilter(it) },
            )
        }
    }

    articleRow?.let { row ->
        ArticleReaderScreen(
            row             = row,
            onDismiss       = { articleRow = null },
            onNoteClick     = onNoteClick,
            onReact         = { actionsViewModel.react(row.id, row.pubkey) },
            onRepost        = { actionsViewModel.repost(row.id, row.pubkey, row.relayUrl) },
            onQuote         = onQuote,
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

    if (videoScope.showFullscreenVideo) {
        FullScreenVideoDialog(
            exoPlayer = videoScope.exoPlayer,
            onDismiss = { videoScope.dismissFullscreen() },
        )
    }

    // ── Long-press actions bottom sheet ──────────────────────────────────────
    actionsRow?.let { row ->
        val authorProfile: UserEntity? = viewModel.profileFlow(row.pubkey)
            .collectAsStateWithLifecycle().value
        PostActionsBottomSheet(
            authorPubkey = row.pubkey,
            authorProfile = authorProfile,
            onDismiss = { actionsRow = null },
            onCopyText = { clipboard.setText(AnnotatedString(row.content)) },
            onCopyLink = {
                val nevent = NEvent.create(row.id, null, null, null as NormalizedRelayUrl?)
                clipboard.setText(AnnotatedString("nostr:$nevent"))
                showSnackbar("Link copied")
            },
            onShare = {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, row.content)
                }
                ctx.startActivity(Intent.createChooser(shareIntent, "Share note"))
            },
            onMuteUser = {
                viewModel.muteUser(row.pubkey)
                showSnackbar("Muted")
            },
            onReport = {
                reportRow = row
                actionsRow = null
            },
        )
    }

    reportRow?.let { row ->
        ReportTypeSheet(
            onDismiss = { reportRow = null },
            onTypeSelected = { type ->
                viewModel.reportEvent(row.id, row.pubkey, type)
                showSnackbar("Reported")
            },
        )
    }
}

@Composable
private fun FeedContentTabs(
    selected: FeedContentFilter,
    onSelect: (FeedContentFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Black),
    ) {
        for (tab in FeedContentFilter.entries) {
            val label = when (tab) {
                FeedContentFilter.NOTES_ONLY -> "Notes"
                FeedContentFilter.REPLIES_ONLY -> "Conversations"
            }
            val isSelected = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(tab) },
                    )
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) White else White.copy(alpha = 0.4f),
                    fontSize = AppType.body,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * Extract the first playable video URL from a FeedRow.
 * Uses imeta tags (MIME-based) then falls back to regex content extraction.
 */
internal fun extractVideoUrl(row: FeedRow): String? {
    // 1. Check imeta tags for video MIME types
    val imetaVideo = com.unsilence.app.data.relay.ImetaParser.videos(row.tags).firstOrNull()?.url
    if (imetaVideo != null) return imetaVideo

    // 2. Fall back to regex match on content
    return VIDEO_URL_REGEX.find(row.content)?.value
}
