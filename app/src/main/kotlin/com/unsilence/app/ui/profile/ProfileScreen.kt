package com.unsilence.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.ui.common.rememberSizedImageRequest
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.memory.toEventModel
import com.unsilence.app.data.relay.formatFollowerCount
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.common.ShimmerNoteCard
import com.unsilence.app.ui.feed.ArticleReaderScreen
import com.unsilence.app.ui.feed.FullScreenVideoDialog
import com.unsilence.app.ui.feed.NoteActionsViewModel
import com.unsilence.app.ui.feed.NostrRichText
import com.unsilence.app.ui.feed.engagementId
import com.unsilence.app.ui.feed.toCompactSats
import com.unsilence.app.ui.shared.EngagementSnapshot
import com.unsilence.app.ui.shared.EventActionCallbacks
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.shared.PostActionsHost
import com.unsilence.app.ui.shared.WotInlineLabel
import com.unsilence.app.ui.shared.WotBreakdownProvenance
import com.unsilence.app.ui.shared.eventFeedItems
import com.unsilence.app.ui.shared.pollActionCallbacks
import com.unsilence.app.ui.shared.rememberVideoPlaybackScope
import com.unsilence.app.ui.shared.threadParentVideoSourceCandidateIds
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Chat
import com.unsilence.app.ui.common.EmptyState
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.TextSecondary

