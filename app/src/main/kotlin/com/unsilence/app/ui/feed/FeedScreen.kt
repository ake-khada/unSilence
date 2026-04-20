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
import androidx.compose.runtime.DisposableEffect
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
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.relay.CoverageStatus
import com.unsilence.app.ui.common.EmptyState
import com.unsilence.app.ui.common.LoadingScreen
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.shared.EngagementSnapshot
import com.unsilence.app.ui.shared.EventActionCallbacks
import com.unsilence.app.ui.shared.RenderContext
import com.unsilence.app.ui.shared.eventFeedItems
import com.unsilence.app.ui.shared.rememberVideoPlaybackScope
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.White
import androidx.compose.foundation.layout.padding
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.lazy.items

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
    val state         by viewModel.uiState.collectAsStateWithLifecycle()
    val reducerState  by viewModel.reducerState.collectAsStateWithLifecycle()
    val contentFilter by viewModel.contentFilter.collectAsStateWithLifecycle()
    val reactedIds    by actionsViewModel.reactedEventIds.collectAsStateWithLifecycle()
    val repostedIds   by actionsViewModel.repostedEventIds.collectAsStateWithLifecycle()
    val zappedIds     by actionsViewModel.zappedEventIds.collectAsStateWithLifecycle()
    val zapLoadingIds by actionsViewModel.zapLoading.collectAsStateWithLifecycle()
    val optimisticSats by actionsViewModel.optimisticZapSats.collectAsStateWithLifecycle()
    val zapFlash      by actionsViewModel.zapFlashState.collectAsStateWithLifecycle()
    val isNwcConfigured = actionsViewModel.isNwcConfigured
    val showSnackbar    = LocalShowSnackbar.current
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val restoreGen    by viewModel.restoreGeneration.collectAsStateWithLifecycle()

    val splashDone by viewModel.splashDone.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    DisposableEffect(Unit) {
        viewModel.setFeedVisible(true)
        onDispose { viewModel.setFeedVisible(false) }
    }

    var articleRow by remember { mutableStateOf<FeedRow?>(null) }

    // ── Zap failure snackbar (lifted from per-card LaunchedEffect) ────────────
    LaunchedEffect(zapFlash) {
        val flash = zapFlash ?: return@LaunchedEffect
        if (!flash.success) showSnackbar("Zap failed: ${flash.message ?: "unknown error"}")
    }

    // ── New-post flash animation tracking ──────────────────────────────────────
    val events = reducerState.visibleEvents
    val currentEvents by rememberUpdatedState(events)
    val newEventIds = remember { mutableStateMapOf<String, Boolean>() }
    var previousEventIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(events) {
        val currentIds = events.map { it.id }.toSet()
        if (previousEventIds.isNotEmpty()) {
            val freshIds = currentIds - previousEventIds
            for (id in freshIds) newEventIds[id] = true
            // Only scroll to top when new events MERGED at top (not APPENDed at bottom).
            // Check: fresh IDs are at the start of the list = MERGE, at the end = APPEND.
            if (freshIds.isNotEmpty() && !reducerState.showDot) {
                val firstFreshIdx = events.indexOfFirst { it.id in freshIds }
                if (firstFreshIdx == 0) {
                    // New events at top (MERGE) — scroll to show them
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
    val engagement = EngagementSnapshot(
        reactedIds = reactedIds,
        repostedIds = repostedIds,
        zappedIds = zappedIds,
        isNwcConfigured = isNwcConfigured,
        zapLoadingIds = zapLoadingIds,
        optimisticZapSats = optimisticSats,
        zapFlash = zapFlash,
    )
    val callbacks = EventActionCallbacks(
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
        fetchOgMetadata = actionsViewModel::fetchOgMetadata,
        profileFlow = viewModel::profileFlow,
    )

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
                !splashDone -> "loading"
                state.coverageStatus in listOf(CoverageStatus.NEVER_FETCHED, CoverageStatus.LOADING)
                    && events.isEmpty() -> "loading"
                state.coverageStatus == CoverageStatus.FAILED && events.isEmpty() -> "failed"
                state.coverageStatus in listOf(CoverageStatus.COMPLETE, CoverageStatus.PARTIAL)
                    && events.isEmpty() -> "empty"
                !state.loading && events.isEmpty() -> "empty"
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
                        context = RenderContext.Feed,
                        newEventIds = newEventIds.keys,
                        onNewPostAnimated = { newEventIds.remove(it) },
                        thumbnailCache = actionsViewModel.videoThumbnailCache,
                        imageDimensionCache = actionsViewModel.imageDimensionCache,
                        imetaImageDimsProvider = actionsViewModel::getImetaImageDims,
                        showThreadParents = contentFilter == FeedContentFilter.REPLIES_ONLY,
                    )

                    if (isLoadingMore) {
                        item(key = "loading-more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Cyan,
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }
                }

                // Scroll position tracking + pagination (merged observer)
                LaunchedEffect(Unit) {
                    snapshotFlow {
                        Triple(
                            listState.firstVisibleItemIndex,
                            listState.firstVisibleItemScrollOffset,
                            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
                        )
                    }.collect { (index, offset, lastVisible) ->
                        viewModel.onScrollPositionChanged(index, offset)
                        viewModel.saveScrollPosition(index, offset)
                        val total = listState.layoutInfo.totalItemsCount
                        if (total > 0 && lastVisible >= total / 2) {
                            viewModel.loadMore()
                        }
                    }
                }

                // Queue gate: pause discretionary hydration when items are pending
                val controller = viewModel.hydrationController
                LaunchedEffect(reducerState.unreadCount) {
                    controller.onPendingCountChanged(reducerState.unreadCount)
                }

                // Hydration controller: sampled at 16 Hz (60ms) to avoid
                // running the state machine at display refresh rate (120 Hz).
                // Scroll start/stop edges fire immediately via a separate flow.
                LaunchedEffect(listState) {
                    // Immediate edge detection — fires only on actual changes
                    launch {
                        snapshotFlow { listState.isScrollInProgress }
                            .distinctUntilChanged()
                            .collect { isScrolling ->
                                if (isScrolling) controller.onScrollStarted()
                                else controller.onScrollStopped()
                            }
                    }

                    // Sampled layout snapshot — 16 Hz max
                    @OptIn(kotlinx.coroutines.FlowPreview::class)
                    snapshotFlow {
                        Triple(
                            listState.firstVisibleItemScrollOffset,
                            listState.isScrollInProgress,
                            listState.layoutInfo.visibleItemsInfo
                                .mapNotNull { it.key as? String }
                                .toSet()
                        )
                    }
                    .sample(60)
                    .collect { (scrollOffset, isScrolling, visibleIds) ->
                        val latestEvents = currentEvents
                        val visibleEvents = latestEvents.filter { it.id in visibleIds }
                        controller.onScrollFrame(
                            visibleItems = visibleEvents,
                            allEvents = latestEvents,
                            scrollPixelOffset = scrollOffset,
                            isScrollInProgress = isScrolling,
                        )
                    }
                }

                // Restore scroll position after feed switch
                LaunchedEffect(restoreGen) {
                    if (restoreGen > 0) {
                        val idx = viewModel.restoreScrollIndex.value
                        val off = viewModel.restoreScrollOffset.value
                        if (idx > 0) {
                            // Wait up to 2s for enough items to populate the list
                            kotlinx.coroutines.withTimeoutOrNull(2_000) {
                                snapshotFlow { listState.layoutInfo.totalItemsCount }
                                    .filter { count: Int -> count > idx }
                                    .first()
                            }
                            val total = listState.layoutInfo.totalItemsCount
                            if (total > 0) {
                                listState.scrollToItem(idx.coerceAtMost(total - 1), off)
                            }
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
