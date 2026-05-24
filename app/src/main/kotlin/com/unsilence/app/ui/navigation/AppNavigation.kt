package com.unsilence.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.unsilence.app.ui.common.LogoMark
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.data.memory.RelaySet
import com.unsilence.app.data.memory.RelayHealthInfo
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.ui.compose.ComposeScreen
import com.unsilence.app.ui.feed.FeedScreen
import com.unsilence.app.ui.feed.FeedType
import com.unsilence.app.ui.feed.FeedViewModel
import com.unsilence.app.ui.feed.FilterBottomSheet
import com.unsilence.app.ui.notifications.NotifFilter
import com.unsilence.app.ui.notifications.NotificationsScreen
import com.unsilence.app.ui.notifications.NotificationsViewModel
import com.unsilence.app.ui.profile.ProfileScreen
import com.unsilence.app.ui.profile.UserProfileScreen
import com.unsilence.app.ui.relays.CreateRelaySetScreen
import com.unsilence.app.ui.relays.RelayManagementScreen
import com.unsilence.app.ui.relays.RelayManagementViewModel
import com.unsilence.app.ui.search.SearchScreen
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.BrandDeep
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.Warn
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.thread.ThreadScreen
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val NavUnselected = Text3

private data class NavTab(val icon: ImageVector, val contentDescription: String)

private val TABS = listOf(
    NavTab(Icons.Outlined.Home,          "Home"),
    NavTab(Icons.Outlined.Search,        "Search"),
    NavTab(Icons.Outlined.Notifications, "Notifications"),
    NavTab(Icons.Outlined.Person,        "Profile"),
)

private val animSpec = tween<androidx.compose.ui.unit.Dp>(250, easing = FastOutSlowInEasing)

// ── Utilities ──────────────────────────────────────────────────────────────

private fun feedTypeMatches(a: FeedType, b: FeedType): Boolean = when {
    a is FeedType.Global && b is FeedType.Global -> true
    a is FeedType.Following && b is FeedType.Following -> true
    a is FeedType.RelaySet && b is FeedType.RelaySet -> a.dTag == b.dTag
    a is FeedType.SingleRelay && b is FeedType.SingleRelay -> a.url == b.url
    else -> false
}

