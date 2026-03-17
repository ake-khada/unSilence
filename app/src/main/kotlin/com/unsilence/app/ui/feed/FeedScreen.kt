package com.unsilence.app.ui.feed

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.relay.CoverageStatus
import com.unsilence.app.ui.common.LoadingScreen
import com.unsilence.app.ui.shared.EngagementSnapshot
import com.unsilence.app.ui.shared.EventActionCallbacks
import com.unsilence.app.ui.shared.RenderContext
import com.unsilence.app.ui.shared.eventFeedItems
import com.unsilence.app.ui.shared.rememberVideoPlaybackScope
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun FeedScreen(
    scrollToTopTrigger: Int = 0,
    onNoteClick: (String) -> Unit = {},
    onAuthorClick: (pubkey: String) -> Unit = {},
    onQuote: (String) -> Unit = {},
    viewModel: FeedViewModel = hiltViewModel(),
    actionsViewModel: NoteActionsViewModel = hiltViewModel(),
) {
    val state         by viewModel.uiState.collectAsStateWithLifecycle()
    val reactedIds    by actionsViewModel.reactedEventIds.collectAsStateWithLifecycle()
    val repostedIds   by actionsViewModel.repostedEventIds.collectAsStateWithLifecycle()
    val zappedIds     by actionsViewModel.zappedEventIds.collectAsStateWithLifecycle()
    val isNwcConfigured = actionsViewModel.isNwcConfigured
    val listState = rememberLazyListState()

    var articleRow by remember { mutableStateOf<FeedRow?>(null) }

    // ── New-post flash animation tracking ──────────────────────────────────────
    val newEventIds = remember { mutableStateMapOf<String, Boolean>() }
    var previousEventIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(state.events) {
        val currentIds = state.events.map { it.id }.toSet()
        if (previousEventIds.isNotEmpty()) {
            val freshIds = currentIds - previousEventIds
            for (id in freshIds) newEventIds[id] = true
        }
        previousEventIds = currentIds
    }

    // ── Shared video playback — all wiring in one call ───────────────────────
    val videoScope = rememberVideoPlaybackScope(
        ownerId = "feed",
        holder = actionsViewModel.sharedPlayerHolder,
        events = state.events,
        listState = listState,
        thumbnailCache = actionsViewModel.videoThumbnailCache,
    )

    // ── Shared callbacks + engagement snapshot ────────────────────────────────
    val engagement = EngagementSnapshot(
        reactedIds = reactedIds,
        repostedIds = repostedIds,
        zappedIds = zappedIds,
        isNwcConfigured = isNwcConfigured,
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
        lookupEvent = actionsViewModel::lookupEvent,
        fetchOgMetadata = actionsViewModel::fetchOgMetadata,
        profileFlow = viewModel::profileFlow,
    )

    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) listState.animateScrollToItem(0)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        Crossfade(
            targetState = when {
                state.coverageStatus in listOf(CoverageStatus.NEVER_FETCHED, CoverageStatus.LOADING)
                    && state.events.isEmpty() -> "loading"
                state.coverageStatus == CoverageStatus.FAILED && state.events.isEmpty() -> "failed"
                state.coverageStatus in listOf(CoverageStatus.COMPLETE, CoverageStatus.PARTIAL)
                    && state.events.isEmpty() -> "empty"
                !state.loading && state.events.isEmpty() -> "empty"
                else -> "content"
            },
            label = "feedState",
        ) { screenState ->
        when (screenState) {
            "loading" -> {
                LoadingScreen()
            }

            "empty" -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text       = "No posts yet.\nTap to retry.",
                        color      = TextSecondary,
                        fontSize   = 15.sp,
                        textAlign  = TextAlign.Center,
                        lineHeight = 22.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Retry",
                        tint = Cyan,
                        modifier = Modifier
                            .size(32.dp)
                            .clickable { viewModel.refresh() },
                    )
                }
            }

            "failed" -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text       = "All relays failed.\nTap to retry.",
                        color      = TextSecondary,
                        fontSize   = 15.sp,
                        textAlign  = TextAlign.Center,
                        lineHeight = 22.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Retry",
                        tint = Cyan,
                        modifier = Modifier
                            .size(32.dp)
                            .clickable { viewModel.refresh() },
                    )
                }
            }

            else -> {
                LazyColumn(
                    state    = listState,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    eventFeedItems(
                        events = state.events,
                        engagement = engagement,
                        callbacks = callbacks,
                        videoScope = videoScope,
                        context = RenderContext.Feed,
                        newEventIds = newEventIds.keys,
                        onNewPostAnimated = { newEventIds.remove(it) },
                        thumbnailCache = actionsViewModel.videoThumbnailCache,
                    )
                }

                // Auto-scroll on new top post
                LaunchedEffect(viewModel.hasNewTopPost) {
                    if (viewModel.hasNewTopPost && listState.firstVisibleItemIndex <= 2) {
                        listState.scrollToItem(0)
                        viewModel.clearNewTopPost()
                    }
                }

                // Pagination: trigger near bottom of list
                LaunchedEffect(Unit) {
                    snapshotFlow { listState.firstVisibleItemIndex }
                        .collect { index ->
                            val total = listState.layoutInfo.totalItemsCount
                            if (total > 0 && index > total - 10) {
                                viewModel.loadMore()
                            }
                        }
                }

                // Engagement fetch: only for visible items, debounced
                LaunchedEffect(listState) {
                    snapshotFlow {
                        listState.layoutInfo.visibleItemsInfo
                            .mapNotNull { it.key as? String }
                            .toSet()
                    }
                    .filter { it.isNotEmpty() }
                    .debounce(500)
                    .distinctUntilChanged()
                    .collectLatest { visibleIds ->
                        viewModel.fetchEngagementForVisible(visibleIds)
                        val visibleEvents = state.events.filter { it.id in visibleIds }
                        viewModel.hydrateVisibleCards(visibleEvents)
                    }
                }
            }
        }
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
        )
    }

    if (videoScope.showFullscreenVideo) {
        FullScreenVideoDialog(
            exoPlayer = videoScope.exoPlayer,
            onDismiss = { videoScope.dismissFullscreen() },
        )
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
