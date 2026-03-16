package com.unsilence.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.unsilence.app.ui.feed.toCompactSats
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
fun ProfileScreen(
    onLogout: () -> Unit = {},
    onBack: () -> Unit = {},
    onNoteClick: (String) -> Unit = {},
    onAuthorClick: (pubkey: String) -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel(),
    actionsViewModel: NoteActionsViewModel = hiltViewModel(),
) {
    val user            by viewModel.userFlow.collectAsStateWithLifecycle(initialValue = null)
    val posts           by viewModel.tabPostsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedTab     by viewModel.selectedTab.collectAsStateWithLifecycle()
    val followingCount  by viewModel.followingCount.collectAsStateWithLifecycle()
    val followerCount   by viewModel.followerCount.collectAsStateWithLifecycle()
    val isLoadingPosts  by viewModel.isLoadingPosts.collectAsStateWithLifecycle()
    val reactedIds      by actionsViewModel.reactedEventIds.collectAsStateWithLifecycle()
    val repostedIds     by actionsViewModel.repostedEventIds.collectAsStateWithLifecycle()
    val zappedIds       by actionsViewModel.zappedEventIds.collectAsStateWithLifecycle()
    val isNwcConfigured = actionsViewModel.isNwcConfigured
    val clipboard        = LocalClipboardManager.current

    var showEditProfile by remember { mutableStateOf(false) }
    var showSettings    by remember { mutableStateOf(false) }
    var articleRow      by remember { mutableStateOf<FeedRow?>(null) }
    val listState = rememberLazyListState()

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
        ?: viewModel.pubkeyHex?.let { "${it.take(6)}…${it.takeLast(4)}" }

    // first6…last4 of npub per design spec
    val npubShort = viewModel.npub?.let {
        "${it.take(6)}…${it.takeLast(4)}"
    }

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
            // Space for our own top bar overlay
            item {
                Spacer(Modifier.height(Sizing.topBarHeight + 8.dp))
            }

            // ── Profile header ───────────────────────────────────────────────
            item {
                // Banner + avatar overlap composite.
                Box(
                    modifier         = Modifier
                        .fillMaxWidth()
                        .height(BANNER_HEIGHT + PROFILE_AVATAR_SIZE / 2),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    // Banner
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

                    // Avatar overlapping banner bottom
                    ProfileAvatar(
                        pubkeyHex  = viewModel.pubkeyHex,
                        pictureUrl = user?.picture,
                        modifier   = Modifier
                            .size(PROFILE_AVATAR_SIZE)
                            .clip(CircleShape)
                            .border(2.dp, Black, CircleShape),
                    )
                }

                Spacer(Modifier.height(Spacing.medium))

                // Display name
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

                // npub — tappable to copy full npub
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

                // NIP-05 badge
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

                // Bio / about
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

                // Following / Followers stats row
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    StatLabel(label = "Following", value = "$followingCount")
                    Spacer(Modifier.size(20.dp))
                    StatLabel(
                        label = "Followers",
                        value = followerCount?.let { "~${it.toCompactSats()}" } ?: "—",
                    )
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

            // ── Post list ────────────────────────────────────────────────────
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

        // ── Own top bar overlay ───────────────────────────────────────────────
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
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint               = Color.White,
                        )
                    }
                    TextButton(onClick = { showEditProfile = true }) {
                        Text(
                            text     = "Edit Profile",
                            color    = Cyan,
                            fontSize = 14.sp,
                        )
                    }
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(
                        imageVector        = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint               = Color.White,
                        modifier           = Modifier.size(22.dp),
                    )
                }
            }
        }
    }

    // ── Overlays ──────────────────────────────────────────────────────────────
    if (showSettings) {
        SettingsScreen(
            onDismiss = { showSettings = false },
            onLogout  = onLogout,
        )
    }
    if (showEditProfile) {
        EditProfileScreen(
            viewModel = viewModel,
            onDismiss = { showEditProfile = false },
        )
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

// ── Private sub-composables ───────────────────────────────────────────────────

@Composable
private fun ProfileAvatar(
    pubkeyHex: String?,
    pictureUrl: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (pubkeyHex != null) {
            IdentIcon(pubkey = pubkeyHex, modifier = Modifier.fillMaxSize())
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF333333)))
        }
        if (!pictureUrl.isNullOrBlank()) {
            AsyncImage(
                model              = pictureUrl,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun StatLabel(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text       = value,
            color      = Color.White,
            fontSize   = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text     = label,
            color    = TextSecondary,
            fontSize = 13.sp,
        )
    }
}