@Composable
fun AppNavigation(userPubkey: String, onLogout: () -> Unit) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showSnackbar: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }
    var selectedTab          by rememberSaveable { mutableIntStateOf(0) }
    var barsVisible          by remember { mutableStateOf(true) }
    var showCompose          by remember { mutableStateOf(false) }
    var showFeedSheet        by remember { mutableStateOf(false) }
    var showFilter           by remember { mutableStateOf(false) }
    var showCreateRelaySet   by remember { mutableStateOf(false) }
    var showRelaySettings    by remember { mutableStateOf(false) }
    var threadEventId        by remember { mutableStateOf<String?>(null) }
    var replyToEventId       by remember { mutableStateOf<String?>(null) }
    var quoteNoteId          by remember { mutableStateOf<String?>(null) }
    var userProfilePubkey    by remember { mutableStateOf<String?>(null) }
    var scrollToTopTrigger   by remember { mutableIntStateOf(0) }
    var showEmojiSettings    by remember { mutableStateOf(false) }
    var hashtagSearchQuery   by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = selectedTab != 0) { selectedTab = 0 }

    val onAuthorClick: (String) -> Unit = { pubkey -> userProfilePubkey = pubkey }
    val onHashtagClick: (String) -> Unit = { tag ->
        hashtagSearchQuery = "#$tag"
        selectedTab = 1
    }

    // Key VMs by pubkey so logout → re-login with a different npub creates fresh
    // instances. Without keying, hiltViewModel() returns the Activity-scoped VM that
    // captured the old user's pubkey at init and never re-initializes.
    val feedViewModel: FeedViewModel = hiltViewModel(key = "feed-$userPubkey")
    val relayManagementVm: RelayManagementViewModel = hiltViewModel(key = "relay-$userPubkey")
    val notifViewModel: NotificationsViewModel = hiltViewModel(key = "notif-$userPubkey")
    val splashDone    by feedViewModel.splashDone.collectAsStateWithLifecycle()
    val feedType      by feedViewModel.feedType.collectAsStateWithLifecycle()
    val userSets      by feedViewModel.userSetsFlow.collectAsStateWithLifecycle()
    val pinnedRelays  by feedViewModel.pinnedRelays.collectAsStateWithLifecycle()
    val relayHealth   by relayManagementVm.relayHealth.collectAsStateWithLifecycle(initialValue = emptyMap())
    val hasFollows    by feedViewModel.hasFollows.collectAsStateWithLifecycle()
    val currentFilter by feedViewModel.filterFlow.collectAsStateWithLifecycle()
    val userAvatarUrl by feedViewModel.userAvatarUrl.collectAsStateWithLifecycle()
    val hasNewTopPost by feedViewModel.showDot.collectAsStateWithLifecycle()
    val notifFilter        by notifViewModel.filter.collectAsStateWithLifecycle()
    val hasNewNotifications by notifViewModel.hasNewNotifications.collectAsStateWithLifecycle()

    // Build the ordered feed list for the carousel
    val feedList = remember(hasFollows, pinnedRelays, userSets) {
        buildList {
            if (hasFollows) add(FeedType.Following to "Following")
            add(FeedType.Global to "Global")
            add(FeedType.Popular to "Popular")
            for (relay in pinnedRelays) {
                if (relay.url == FeedType.Popular.url) continue
                add(relay as FeedType to relay.displayLabel)
            }
            userSets.forEach { set ->
                val name = set.title ?: set.dTag
                add(FeedType.RelaySet(set.dTag, name) as FeedType to name)
            }
        }
    }

    val density = LocalDensity.current
    val statusBarHeight = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val navBarHeight    = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }

    val topBarShown    = splashDone && barsVisible && selectedTab != 1 && selectedTab != 3
    val bottomBarShown = splashDone && barsVisible

    val topBarOffset by animateDpAsState(
        targetValue   = if (topBarShown) 0.dp else -(Sizing.topBarHeight + statusBarHeight + 8.dp),
        animationSpec = animSpec,
        label         = "topBarOffset",
    )
    val bottomBarOffset by animateDpAsState(
        targetValue   = if (bottomBarShown) 0.dp else (Sizing.bottomNavHeight + navBarHeight + 8.dp),
        animationSpec = animSpec,
        label         = "bottomBarOffset",
    )
    // Constant: top spacing moved to LazyColumn contentPadding (no animation = no jerk).
    val staticTopPadding = Sizing.topBarHeight + statusBarHeight
    val contentBottomPadding by animateDpAsState(
        targetValue   = if (bottomBarShown) Sizing.bottomNavHeight + navBarHeight else 0.dp,
        animationSpec = animSpec,
        label         = "contentBottomPadding",
    )

    // Accumulated scroll distance — requires committed drag before toggling bars.
    // Prevents jittery show/hide on micro-scrolls and the "back jerk" when
    // contentTopPadding animates on a barely-moved finger.
    val scrollAccumulator = remember { mutableFloatStateOf(0f) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if ((dy < 0 && scrollAccumulator.floatValue > 0) ||
                    (dy > 0 && scrollAccumulator.floatValue < 0)) {
                    scrollAccumulator.floatValue = 0f  // direction reversed — reset
                }
                scrollAccumulator.floatValue += dy
                when {
                    scrollAccumulator.floatValue < -60f && barsVisible -> {
                        barsVisible = false
                        scrollAccumulator.floatValue = 0f
                    }
                    scrollAccumulator.floatValue > 30f && !barsVisible -> {
                        barsVisible = true
                        scrollAccumulator.floatValue = 0f
                    }
                }
                return Offset.Zero
            }
        }
    }

    CompositionLocalProvider(
        LocalShowSnackbar provides showSnackbar,
        com.unsilence.app.ui.common.LocalOpenEmojiSettings provides { showEmojiSettings = true },
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .nestedScroll(nestedScrollConnection),
    ) {

            // ── Content ───────────────────────────────────────────────────────
            // No top padding — FeedScreen uses LazyColumn contentPadding instead
            // (prevents jerk when bar hides). Other screens get constant padding.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = contentBottomPadding),
            ) {
                when (selectedTab) {
                    0    -> FeedScreen(
                        scrollToTopTrigger = scrollToTopTrigger,
                        topBarShown        = topBarShown,
                        staticTopPadding   = staticTopPadding,
                        onNoteClick        = { eventId -> threadEventId = eventId },
                        onComment          = { eventId -> replyToEventId = eventId },
                        onAuthorClick      = onAuthorClick,
                        onHashtagClick     = onHashtagClick,
                        onQuote            = { noteId  -> quoteNoteId   = noteId  },
                        viewModel          = feedViewModel,
                    )
                    1    -> Box(Modifier.padding(top = statusBarHeight)) {
                        SearchScreen(
                            onNoteClick   = { eventId -> threadEventId = eventId },
                            onComment     = { eventId -> replyToEventId = eventId },
                            onAuthorClick = onAuthorClick,
                            onHashtagClick = onHashtagClick,
                            onQuote       = { noteId  -> quoteNoteId   = noteId  },
                            initialQuery  = hashtagSearchQuery,
                            onInitialQueryConsumed = { hashtagSearchQuery = null },
                        )
                    }
                    2    -> NotificationsScreen(
                        onNoteClick      = { eventId -> threadEventId = eventId },
                        staticTopPadding = staticTopPadding,
                        viewModel        = notifViewModel,
                    )
                    3    -> ProfileScreen(onLogout = onLogout, onBack = { selectedTab = 0 }, onNoteClick = { eventId -> threadEventId = eventId }, onComment = { eventId -> replyToEventId = eventId }, onAuthorClick = onAuthorClick, viewModel = hiltViewModel(key = "profile-$userPubkey"))
                    else -> PlaceholderScreen()
                }
            }

            // ── Top bar overlay ───────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = topBarOffset)
                    .fillMaxWidth()
                    .background(Black)
                    .statusBarsPadding()
                    .height(Sizing.topBarHeight),
                contentAlignment = Alignment.Center,
            ) {
                // Layered layout for true centering
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium),
                ) {
                    // Left: app logo mark (52dp = next golden-ratio step from 32)
                    // Offset left by 8dp to align waveform visual start with card edges
                    LogoMark(
                        sizeDp = Spacing.xxl,
                        static = false,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = (-8).dp),
                    )

                    if (selectedTab == 2) {
                        // Center: notification filter carousel
                        NotifFilterCarousel(
                            current = notifFilter,
                            onChanged = { notifViewModel.setFilter(it) },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        // Center: feed carousel
                        FeedCarousel(
                            feedList = feedList,
                            currentFeedType = feedType,
                            relayHealth = relayHealth,
                            onFeedChanged = { feedViewModel.setFeedType(it) },
                            onTap = { showFeedSheet = true },
                            modifier = Modifier.align(Alignment.Center),
                        )

                        // Right: filter icon (24dp — lone icon needs more mass
                        // to balance the 52dp logo mark on the opposite side)
                        Icon(
                            imageVector        = Icons.Filled.Tune,
                            contentDescription = "Filter",
                            tint               = if (currentFilter.isNonDefault) Brand else Color.White.copy(alpha = 0.7f),
                            modifier           = Modifier
                                .align(Alignment.CenterEnd)
                                .size(24.dp)
                                .clickable { showFilter = true },
                        )
                    }
                }
            }

            // ── Floating compose FAB (feed tab only) ──────────────────────────
            if (selectedTab == 0) {
                val fabVisible = splashDone && barsVisible
                AnimatedVisibility(
                    visible = fabVisible,
                    enter   = scaleIn(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
                    exit    = scaleOut(animationSpec = tween(150)) + fadeOut(animationSpec = tween(150)),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end    = Spacing.medium,
                            bottom = Sizing.bottomNavHeight + navBarHeight + Spacing.medium + 14.dp,
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(BrandDeep, CircleShape)
                            .clip(CircleShape)
                            .clickable { showCompose = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector        = Icons.Outlined.EditNote,
                            contentDescription = "New note",
                            tint               = Color.Black,
                            modifier           = Modifier.size(28.dp),
                        )
                    }
                }
            }

            // ── Bottom nav overlay ────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(y = bottomBarOffset)
                    .fillMaxWidth()
                    .background(Black)
                    .navigationBarsPadding()
                    .height(Sizing.bottomNavHeight)
                    .padding(horizontal = Spacing.medium),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                TABS.forEachIndexed { index, tab ->
                    val isSelected = index == selectedTab
                    val iconSize   = 24.dp  // constant — selection via tint only

                    IconButton(onClick = {
                        if (index == 0 && selectedTab == 0) {
                            scrollToTopTrigger++
                            feedViewModel.clearNewTopPost()
                        }
                        if (index == 2) notifViewModel.markSeen()
                        selectedTab = index
                    }) {
                        Box(contentAlignment = Alignment.Center) {
                            if (index == 3 && userAvatarUrl != null) {
                                Box(
                                    modifier = Modifier
                                        .size(iconSize)
                                        .then(
                                            if (isSelected) Modifier.border(1.5.dp, Color.White, CircleShape)
                                            else Modifier
                                        )
                                        .clip(CircleShape),
                                ) {
                                    AsyncImage(
                                        model = rememberAvatarImageRequest(userAvatarUrl, iconSize),
                                        contentDescription = "Profile",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector        = tab.icon,
                                    contentDescription = tab.contentDescription,
                                    tint               = if (isSelected) Color.White else NavUnselected,
                                    modifier           = Modifier.size(iconSize),
                                )
                            }
                            if (index == 0 && hasNewTopPost) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .align(Alignment.TopEnd)
                                        .background(Brand, CircleShape),
                                )
                            }
                            if (index == 2 && hasNewNotifications) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .align(Alignment.TopEnd)
                                        .background(Brand, CircleShape),
                                )
                            }
                        }
                    }
                }
            }

            // ── Feed selector bottom sheet ───────────────────────────────────
            if (showFeedSheet) {
                FeedSelectorSheet(
                    feedType        = feedType,
                    hasFollows      = hasFollows,
                    userSets        = userSets,
                    pinnedRelays    = pinnedRelays,
                    relayHealth     = relayHealth,
                    onFeedChanged   = { type ->
                        feedViewModel.setFeedType(type)
                        showFeedSheet = false
                    },
                    onRemoveFavorite = { url -> feedViewModel.removePinnedRelay(url) },
                    onNewRelaySet   = { showFeedSheet = false; showCreateRelaySet = true },
                    onRelaySettings = { showFeedSheet = false; showRelaySettings = true },
                    onDeleteSet     = { dTag ->
                        relayManagementVm.deleteRelaySet(dTag)
                        if (feedType is FeedType.RelaySet && (feedType as FeedType.RelaySet).dTag == dTag) {
                            feedViewModel.setFeedType(FeedType.Global)
                        }
                    },
                    onDismiss       = { showFeedSheet = false },
                )
            }

            // ── Filter bottom sheet ───────────────────────────────────────────
            if (showFilter) {
                FilterBottomSheet(
                    currentFilter = currentFilter,
                    onApply       = { filter -> feedViewModel.updateFilter(filter) },
                    onDismiss     = { showFilter = false },
                )
            }

            // ── Create relay set overlay ──────────────────────────────────────
            if (showCreateRelaySet) {
                CreateRelaySetScreen(onDismiss = { showCreateRelaySet = false })
            }

            // ── Relay settings overlay ──────────────────────────────────────
            if (showRelaySettings) {
                RelayManagementScreen(
                    onDismiss    = { showRelaySettings = false },
                    onStartFeed  = { url, label ->
                        feedViewModel.addPinnedRelay(url, label)
                    },
                )
            }

            // ── Compose overlay ───────────────────────────────────────────────
            if (showCompose) {
                ComposeScreen(onDismiss = { showCompose = false })
            }

            // ── User profile overlay ──────────────────────────────────────────
            userProfilePubkey?.let { pubkey ->
                UserProfileScreen(
                    pubkey        = pubkey,
                    onDismiss     = { userProfilePubkey = null },
                    onNoteClick   = { eventId -> threadEventId = eventId },
                    onComment     = { eventId -> replyToEventId = eventId },
                    onAuthorClick = onAuthorClick,
                )
            }

            // ── Thread overlay ────────────────────────────────────────────────
            // Must come AFTER user profile so it renders on top when both are visible
            threadEventId?.let { eventId ->
                key(eventId) {
                    ThreadScreen(
                        eventId       = eventId,
                        onDismiss     = { threadEventId = null },
                        onQuote       = { noteId -> quoteNoteId = noteId },
                        onComment     = { replyEventId -> replyToEventId = replyEventId },
                        onAuthorClick = { pubkey ->
                            threadEventId = null      // dismiss thread so profile is visible
                            userProfilePubkey = pubkey
                        },
                    )
                }
            }

            // ── Reply-compose overlay ─────────────────────────────────────────
            replyToEventId?.let { eventId ->
                ComposeScreen(
                    replyToEventId = eventId,
                    onDismiss      = { replyToEventId = null },
                )
            }

            // ── Quote-compose overlay ─────────────────────────────────────────
            quoteNoteId?.let { noteId ->
                ComposeScreen(
                    quoteEventId = noteId,
                    onDismiss    = { quoteNoteId = null },
                )
            }

            // ── Settings → Custom Emojis overlay ────────────────────────────
            if (showEmojiSettings) {
                com.unsilence.app.ui.settings.CustomEmojisScreen(
                    onDismiss = { showEmojiSettings = false },
                )
            }

            // ── Snackbar host ────────────────────────────────────────────────
            SnackbarHost(
                hostState = snackbarHostState,
                modifier  = Modifier.align(Alignment.BottomCenter),
            )
        }
    } // CompositionLocalProvider
}