private val BANNER_HEIGHT       = 200.dp   // φ³ region — taller for visual impact
private val PROFILE_AVATAR_SIZE = 85.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onLogout: () -> Unit = {},
    onBack: () -> Unit = {},
    onNoteClick: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onAuthorClick: (pubkey: String) -> Unit = {},
    onConnectionsClick: (ConnectionsTab) -> Unit = {},
    onHashtagClick: (String) -> Unit = {},
    onBrowseRelay: (url: String, label: String) -> Unit = { _, _ -> },
    viewModel: ProfileViewModel = hiltViewModel(
        key = "profile-${LocalAppSessionKey.current}",
    ),
    actionsViewModel: NoteActionsViewModel = hiltViewModel(
        key = "note-actions-${LocalAppSessionKey.current}",
    ),
) {
    val user            by viewModel.userFlow.collectAsStateWithLifecycle(initialValue = null)
    val posts           by viewModel.tabPostsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val sensitiveMode   by viewModel.sensitiveContentMode.collectAsStateWithLifecycle()
    val selectedTab     by viewModel.selectedTab.collectAsStateWithLifecycle()
    val followingCount  by viewModel.followingCount.collectAsStateWithLifecycle()
    val followerCount   by viewModel.followerCount.collectAsStateWithLifecycle()
    val wotLookups      by viewModel.wotLookups.collectAsStateWithLifecycle()
    val profileWotLookup by viewModel.profileWotLookup.collectAsStateWithLifecycle()
    val wotProvenance   by viewModel.wotProvenance.collectAsStateWithLifecycle()
    val feedWotDisplayMode by viewModel.feedWotDisplayMode.collectAsStateWithLifecycle()
    val isLoadingPosts  by viewModel.isLoadingPosts.collectAsStateWithLifecycle()
    val reactedIds      by actionsViewModel.reactedEventIds.collectAsStateWithLifecycle()
    val repostedIds     by actionsViewModel.repostedEventIds.collectAsStateWithLifecycle()
    val zappedIds       by actionsViewModel.zappedEventIds.collectAsStateWithLifecycle()
    val zapLoadingIds   by actionsViewModel.zapLoading.collectAsStateWithLifecycle()
    val optimisticSats  by actionsViewModel.optimisticZapSats.collectAsStateWithLifecycle()
    val zapFlash        by actionsViewModel.zapFlashState.collectAsStateWithLifecycle()
    val isNwcConfigured = actionsViewModel.isNwcConfigured
    val clipboard        = LocalClipboardManager.current
    val showSnackbar     = LocalShowSnackbar.current

    var showEditProfile by remember { mutableStateOf(false) }
    var showSettings    by remember { mutableStateOf(false) }
    var articleRow      by remember { mutableStateOf<FeedRow?>(null) }
    var actionsRow      by remember { mutableStateOf<FeedRow?>(null) }
    var showWotBreakdown by remember { mutableStateOf(false) }

    // ── Emoji reaction picker state ─────────────────────────────────────────
    var emojiReactTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showFullEmojiPicker by remember { mutableStateOf(false) }
    val openEmojiSettings = com.unsilence.app.ui.common.LocalOpenEmojiSettings.current
    val pinnedShortcodes by actionsViewModel.pinnedEmojiShortcodes.collectAsStateWithLifecycle()

    // ── Action failure snackbar ──────────────────────────────────────────────
    LaunchedEffect(Unit) {
        actionsViewModel.actionError.collect { showSnackbar(it) }
    }
    val listState = rememberLazyListState()
    val cardWidthPx = LocalWindowInfo.current.containerSize.width
    val scope = rememberCoroutineScope()

    // Intercept avatar tap: own pubkey → scroll to top, other → navigate
    val interceptedAuthorClick: (String) -> Unit = { tappedPubkey ->
        if (tappedPubkey == viewModel.pubkeyHex) {
            scope.launch { listState.animateScrollToItem(0) }
        } else {
            onAuthorClick(tappedPubkey)
        }
    }
    val showThreadParents = selectedTab == ProfileTab.REPLIES

    // ── Shared video playback — replaces ~80 lines of duplicated state ────────
    val videoScope = rememberVideoPlaybackScope(
        ownerId = "profile",
        holder = actionsViewModel.sharedPlayerHolder,
        events = posts,
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
    val pinnedEmojis = remember(pinnedShortcodes) { actionsViewModel.getPinnedEmojis() }
    val callbacks = remember(viewModel, actionsViewModel, pinnedEmojis, wotLookups, feedWotDisplayMode) { EventActionCallbacks(
        onNoteClick = onNoteClick,
        onComment = onComment,
        onAuthorClick = interceptedAuthorClick,
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
        wotLookup = { key -> wotLookups[key] },
        feedWotDisplayMode = feedWotDisplayMode,
        poll = actionsViewModel.pollActionCallbacks(),
        onWotSubjectsVisible = viewModel::requestWotHydration,
        onLongPress = { row -> actionsRow = row },
    ) }

    val displayName = user?.displayName?.takeIf { it.isNotBlank() }
        ?: user?.name?.takeIf { it.isNotBlank() }
        ?: viewModel.pubkeyHex?.let { "${it.take(6)}…${it.takeLast(4)}" }

    // first6…last4 of npub per design spec
    val npubShort = viewModel.npub?.let {
        "${it.take(6)}…${it.takeLast(4)}"
    }
    val scoredWotInline = profileWotLookup as? WotLookup.Scored

    // ── Swipe left/right to switch profile tabs ────────────────────────────
    val swipeDrag = remember { mutableFloatStateOf(0f) }
    val tabs = ProfileTab.entries

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        // ── Scrollable content ────────────────────────────────────────────────
        LazyColumn(
            state               = listState,
            modifier            = Modifier
                .fillMaxSize()
                .pointerInput(selectedTab) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val threshold = 100.dp.toPx()
                            val curIdx = tabs.indexOf(selectedTab)
                            if (swipeDrag.floatValue > threshold && curIdx > 0) {
                                viewModel.selectTab(tabs[curIdx - 1])
                            } else if (swipeDrag.floatValue < -threshold && curIdx < tabs.lastIndex) {
                                viewModel.selectTab(tabs[curIdx + 1])
                            }
                            swipeDrag.floatValue = 0f
                        },
                        onDragCancel = { swipeDrag.floatValue = 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            swipeDrag.floatValue += dragAmount
                        },
                    )
                },
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
                            val bannerDensity = LocalDensity.current
                            val bannerWidthPx = LocalWindowInfo.current.containerSize.width.coerceAtLeast(1)
                            val bannerHeightPx = with(bannerDensity) { 200.dp.roundToPx() }
                            AsyncImage(
                                model              = rememberSizedImageRequest(bannerUrl, bannerWidthPx, bannerHeightPx),
                                contentDescription = null,
                                contentScale       = ContentScale.Crop,
                                modifier           = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Surface1),
                            )
                        }
                    }

                    // Avatar overlapping banner bottom
                    val avatarBorderColor = if (user?.banner.isNullOrBlank())
                        Color.White.copy(alpha = 0.2f) else Black
                    ProfileAvatar(
                        pubkeyHex  = viewModel.pubkeyHex,
                        pictureUrl = user?.picture,
                        modifier   = Modifier
                            .size(PROFILE_AVATAR_SIZE)
                            .clip(CircleShape)
                            .border(1.dp, avatarBorderColor, CircleShape),
                    )
                }

                Spacer(Modifier.height(Spacing.small))

                // Display name
                if (displayName != null) {
                    Text(
                        text       = displayName,
                        color      = Color.White,
                        fontSize   = AppType.heading,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.medium),
                    )
                    Spacer(Modifier.height(Spacing.micro))
                }

                // npub — tappable row with copy icon
                if (npubShort != null) {
                    Row(
                        modifier = Modifier
                            .clickable {
                                viewModel.npub?.let { full ->
                                    clipboard.setText(AnnotatedString(full))
                                    showSnackbar("Copied npub")
                                }
                            }
                            .padding(horizontal = Spacing.medium, vertical = 2.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text     = npubShort,
                            color    = TextSecondary,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (scoredWotInline != null) {
                            Text(
                                text = " · ",
                                color = Text3,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                            )
                            WotInlineLabel(
                                assertion = scoredWotInline.assertion,
                                onClick = { showWotBreakdown = true },
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector        = Icons.Outlined.ContentCopy,
                            contentDescription = "Copy npub",
                            tint               = TextSecondary,
                            modifier           = Modifier.size(14.dp),
                        )
                    }
                    Spacer(Modifier.height(Spacing.micro))
                }

                // NIP-05 badge
                val nip05 = user?.nip05?.takeIf { it.isNotBlank() }
                if (nip05 != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier          = Modifier.padding(bottom = Spacing.micro),
                    ) {
                        Icon(
                            imageVector        = Icons.Filled.Verified,
                            contentDescription = "NIP-05 verified",
                            tint               = Brand,
                            modifier           = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text     = nip05,
                            color    = TextSecondary,
                            fontSize = AppType.bodySmall,
                        )
                    }
                }

                // Bio / about (with inline @mention resolution)
                val about = user?.about?.takeIf { it.isNotBlank() }
                if (about != null) {
                    NostrRichText(
                        content       = about,
                        lookupProfile = actionsViewModel::lookupProfile,
                        onAuthorClick = interceptedAuthorClick,
                        onTextClick   = {},
                        maxLines      = 3,
                        overflow      = TextOverflow.Ellipsis,
                        textAlign     = TextAlign.Center,
                        modifier      = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.medium),
                    )
                    Spacer(Modifier.height(Spacing.micro))
                }

                // Following / Followers stats row
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    StatLabel(
                        label = "Following",
                        value = "$followingCount",
                        onClick = { onConnectionsClick(ConnectionsTab.Following) },
                    )
                    Spacer(Modifier.size(Spacing.large))
                    StatLabel(
                        label = "Followers",
                        value = followerCount?.let(::formatFollowerCount) ?: "—",
                        onClick = { onConnectionsClick(ConnectionsTab.Followers) },
                    )
                }
                (profileWotLookup as? WotLookup.Scored)
                    ?.assertion
                    ?.verifiedFollowers
                    ?.let { verified ->
                        Spacer(Modifier.height(Spacing.micro))
                        Text(
                            text = "${verified.toCompactSats()} verified in your grapevine",
                            color = Text3,
                            fontSize = AppType.caption,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }

                Spacer(Modifier.height(Spacing.medium))
            }

            // ── Profile tabs ──────────────────────────────────────────────────
            item {
                ProfileTabRow(
                    selectedTab   = selectedTab,
                    onTabSelected = { viewModel.selectTab(it) },
                )
            }

            // ── Post list ────────────────────────────────────────────────────
            if (posts.isEmpty() && isLoadingPosts) {
                items(3) { ShimmerNoteCard(showMedia = it == 0) }
            } else if (posts.isEmpty()) {
                item {
                    EmptyState(
                        icon    = when (selectedTab) {
                            ProfileTab.NOTES    -> Icons.Outlined.Chat
                            ProfileTab.REPLIES  -> Icons.AutoMirrored.Outlined.Reply
                            ProfileTab.LONGFORM -> Icons.Outlined.Article
                        },
                        message = when (selectedTab) {
                            ProfileTab.NOTES    -> "No notes yet"
                            ProfileTab.REPLIES  -> "No replies yet"
                            ProfileTab.LONGFORM -> "No articles yet"
                        },
                        modifier = Modifier.height(200.dp),
                    )
                }
            } else {
                eventFeedItems(
                    events = posts,
                    engagement = engagement,
                    callbacks = callbacks,
                    videoScope = videoScope,
                    role = CardRole.Profile,
                    thumbnailCache = actionsViewModel.videoThumbnailCache,
                    imageDimensionCache = actionsViewModel.imageDimensionCache,
                    showThreadParents = showThreadParents,
                    eventModelProvider = actionsViewModel::getEventModel,
                    sensitiveMode = sensitiveMode,
                )
            }

            item { Spacer(Modifier.height(Spacing.xl)) }
        }

        // ── Infinite scroll trigger ──────────────────────────────────────────
        val shouldLoadMore = remember {
            derivedStateOf {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val totalItems = listState.layoutInfo.totalItemsCount
                totalItems > 0 && lastVisible >= totalItems / 2
            }
        }
        LaunchedEffect(Unit) {
            snapshotFlow {
                if (shouldLoadMore.value) posts.lastOrNull()?.createdAt else null
            }
                .distinctUntilChanged()
                .collect { oldestVisiblePageCursor ->
                    if (oldestVisiblePageCursor != null) {
                        viewModel.loadMore(oldestVisiblePageCursor)
                    }
                }
        }

        // Viewport tracking for zone-aware hydration
        @OptIn(FlowPreview::class)
        LaunchedEffect(Unit) {
            snapshotFlow {
                val info = listState.layoutInfo
                val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                Triple(first, last, listState.isScrollInProgress)
            }.sample(100).collect { (first, last, isScrolling) ->
                viewModel.onViewportChanged(first, last, isScrolling)
            }
        }

        @OptIn(FlowPreview::class)
        LaunchedEffect(posts, cardWidthPx) {
            val eventOffset = 3
            fun warmVisibleRange(first: Int, last: Int) {
                val dataFirst = (first - eventOffset).coerceAtLeast(0)
                val dataLast = (last - eventOffset).coerceAtMost(posts.lastIndex)
                if (dataFirst <= dataLast) {
                    actionsViewModel.warmCardWindow(
                        rows = posts,
                        first = dataFirst,
                        last = dataLast,
                        cardWidthPx = cardWidthPx,
                        hydrateEngagement = false,
                    )
                }
            }

            if (posts.isNotEmpty() && cardWidthPx > 0) {
                val info = listState.layoutInfo
                val first = info.visibleItemsInfo.firstOrNull()?.index ?: eventOffset
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: (eventOffset + 8)
                warmVisibleRange(first, last)
            }

            snapshotFlow {
                val info = listState.layoutInfo
                val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                first to last
            }.sample(100).collect { (first, last) ->
                warmVisibleRange(first, last)
            }
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
                // In-context edit entry — matches the Settings gear (white, 22dp).
                IconButton(onClick = { showEditProfile = true }) {
                    Icon(
                        imageVector        = Icons.Filled.Edit,
                        contentDescription = "Edit profile",
                        tint               = Color.White,
                        modifier           = Modifier.size(22.dp),
                    )
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
            onEditProfile = { showEditProfile = true },
            onOpenProfile = onAuthorClick,
            onBrowseRelay = onBrowseRelay,
        )
    }
    if (showEditProfile) {
        EditProfileScreen(
            viewModel = viewModel,
            onDismiss = { showEditProfile = false },
        )
    }
    PostActionsHost(
        row = actionsRow,
        profileFlow = viewModel::profileFlow,
        canDelete = { row -> actionsViewModel.isOwnPubkey(row.pubkey) },
        onMuteUser = actionsViewModel::muteUser,
        onReport = { row, type -> actionsViewModel.reportEvent(row.id, row.pubkey, type) },
        onDelete = { row -> actionsViewModel.deleteEvent(row.id, row.pubkey, row.relayUrl) },
        onDismiss = { actionsRow = null },
        showModerationActions = false,
    )
    articleRow?.let { row ->
        // Effective engagement target (kind-6/16 reposts → original event).
        val model = remember(row.id, row.content, row.tags) { row.toEventModel() }
        ArticleReaderScreen(
            row             = row,
            onDismiss       = { articleRow = null },
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
            onZap           = { req -> actionsViewModel.zap(model.engagementId, model.pubkey, row.relayUrl, req) },
            onSaveNwcUri    = { uri -> actionsViewModel.saveNwcUri(uri) },
            hasReacted      = row.engagementId in reactedIds,
            hasReposted     = row.engagementId in repostedIds,
            hasZapped       = row.engagementId in zappedIds,
            isNwcConfigured = isNwcConfigured,
            isZapLoading    = model.engagementId in zapLoadingIds,
            extraZapSats    = optimisticSats[model.engagementId] ?: 0L,
            zapFlash        = zapFlash,
            onAuthorClick   = interceptedAuthorClick,
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

    val scoredWot = profileWotLookup as? WotLookup.Scored
    if (showWotBreakdown && scoredWot != null) {
        ModalBottomSheet(onDismissRequest = { showWotBreakdown = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium)
                    .padding(bottom = Spacing.xl),
            ) {
                Text(
                    text = "Web of trust",
                    color = Color.White,
                    fontSize = AppType.subheading,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(Spacing.medium))
                ScoredStanding(scoredWot.assertion)
                Spacer(Modifier.height(Spacing.medium))
                WotBreakdownProvenance(
                    text = "Via ${wotProvenance.providerName} grapevine · ${profileWotSyncedAgo(wotProvenance.lastFetchAt)}",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    // ── Full emoji picker sheet ─────────────────────────────────────────────
    if (showFullEmojiPicker && emojiReactTarget != null) {
        val (eventId, pubkey) = emojiReactTarget!!
        com.unsilence.app.ui.feed.EmojiPickerSheet(
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
                model              = rememberAvatarImageRequest(pictureUrl, 85.dp),
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun StatLabel(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text       = value,
            color      = Color.White,
            fontSize   = AppType.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text     = label,
            color    = TextSecondary,
            fontSize = AppType.bodySmall,
        )
    }
}
