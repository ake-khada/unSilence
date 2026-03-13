package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.relay.extractRepostAuthorPubkey
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.media3.exoplayer.ExoPlayer
import com.unsilence.app.data.relay.ImetaParser
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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

    // ── Video autoplay state ─────────────────────────────────────────────────
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = ExoPlayer.REPEAT_MODE_ALL
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    var activeVideoNoteId by remember { mutableStateOf<String?>(null) }
    var isMuted by remember { mutableStateOf(true) }
    var showFullscreenVideo by remember { mutableStateOf(false) }
    var preFullscreenMuted by remember { mutableStateOf(true) }

    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) listState.animateScrollToItem(0)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        when {
            state.loading -> {
                CircularProgressIndicator(
                    color    = Cyan,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            state.events.isEmpty() -> {
                Text(
                    text       = "No posts yet.\nConnect to a relay to load the feed.",
                    color      = TextSecondary,
                    fontSize   = 15.sp,
                    textAlign  = TextAlign.Center,
                    lineHeight = 22.sp,
                    modifier   = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = Spacing.xl),
                )
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
                                row     = row,
                                onClick = { articleRow = row },
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

                // Keep a stable reference that the long-lived LaunchedEffect can read
                // without restarting when the set changes (e.g. after pagination).
                val noteIdsWithVideoState = rememberUpdatedState(noteIdsWithVideo)

                // Active video detection: find video note closest to viewport center
                LaunchedEffect(Unit) {
                    snapshotFlow { listState.layoutInfo }
                        .map { layoutInfo ->
                            if (showFullscreenVideo) return@map activeVideoNoteId
                            val currentIds = noteIdsWithVideoState.value
                            val viewportCenter = (layoutInfo.viewportStartOffset +
                                layoutInfo.viewportEndOffset) / 2
                            layoutInfo.visibleItemsInfo
                                .filter { (it.key as? String) in currentIds }
                                .minByOrNull {
                                    val itemCenter = it.offset + it.size / 2
                                    kotlin.math.abs(itemCenter - viewportCenter)
                                }
                                ?.key as? String
                        }
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

    articleRow?.let { row ->
        ArticleReaderScreen(row = row, onDismiss = { articleRow = null })
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