// ── Feed carousel ─────────────────────────────────────────────────────────
//
// Vertical pager with infinite scroll that shows adjacent feed names peeking
// in above and below, scaling and fading as they move away from center.
// Tap opens the sheet; swipe cycles feeds with a smooth drum-roller feel.

@Composable
private fun FeedCarousel(
    feedList: List<Pair<FeedType, String>>,
    currentFeedType: FeedType,
    relayHealth: Map<String, RelayHealthInfo>,
    onFeedChanged: (FeedType) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (feedList.isEmpty()) return

    val realCount = feedList.size

    // Single feed — static label, no pager
    if (realCount == 1) {
        Text(
            text = feedList[0].second,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTap,
                ),
        )
        return
    }

    val currentIdx = feedList.indexOfFirst { feedTypeMatches(it.first, currentFeedType) }
        .coerceAtLeast(0)

    // Infinite scroll via large virtual page count centered at the current feed
    val virtualCount = realCount * 10_000
    val middleBase = (virtualCount / 2 / realCount) * realCount
    val initialPage = middleBase + currentIdx

    val pagerState = rememberPagerState(initialPage = initialPage) { virtualCount }

    // Recenter pager when feed list changes (follow/unfollow, relay set add/remove)
    // to prevent mod(realCount) pointing to the wrong feed after virtualCount changes.
    LaunchedEffect(realCount) {
        val targetReal = feedList.indexOfFirst { feedTypeMatches(it.first, currentFeedType) }
            .coerceAtLeast(0)
        val newMiddle = (virtualCount / 2 / realCount) * realCount
        pagerState.scrollToPage(newMiddle + targetReal)
    }

    // Pager settled on a new page → update the ViewModel
    LaunchedEffect(pagerState.settledPage) {
        val realIdx = pagerState.settledPage.mod(realCount)
        val settled = feedList.getOrNull(realIdx)?.first ?: return@LaunchedEffect
        if (!feedTypeMatches(settled, currentFeedType)) {
            onFeedChanged(settled)
        }
    }

    // External change (sheet selection) → scroll pager to match
    LaunchedEffect(currentFeedType) {
        val targetReal = feedList.indexOfFirst { feedTypeMatches(it.first, currentFeedType) }
            .coerceAtLeast(0)
        val currentReal = pagerState.currentPage.mod(realCount)
        if (targetReal != currentReal) {
            pagerState.animateScrollToPage(pagerState.currentPage + (targetReal - currentReal))
        }
    }

    val pageHeightDp = 26.dp
    val coroutineScope = rememberCoroutineScope()
    var engaged by remember { mutableStateOf(false) }

    // Health dot for the active feed (only for SingleRelay)
    val activeRelayUrl = (currentFeedType as? FeedType.SingleRelay)?.url
    val activeHealth = activeRelayUrl?.let { url ->
        relayHealth[url] ?: normalizeRelayUrl(url)?.let { relayHealth[it] }
    }

    Box(
        modifier = modifier
            .height(pageHeightDp * 1.7f)
            .widthIn(min = 80.dp, max = 160.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (engaged) Brand.copy(alpha = 0.06f) else Color.Transparent, RoundedCornerShape(10.dp))
            .pointerInput(pagerState) {
                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)

                    // Wait for finger lift (tap) or timeout (long press)
                    val liftedBeforeTimeout = withTimeoutOrNull(longPressTimeout) {
                        waitForUpOrCancellation()
                    }

                    if (liftedBeforeTimeout != null) {
                        // Finger lifted before timeout → TAP
                        onTap()
                    } else {
                        // Long press reached → enter drag mode
                        engaged = true
                        try {
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (change.pressed) {
                                    val dragY = change.positionChange().y
                                    change.consume()
                                    coroutineScope.launch {
                                        pagerState.scrollBy(-dragY)
                                    }
                                } else {
                                    break
                                }
                            } while (true)
                        } finally {
                            engaged = false
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage)
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        VerticalPager(
            state = pagerState,
            pageSize = PageSize.Fixed(pageHeightDp),
            beyondViewportPageCount = 1,
            userScrollEnabled = false,
            modifier = Modifier
                .height(pageHeightDp * 1.7f)
                .fillMaxWidth(),
        ) { page ->
            val realIdx = page.mod(realCount)
            val pageOffset = ((pagerState.currentPage - page) +
                pagerState.currentPageOffsetFraction).absoluteValue

            Box(
                modifier = Modifier
                    .height(pageHeightDp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = lerp(1f, 0.12f, pageOffset.coerceIn(0f, 1f))
                        val scale = lerp(1f, 0.65f, pageOffset.coerceIn(0f, 1f))
                        scaleX = scale
                        scaleY = scale
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    // Health dot — only for the active relay feed page
                    if (activeHealth != null && pageOffset < 0.5f) {
                        val dotColor = when {
                            activeHealth.score == null -> Text3
                            activeHealth.score!! >= 70 -> Mint
                            activeHealth.score!! >= 40 -> Warn
                            else -> Like
                        }
                        val breathAlpha = rememberInfiniteTransition(label = "dot")
                            .animateFloat(
                                initialValue = 0.55f,
                                targetValue  = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(2000, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse,
                                ),
                                label = "dotAlpha",
                            )
                        Box(
                            Modifier
                                .size(5.dp)
                                .graphicsLayer { alpha = breathAlpha.value }
                                .background(dotColor, CircleShape)
                        )
                        Spacer(Modifier.width(Spacing.micro))
                    }
                    Text(
                        text = feedList[realIdx].second,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ── Notification filter carousel (revolver only, no tap-to-open) ─────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotifFilterCarousel(
    current: NotifFilter,
    onChanged: (NotifFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = NotifFilter.entries
    val realCount = items.size
    val virtualCount = realCount * 10_000
    val middleBase = (virtualCount / 2 / realCount) * realCount
    val initialPage = middleBase + items.indexOf(current)

    val pagerState = rememberPagerState(initialPage = initialPage) { virtualCount }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(pagerState.settledPage) {
        val settled = items[pagerState.settledPage.mod(realCount)]
        if (settled != current) onChanged(settled)
    }

    LaunchedEffect(current) {
        val targetReal = items.indexOf(current)
        val currentReal = pagerState.currentPage.mod(realCount)
        if (targetReal != currentReal) {
            pagerState.animateScrollToPage(pagerState.currentPage + (targetReal - currentReal))
        }
    }

    val pageHeightDp = 26.dp

    Box(
        modifier = modifier
            .height(pageHeightDp * 1.7f)
            .widthIn(min = 80.dp, max = 150.dp)
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(pagerState) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.pressed) {
                            val dragY = change.positionChange().y
                            change.consume()
                            coroutineScope.launch { pagerState.scrollBy(-dragY) }
                        } else {
                            break
                        }
                    } while (true)
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        VerticalPager(
            state = pagerState,
            pageSize = PageSize.Fixed(pageHeightDp),
            beyondViewportPageCount = 1,
            userScrollEnabled = false,
            modifier = Modifier
                .height(pageHeightDp * 1.7f)
                .fillMaxWidth(),
        ) { page ->
            val realIdx = page.mod(realCount)
            val pageOffset = ((pagerState.currentPage - page) +
                pagerState.currentPageOffsetFraction).absoluteValue

            Box(
                modifier = Modifier
                    .height(pageHeightDp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = lerp(1f, 0.12f, pageOffset.coerceIn(0f, 1f))
                        val scale = lerp(1f, 0.65f, pageOffset.coerceIn(0f, 1f))
                        scaleX = scale
                        scaleY = scale
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = items[realIdx].name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── Feed selector bottom sheet ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FeedSelectorSheet(
    feedType: FeedType,
    hasFollows: Boolean,
    userSets: List<RelaySet>,
    pinnedRelays: List<FeedType.SingleRelay>,
    relayHealth: Map<String, RelayHealthInfo>,
    onFeedChanged: (FeedType) -> Unit,
    onRemoveFavorite: (String) -> Unit,
    onNewRelaySet: () -> Unit,
    onRelaySettings: () -> Unit,
    onDeleteSet: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var confirmDeleteDTag by remember { mutableStateOf<String?>(null) }

    fun isSelected(type: FeedType): Boolean = feedTypeMatches(type, feedType)

    @Composable
    fun SheetItem(label: String, type: FeedType, dTag: String? = null) {
        val selected = isSelected(type)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) Brand.copy(alpha = 0.08f) else Color.Transparent)
                .combinedClickable(
                    onClick = { onFeedChanged(type) },
                    onLongClick = { if (dTag != null) confirmDeleteDTag = dTag },
                )
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(if (selected) Brand else Color(0xFF333333), CircleShape),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text       = label,
                color      = if (selected) Brand else Color(0xFFDDDDDD),
                fontSize   = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier   = Modifier.weight(1f),
            )
        }
    }

    @Composable
    fun SectionLabel(text: String) {
        Text(
            text = text.uppercase(),
            color = Text3,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 4.dp),
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = Surface1,
        shape            = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle       = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .background(Color(0xFF333333), RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(modifier = Modifier.padding(bottom = 28.dp)) {
            // ── Core feeds ──
            SectionLabel("Feeds")
            if (hasFollows) SheetItem("Following", FeedType.Following)
            SheetItem("Global", FeedType.Global)
            SheetItem("Popular", FeedType.Popular)

            // ── Pinned relays ──
            val visiblePinned = pinnedRelays.filter { it.url != FeedType.Popular.url }
            if (visiblePinned.isNotEmpty()) {
                SectionLabel("Favorite Relays")
                for (relay in visiblePinned) {
                    val selected = isSelected(relay)
                    val healthScore = (relayHealth[relay.url] ?: normalizeRelayUrl(relay.url)?.let { relayHealth[it] })?.score
                    val dotColor = when {
                        healthScore == null -> Text3
                        healthScore >= 70   -> Mint
                        healthScore >= 40   -> Color(0xFFFFC107)
                        else                -> Color(0xFFFF5252)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Brand.copy(alpha = 0.08f) else Color.Transparent)
                            .clickable { onFeedChanged(relay) }
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Canvas(modifier = Modifier.size(8.dp)) {
                            drawCircle(color = dotColor)
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text       = relay.displayLabel,
                            color      = if (selected) Brand else Color(0xFFDDDDDD),
                            fontSize   = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier   = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove favorite",
                            tint = Text3,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { onRemoveFavorite(relay.url) },
                        )
                    }
                }
            }

            // ── Relay sets ──
            if (userSets.isNotEmpty()) {
                SectionLabel("Relay Sets")
                for (set in userSets) {
                    SheetItem(
                        label = set.title ?: set.dTag,
                        type  = FeedType.RelaySet(set.dTag, set.title ?: set.dTag),
                        dTag  = set.dTag,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Surface1, thickness = 1.dp)
            Spacer(Modifier.height(4.dp))

            // ── Actions ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onNewRelaySet() }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("+", color = Brand, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(14.dp))
                Text("New Relay Set", color = Brand, fontSize = 14.sp)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onRelaySettings() }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("\u2699", color = Text3, fontSize = 14.sp)
                Spacer(Modifier.width(14.dp))
                Text("Manage Relays", color = Color(0xFF999999), fontSize = 14.sp)
            }
        }
    }

    confirmDeleteDTag?.let { dTag ->
        val setName = userSets.firstOrNull { it.dTag == dTag }?.title ?: dTag
        AlertDialog(
            onDismissRequest = { confirmDeleteDTag = null },
            title = { Text("Delete Relay Set", color = Color.White) },
            text = { Text("Delete \"$setName\"? This cannot be undone.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSet(dTag)
                    confirmDeleteDTag = null
                }) { Text("Delete", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteDTag = null }) {
                    Text("Cancel", color = Brand)
                }
            },
            containerColor = Surface1,
        )
    }
}

@Composable
private fun PlaceholderScreen() {
    Box(
        modifier         = Modifier.fillMaxSize().background(Black),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text     = "Coming soon",
            color    = TextSecondary,
            fontSize = 15.sp,
        )
    }
}
