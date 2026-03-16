package com.unsilence.app.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.unsilence.app.data.relay.ImetaParser
import com.unsilence.app.data.relay.extractRepostAuthorPubkey
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.common.ShimmerNoteCard
import com.unsilence.app.ui.feed.ArticleCard
import com.unsilence.app.ui.feed.ArticleReaderScreen
import com.unsilence.app.ui.feed.FullScreenVideoDialog
import com.unsilence.app.ui.feed.NoteActionsViewModel
import com.unsilence.app.ui.feed.NoteCard
import com.unsilence.app.ui.feed.VIDEO_URL_REGEX
import com.unsilence.app.ui.feed.engagementId
import com.unsilence.app.ui.feed.extractVideoUrl
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import kotlin.math.abs
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val BANNER_HEIGHT       = 150.dp
private val PROFILE_AVATAR_SIZE = 85.dp

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun UserProfileScreen(
    pubkey: String,
    onDismiss: () -> Unit,
    onNoteClick: (String) -> Unit = {},
    onAuthorClick: (pubkey: String) -> Unit = {},
    viewModel: UserProfileViewModel = hiltViewModel(),
    actionsViewModel: NoteActionsViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onDismiss)
    LaunchedEffect(pubkey) { viewModel.loadProfile(pubkey) }

    val pubkeyHex       by viewModel.pubkeyHex.collectAsStateWithLifecycle()
    val user            by viewModel.userFlow.collectAsStateWithLifecycle(initialValue = null)
    val posts           by viewModel.tabPostsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedTab     by viewModel.selectedTab.collectAsStateWithLifecycle()
    val isLoadingPosts  by viewModel.isLoadingPosts.collectAsStateWithLifecycle()
    val reactedIds      by actionsViewModel.reactedEventIds.collectAsStateWithLifecycle()
    val repostedIds     by actionsViewModel.repostedEventIds.collectAsStateWithLifecycle()
    val zappedIds       by actionsViewModel.zappedEventIds.collectAsStateWithLifecycle()
    val isNwcConfigured = actionsViewModel.isNwcConfigured
    val clipboard        = LocalClipboardManager.current
    val isFollowing    by viewModel.isFollowing.collectAsStateWithLifecycle(initialValue = false)
    val followLoading  by viewModel.followLoading.collectAsStateWithLifecycle()

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

    // Pause playback when app goes to background, resume when foregrounded
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP  -> exoPlayer.playWhenReady = false
                Lifecycle.Event.ON_START -> if (activeVideoNoteId != null) exoPlayer.playWhenReady = true
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── Single-owner playback transitions ─────────────────────────────────────
    val activeVideoUrl = remember(activeVideoNoteId, posts) {
        activeVideoNoteId?.let { noteId ->
            posts.firstOrNull { it.id == noteId }?.let { extractVideoUrl(it) }
        }
    }

    LaunchedEffect(activeVideoUrl) {
        val currentUrl = exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString()

        if (activeVideoUrl == null) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.playWhenReady = false
            return@LaunchedEffect
        }

        if (activeVideoUrl == currentUrl) return@LaunchedEffect

        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.playWhenReady = false
        exoPlayer.setMediaItem(MediaItem.fromUri(activeVideoUrl))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // Trigger loadMore() when scrolled near bottom
    val shouldLoadMore = remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisible >= totalItems - 5
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { shouldLoadMore.value }
            .distinctUntilChanged()
            .collect { shouldLoad ->
                if (shouldLoad && posts.isNotEmpty()) {
                    val oldest = posts.last().createdAt
                    viewModel.loadMore(oldest)
                }
            }
    }

    // Precompute which notes have video for scroll detection
    val noteIdsWithVideo = remember(posts) {
        posts.filter { row ->
            row.kind != 30023 &&
            (ImetaParser.videos(row.tags).isNotEmpty() ||
                VIDEO_URL_REGEX.containsMatchIn(row.content))
        }.map { it.id }.toSet()
    }

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

    val displayName = user?.displayName?.takeIf { it.isNotBlank() }
        ?: user?.name?.takeIf { it.isNotBlank() }
        ?: pubkeyHex?.let { "${it.take(6)}…${it.takeLast(4)}" }

    val npubShort = viewModel.npub?.let { "${it.take(6)}…${it.takeLast(4)}" }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        // ── Scrollable content ────────────────────────────────────────────────
        LazyColumn(
            state               = listState,
            modifier            = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Space for top bar (statusBar + topBarHeight)
            item { Spacer(Modifier.statusBarsPadding().height(Sizing.topBarHeight + 8.dp)) }

            // ── Profile header ────────────────────────────────────────────────
            item {
                // Banner + avatar overlap
                Box(
                    modifier         = Modifier
                        .fillMaxWidth()
                        .height(BANNER_HEIGHT + PROFILE_AVATAR_SIZE / 2),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(BANNER_HEIGHT)
                            .align(Alignment.TopStart),
                    ) {
                        val bannerUrl = user?.banner
                        if (!bannerUrl.isNullOrBlank()) {
                            AsyncImage(
                                model              = bannerUrl,
                                contentDescription = null,
                                contentScale       = ContentScale.Crop,
                                modifier           = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF1A1A1A)),
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(PROFILE_AVATAR_SIZE)
                            .clip(CircleShape)
                            .border(2.dp, Black, CircleShape),
                    ) {
                        if (pubkeyHex != null) {
                            IdentIcon(pubkey = pubkeyHex!!, modifier = Modifier.fillMaxSize())
                        }
                        if (!user?.picture.isNullOrBlank()) {
                            AsyncImage(
                                model              = user?.picture,
                                contentDescription = null,
                                contentScale       = ContentScale.Crop,
                                modifier           = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.medium))

                if (displayName != null) {
                    Text(
                        text       = displayName,
                        color      = Color.White,
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center,
                        modifier   = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.medium),
                    )
                    Spacer(Modifier.height(Spacing.small))
                }

                if (npubShort != null) {
                    Text(
                        text      = npubShort,
                        color     = TextSecondary,
                        fontSize  = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.medium)
                            .clickable {
                                viewModel.npub?.let { full ->
                                    clipboard.setText(AnnotatedString(full))
                                }
                            },
                    )
                    Spacer(Modifier.height(Spacing.small))
                }

                val nip05 = user?.nip05?.takeIf { it.isNotBlank() }
                if (nip05 != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier          = Modifier.padding(bottom = Spacing.small),
                    ) {
                        Icon(
                            imageVector        = Icons.Filled.Verified,
                            contentDescription = "NIP-05 verified",
                            tint               = Cyan,
                            modifier           = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text     = nip05,
                            color    = TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                }

                val about = user?.about?.takeIf { it.isNotBlank() }
                if (about != null) {
                    Text(
                        text      = about,
                        color     = Color.White,
                        fontSize  = 14.sp,
                        textAlign = TextAlign.Center,
                        maxLines  = 3,
                        overflow  = TextOverflow.Ellipsis,
                        modifier  = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.medium),
                    )
                    Spacer(Modifier.height(Spacing.small))
                }

                // Follow/Unfollow button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (followLoading) {
                        CircularProgressIndicator(
                            color    = Cyan,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else if (isFollowing) {
                        OutlinedButton(
                            onClick = { viewModel.toggleFollow() },
                            border  = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                                brush = androidx.compose.ui.graphics.SolidColor(Cyan),
                            ),
                            modifier = Modifier.widthIn(min = 120.dp),
                        ) {
                            Text("Following", color = Cyan, fontSize = 14.sp)
                        }
                    } else {
                        Button(
                            onClick  = { viewModel.toggleFollow() },
                            colors   = ButtonDefaults.buttonColors(containerColor = Cyan),
                            modifier = Modifier.widthIn(min = 120.dp),
                        ) {
                            Text("Follow", color = Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.large))
            }

            // ── Profile tabs ──────────────────────────────────────────────────
            item {
                ProfileTabRow(
                    selectedTab   = selectedTab,
                    onTabSelected = { viewModel.selectedTab.value = it },
                )
            }

            // ── Post list ─────────────────────────────────────────────────────
            if (posts.isEmpty() && isLoadingPosts) {
                items(3) { ShimmerNoteCard(showMedia = it == 0) }
            } else if (posts.isEmpty()) {
                item {
                    Text(
                        text     = when (selectedTab) {
                            ProfileTab.NOTES    -> "No notes yet"
                            ProfileTab.REPLIES  -> "No replies yet"
                            ProfileTab.LONGFORM -> "No articles yet"
                        },
                        color    = TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.medium),
                    )
                }
            } else {
                items(items = posts, key = { it.id }) { row ->
                    if (row.kind == 30023) {
                        ArticleCard(
                            row             = row,
                            onClick         = { articleRow = row },
                            onReact         = { actionsViewModel.react(row.id, row.pubkey) },
                            onRepost        = { actionsViewModel.repost(row.id, row.pubkey, row.relayUrl) },
                            onZap           = { amt -> actionsViewModel.zap(row.id, row.pubkey, row.relayUrl, amt) },
                            onSaveNwcUri    = { uri -> actionsViewModel.saveNwcUri(uri) },
                            hasReacted      = row.engagementId in reactedIds,
                            hasReposted     = row.engagementId in repostedIds,
                            hasZapped       = row.engagementId in zappedIds,
                            isNwcConfigured = isNwcConfigured,
                        )
                    } else {
                        // Resolve original author profile for kind-6 reposts
                        val originalAuthorProfile = if (row.kind == 6) {
                            extractRepostAuthorPubkey(row.content, row.tags)
                                ?.let { viewModel.profileFlow(it).collectAsState().value }
                        } else null

                        NoteCard(
                            row                    = row,
                            onNoteClick            = onNoteClick,
                            originalAuthorProfile  = originalAuthorProfile,
                            onAuthorClick          = onAuthorClick,
                            hasReacted             = row.engagementId in reactedIds,
                            hasReposted            = row.engagementId in repostedIds,
                            hasZapped              = row.engagementId in zappedIds,
                            isNwcConfigured        = isNwcConfigured,
                            onReact                = { actionsViewModel.react(row.id, row.pubkey) },
                            onRepost               = { actionsViewModel.repost(row.id, row.pubkey, row.relayUrl) },
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
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(Spacing.xl)) }
        }

        // ── Top bar overlay ───────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Black)
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Sizing.topBarHeight)
                    .padding(horizontal = Spacing.small),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint               = Color.White,
                    )
                }
                Text(
                    text       = "Profile",
                    color      = Color.White,
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    articleRow?.let { row ->
        ArticleReaderScreen(
            row             = row,
            onDismiss       = { articleRow = null },
            onReact         = { actionsViewModel.react(row.id, row.pubkey) },
            onRepost        = { actionsViewModel.repost(row.id, row.pubkey, row.relayUrl) },
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
