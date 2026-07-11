package com.unsilence.app.ui.feed

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.toEventModel
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.domain.model.summaryLabel
import com.unsilence.app.ui.common.EmptyState
import com.unsilence.app.ui.common.LoadingScreen
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.shared.EngagementSnapshot
import com.unsilence.app.ui.shared.EventActionCallbacks
import com.unsilence.app.ui.shared.pollActionCallbacks
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.shared.PostActionsHost
import com.unsilence.app.ui.shared.eventFeedItems
import com.unsilence.app.ui.shared.rememberVideoPlaybackScope
import com.unsilence.app.ui.shared.threadParentVideoSourceCandidateIds
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.BrandDeep
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.White
import androidx.compose.foundation.layout.padding
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.foundation.lazy.items

/** Auto-page when the last visible item is within this distance of the
 *  end of the list (excluding the load-more sentinel). 5 rows is roughly
 *  half a viewport on a phone — far enough that the new batch arrives
 *  before the user reaches the bottom, near enough not to over-fetch
 *  while the user is mid-feed. */
private const val AUTO_PAGE_TRIGGER_DISTANCE = 5

private data class FeedViewport(
    val firstVisible: Int,
    val lastVisible: Int,
    val totalItems: Int,
    val loadingMore: Boolean,
    val cardWidthPx: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    scrollToTopTrigger: Int = 0,
    topBarShown: Boolean = true,
    staticTopPadding: Dp = 0.dp,
    onNoteClick: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onAuthorClick: (pubkey: String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {},
    onQuote: (String) -> Unit = {},
    viewModel: FeedViewModel = hiltViewModel(
        key = "feed-${LocalAppSessionKey.current}",
    ),
    actionsViewModel: NoteActionsViewModel = hiltViewModel(
        key = "note-actions-${LocalAppSessionKey.current}",
    ),
) {
    val contentFilter by viewModel.contentFilter.collectAsStateWithLifecycle()
    val currentFilter by viewModel.filterFlow.collectAsStateWithLifecycle()
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
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val feedEvents    by viewModel.feedRows.collectAsStateWithLifecycle()
    val feedShowDot   by viewModel.showDot.collectAsStateWithLifecycle()
    val rawEventCount by viewModel.rawEventCount.collectAsStateWithLifecycle()
    val wotLookups    by viewModel.wotLookups.collectAsStateWithLifecycle()
    val feedWotDisplayMode by viewModel.feedWotDisplayMode.collectAsStateWithLifecycle()

    val coldStartState by viewModel.coldStartState.collectAsStateWithLifecycle()
    val sensitiveMode  by viewModel.sensitiveContentMode.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val cardWidthPx = LocalWindowInfo.current.containerSize.width

    var articleRow by remember { mutableStateOf<FeedRow?>(null) }

    // ── Long-press bottom sheet state ────────────────────────────────────────
    var actionsRow by remember { mutableStateOf<FeedRow?>(null) }

    // ── Emoji reaction picker state ─────────────────────────────────────────
    // (eventId, pubkey) of the note being custom-reacted to
    var emojiReactTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showFullEmojiPicker by remember { mutableStateOf(false) }
    val openEmojiSettings = com.unsilence.app.ui.common.LocalOpenEmojiSettings.current
    val pinnedShortcodes by actionsViewModel.pinnedEmojiShortcodes.collectAsStateWithLifecycle()
    // Keep one immutable list instance until the pin set actually changes.
    // A fresh list on unrelated screen recompositions invalidates every card.
    val pinnedEmojis = remember(pinnedShortcodes) { actionsViewModel.getPinnedEmojis() }

    // ── Action failure snackbar ──────────────────────────────────────────────
    LaunchedEffect(Unit) {
        actionsViewModel.actionError.collect { showSnackbar(it) }
    }

    // ── New-post flash — only live-tail arrivals, not batch/snapshot/Load More ──
    val events = feedEvents
    val liveArrivalIds by viewModel.liveArrivalIds.collectAsStateWithLifecycle()
    val showThreadParents = contentFilter == FeedContentFilter.REPLIES_ONLY
    val filterSummary = currentFilter.summaryLabel()

    // ── Shared video playback — all wiring in one call ───────────────────────
    val videoScope = rememberVideoPlaybackScope(
        ownerId = "feed",
        holder = actionsViewModel.sharedPlayerHolder,
        events = events,
        listState = listState,
        videoModelProvider = actionsViewModel::getVideoRenderModels,
        cachedModelProvider = actionsViewModel::getCachedEventModel,
        additionalVideoSourceCandidateIds = if (showThreadParents) {
            ::threadParentVideoSourceCandidateIds
        } else null,
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
    val callbacks = remember(viewModel, actionsViewModel, pinnedEmojis, wotLookups, feedWotDisplayMode) {
        EventActionCallbacks(
            onNoteClick = onNoteClick,
            onComment = onComment,
            onAuthorClick = onAuthorClick,
            onHashtagClick = onHashtagClick,
            onQuote = onQuote,
            onArticleClick = { articleRow = it },
            react = { id, pk, emoji, url -> actionsViewModel.react(id, pk, emoji, url) },
            onReactLongPress = { id, pk ->
                emojiReactTarget = id to pk
                showFullEmojiPicker = true
            },
            pinnedEmojis = { pinnedEmojis },
            repost = { id, pk, relay -> actionsViewModel.repost(id, pk, relay) },
            zap = { id, pk, relay, req -> actionsViewModel.zap(id, pk, relay, req) },
            saveNwcUri = { actionsViewModel.saveNwcUri(it) },
            lookupProfile = actionsViewModel::lookupProfile,
            lookupEvent = { id, hints -> actionsViewModel.lookupEvent(id, hints) },
            lookupEventWithAuthor = { id, hints, authorPk -> actionsViewModel.lookupEvent(id, hints, authorPk) },
            fetchOgMetadata = actionsViewModel::fetchOgMetadata,
            hasCachedOgMetadata = actionsViewModel::hasCachedOgMetadata,
            profileFlow = viewModel::profileFlow,
            statsFlow = viewModel::statsFlow,
            zapDetailsForEvent = viewModel::zapDetailsForEvent,
            repostPubkeysForEvent = viewModel::repostPubkeysForEvent,
            reactionsForEvent = viewModel::reactionsForEvent,
            wotLookup = { pubkey -> wotLookups[pubkey] },
            feedWotDisplayMode = feedWotDisplayMode,
            onWotSubjectsVisible = viewModel::requestWotHydration,
            poll = actionsViewModel.pollActionCallbacks(),
            onLongPress = { row -> actionsRow = row },
        )
    }

    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) listState.animateScrollToItem(0)
    }

    // Intent-based at-top: only user-driven scrolling changes _isAtTop.
    // Prepends change firstVisibleItemIndex without scrolling — they must
    // NOT flip _isAtTop false.  isScrollInProgress is true only during a
    // user drag/fling, never during a layout-driven index shift.
    val isAtTop by viewModel.isAtTop.collectAsStateWithLifecycle()

    LaunchedEffect(listState) {
        var wasScrolling = false
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }.collect { (scrolling, idx, offset) ->
            if (scrolling || wasScrolling) {
                viewModel.setAtTop(idx == 0 && offset == 0)
            }
            wasScrolling = scrolling
        }
    }

    // Pin viewport at item 0 while user intends to be at the live edge.
    // Gated on isAtTop (intent), not an index window.
    LaunchedEffect(liveArrivalIds) {
        if (liveArrivalIds.isNotEmpty() && isAtTop) {
            listState.scrollToItem(0)
        }
    }

    // Tab row: constant height, slides via offset (no height-collapse jerk)
    val tabRowHeight = 48.dp
    val filterPillHeight = if (filterSummary != null) 36.dp else 0.dp
    val totalTopPadding = staticTopPadding + tabRowHeight + filterPillHeight
    val tabRowOffset by animateDpAsState(
        targetValue   = if (topBarShown) 0.dp else -(totalTopPadding + 8.dp),
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label         = "tabRowOffset",
    )
    val refreshLineOffset = staticTopPadding + tabRowOffset + tabRowHeight + filterPillHeight
    val refreshLineProgress = remember { Animatable(0f) }
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            refreshLineProgress.snapTo(0f)
            refreshLineProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 3000, easing = FastOutSlowInEasing),
            )
        } else {
            refreshLineProgress.snapTo(0f)
        }
    }

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

            else -> {
                val pullState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh    = { viewModel.triggerRefresh() },
                    state        = pullState,
                    modifier     = Modifier.fillMaxSize(),
                    indicator    = {},
                ) {
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
                        newEventIds = liveArrivalIds,
                        onNewPostAnimated = { viewModel.clearLiveArrival(it) },
                        thumbnailCache = actionsViewModel.videoThumbnailCache,
                        imageDimensionCache = actionsViewModel.imageDimensionCache,
                        showThreadParents = showThreadParents,
                        eventModelProvider = actionsViewModel::getEventModel,
                        sensitiveMode = sensitiveMode,
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
                } // PullToRefreshBox

                // Viewport tracking — warm-zone hydration + auto-paging.
                // _isAtTop is driven by the gesture-based snapshotFlow above,
                // NOT from this sampled index (which drifts on prepends).
                LaunchedEffect(events, cardWidthPx, contentFilter) {
                    if (events.isNotEmpty() && cardWidthPx > 0) {
                        val info = listState.layoutInfo
                        val first = info.visibleItemsInfo.firstOrNull()?.index
                            ?: listState.firstVisibleItemIndex
                        val last = info.visibleItemsInfo.lastOrNull()?.index
                            ?: (first + 8).coerceAtMost(events.lastIndex)
                        viewModel.onViewportChanged(
                            first = first,
                            last = last,
                            cardWidthPx = cardWidthPx,
                        )
                    }
                }

                @OptIn(kotlinx.coroutines.FlowPreview::class)
                LaunchedEffect(Unit) {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
                        val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                        val total = info.totalItemsCount
                        FeedViewport(first, last, total, isLoadingMore, cardWidthPx)
                    }.sample(100).collect { viewport ->
                        viewModel.onViewportChanged(
                            first = viewport.firstVisible,
                            last = viewport.lastVisible,
                            cardWidthPx = viewport.cardWidthPx,
                        )
                        // total includes the "load-more" sentinel item when
                        // events.isNotEmpty(). last >= total - 1 - trigger
                        // means the user is within `trigger` rows of the bottom.
                        if (!viewport.loadingMore && viewport.totalItems > 0 &&
                            viewport.lastVisible >= viewport.totalItems - 1 - AUTO_PAGE_TRIGGER_DISTANCE
                        ) {
                            viewModel.loadMore()
                        }
                    }
                }
            }
        }
        } // Crossfade

        if (isRefreshing) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(refreshLineProgress.value)
                    .offset { IntOffset(0, refreshLineOffset.roundToPx()) }
                    .height(1.5.dp)
                    .background(BrandDeep),
            )
        }

        // ── Tab row overlay (slides with top bar via offset, no height collapse) ─
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(0, (staticTopPadding + tabRowOffset).roundToPx()) }
                .fillMaxWidth()
                .height(tabRowHeight)
                .background(Black),
        ) {
            FeedContentTabs(
                selected = contentFilter,
                onSelect = { viewModel.setContentFilter(it) },
            )
        }
        if (filterSummary != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(0, (staticTopPadding + tabRowOffset + tabRowHeight).roundToPx()) }
                    .fillMaxWidth()
                    .height(filterPillHeight)
                    .background(Black),
                contentAlignment = Alignment.Center,
            ) {
                ActiveFeedFilterPill(
                    summary = filterSummary,
                    onClear = { viewModel.updateFilter(FeedFilter()) },
                )
            }
        }
    }

    articleRow?.let { row ->
        // Effective engagement target: for a kind-6/16 reposted article these route
        // to the ORIGINAL event (model.engagementId/pubkey), not the wrapper.
        val model = remember(row.id, row.content, row.tags) { row.toEventModel() }
        ArticleReaderScreen(
            row             = row,
            onDismiss       = { articleRow = null },
            onNoteClick     = onNoteClick,
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
            onQuote         = onQuote,
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

    if (videoScope.showFullscreenVideo) {
        FullScreenVideoDialog(
            exoPlayer = videoScope.exoPlayer,
            videoUrl  = videoScope.activeVideoUrl,
            onDismiss = { videoScope.dismissFullscreen() },
        )
    }

    // ── Long-press actions bottom sheet ──────────────────────────────────────
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
        EmojiPickerSheet(
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

@Composable
private fun ActiveFeedFilterPill(
    summary: String,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Surface2)
            .border(
                width = 1.dp,
                color = Brand.copy(alpha = 0.55f),
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClear() }
            .padding(horizontal = Spacing.medium, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = summary,
            color = Brand,
            fontSize = AppType.caption,
            fontWeight = FontWeight.SemiBold,
        )
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Clear filter",
            tint = Brand,
            modifier = Modifier.size(12.dp),
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
