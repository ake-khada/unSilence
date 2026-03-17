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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.relay.extractRepostAuthorPubkey
import com.unsilence.app.data.relay.CoverageStatus
import com.unsilence.app.ui.common.LoadingScreen
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.exoplayer.ExoPlayer
import com.unsilence.app.data.relay.ImetaParser
import androidx.media3.common.MediaItem
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

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

    // ── Video autoplay state ─────────────────────────────────────────────────
    val holder = actionsViewModel.sharedPlayerHolder
    val ownerId = "feed"
    val exoPlayer = holder.player
    DisposableEffect(Unit) { onDispose { holder.releaseOwnership(ownerId) } }

    var activeVideoNoteId by remember { mutableStateOf<String?>(null) }
    var isMuted by remember { mutableStateOf(true) }
    var showFullscreenVideo by remember { mutableStateOf(false) }
    var preFullscreenMuted by remember { mutableStateOf(true) }

    // Pause playback when app goes to background, resume when foregrounded
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE  -> exoPlayer.playWhenReady = false
                Lifecycle.Event.ON_RESUME -> if (activeVideoNoteId != null) exoPlayer.playWhenReady = true
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── FeedScreen owns ALL playback transitions ───────────────────────────
    val activeVideoUrl = remember(activeVideoNoteId, state.events) {
        activeVideoNoteId?.let { noteId ->
            state.events.firstOrNull { it.id == noteId }?.let { extractVideoUrl(it) }
        }
    }

    LaunchedEffect(activeVideoUrl) {
        if (activeVideoUrl != null) {
            holder.claim(ownerId)
            val currentUrl = exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
            if (currentUrl != activeVideoUrl) {
                exoPlayer.setMediaItem(MediaItem.fromUri(activeVideoUrl))
                exoPlayer.prepare()
            }
            exoPlayer.playWhenReady = true
        } else {
            if (holder.isOwner(ownerId)) {
                exoPlayer.stop()
            }
        }
    }

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
                // Precompute which notes have video for scroll detection
                val noteIdsWithVideo = remember(state.events) {
                    state.events.filter { row ->
                        row.kind != 30023 &&
                        (ImetaParser.videos(row.tags).isNotEmpty() ||
                            VIDEO_URL_REGEX.containsMatchIn(row.content))
                    }.map { it.id }.toSet()
                }

                LazyColumn(
                    state    = listState,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(
                        items = state.events,
                        key   = { it.id },
                    ) { row ->
                        // Resolve original author profile for kind-6 reposts
                        val originalAuthorProfile = if (row.kind == 6) {
                            extractRepostAuthorPubkey(row.content, row.tags)
                                ?.let { viewModel.profileFlow(it).collectAsState().value }
                        } else null

                        if (row.kind == 30023) {
                            ArticleCard(
                                row             = row,
                                onClick         = { articleRow = row },
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
                        } else {
                            NoteCard(
                                row                    = row,
                                onNoteClick            = onNoteClick,
                                onAuthorClick          = onAuthorClick,
                                hasReacted             = row.engagementId in reactedIds,
                                hasReposted            = row.engagementId in repostedIds,
                                hasZapped              = row.engagementId in zappedIds,
                                isNwcConfigured        = isNwcConfigured,
                                originalAuthorProfile  = originalAuthorProfile,
                                onReact                = { actionsViewModel.react(row.id, row.pubkey) },
                                onRepost               = { actionsViewModel.repost(row.id, row.pubkey, row.relayUrl) },
                                onQuote                = onQuote,
                                onZap                  = { amt -> actionsViewModel.zap(row.id, row.pubkey, row.relayUrl, amt) },
                                onSaveNwcUri           = { uri -> actionsViewModel.saveNwcUri(uri) },
                                exoPlayer              = exoPlayer,
                                isActiveVideo          = row.id == activeVideoNoteId,
                                isMuted                = isMuted,
                                onToggleMute           = { isMuted = !isMuted },
                                onOpenFullscreen       = {
                                    activeVideoNoteId = row.id
                                    preFullscreenMuted = isMuted
                                    isMuted = false
                                    showFullscreenVideo = true
                                },
                                lookupProfile          = actionsViewModel::lookupProfile,
                                lookupEvent            = actionsViewModel::lookupEvent,
                                fetchOgMetadata        = actionsViewModel::fetchOgMetadata,
                                isNewPost              = row.id in newEventIds,
                                onNewPostAnimated      = { newEventIds.remove(row.id) },
                            )
                        }
                    }
                }

                // Auto-scroll: keyed on hasNewTopPost so it re-fires the moment a new
                // top post arrives. snapshotFlow on firstVisibleItemIndex can't work here
                // because LazyColumn preserves scroll position on prepend — the index
                // shifts from 0 to 1 when an item inserts above, so "index == 0" never
                // fires. Keying LaunchedEffect on the flag itself sidesteps that entirely.
                LaunchedEffect(viewModel.hasNewTopPost) {
                    if (viewModel.hasNewTopPost && listState.firstVisibleItemIndex <= 2) {
                        listState.scrollToItem(0)
                        viewModel.clearNewTopPost()
                    }
                }

                // Pagination: separate observer so it's not tangled with scroll-to-top.
                LaunchedEffect(Unit) {
                    snapshotFlow { listState.firstVisibleItemIndex }
                        .collect { index ->
                            val total = listState.layoutInfo.totalItemsCount
                            if (total > 0 && index > total * 0.5) {
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

                // Keep a stable reference that the long-lived LaunchedEffect can read
                // without restarting when the set changes (e.g. after pagination).
                val noteIdsWithVideoState = rememberUpdatedState(noteIdsWithVideo)
                val showFullscreenVideoState = rememberUpdatedState(showFullscreenVideo)

                // Active video detection: find video note closest to viewport center
                LaunchedEffect(Unit) {
                    snapshotFlow { listState.layoutInfo }
                        .map { layoutInfo ->
                            if (showFullscreenVideoState.value) return@map activeVideoNoteId
                            val currentIds = noteIdsWithVideoState.value
                            val viewportCenter = (layoutInfo.viewportStartOffset +
                                layoutInfo.viewportEndOffset) / 2
                            layoutInfo.visibleItemsInfo
                                .filter { (it.key as? String) in currentIds }
                                .minByOrNull {
                                    val itemCenter = it.offset + it.size / 2
                                    abs(itemCenter - viewportCenter)
                                }
                                ?.key as? String
                        }
                        .debounce(300)
                        .distinctUntilChanged()
                        .collect { newActiveId ->
                            if (activeVideoNoteId != newActiveId) {
                                activeVideoNoteId = newActiveId
                                if (newActiveId == null) {
                                    exoPlayer.playWhenReady = false
                                }
                            }
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

    if (showFullscreenVideo) {
        FullScreenVideoDialog(
            exoPlayer = exoPlayer,
            onDismiss = {
                showFullscreenVideo = false
                isMuted = preFullscreenMuted
            },
        )
    }
}

/**
 * Extract the first playable video URL from a FeedRow.
 * Uses imeta tags (MIME-based) then falls back to regex content extraction.
 */
internal fun extractVideoUrl(row: FeedRow): String? {
    // 1. Check imeta tags for video MIME types
    val imetaVideo = ImetaParser.videos(row.tags).firstOrNull()?.url
    if (imetaVideo != null) return imetaVideo

    // 2. Fall back to regex match on content
    return VIDEO_URL_REGEX.find(row.content)?.value
}
