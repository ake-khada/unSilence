package com.unsilence.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.GppGood
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.unsilence.app.ui.common.LogoMark
import com.unsilence.app.ui.common.rememberAnimatorDurationScale
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.ui.common.rememberPowerSaveMode
import com.unsilence.app.data.memory.RelaySet
import com.unsilence.app.data.memory.RelayHealthInfo
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.relay.GraphLanding
import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.ui.compose.ComposeScreen
import com.unsilence.app.ui.feed.FeedScreen
import com.unsilence.app.ui.feed.FeedType
import com.unsilence.app.ui.feed.FeedViewModel
import com.unsilence.app.ui.feed.FilterBottomSheet
import com.unsilence.app.ui.feed.FilterIconKind
import com.unsilence.app.ui.feed.filterIconKind
import com.unsilence.app.ui.feed.isImmersiveVideoMode
import com.unsilence.app.ui.feed.NoteActionsViewModel
import com.unsilence.app.ui.notifications.NotifFilter
import com.unsilence.app.ui.notifications.NotificationsScreen
import com.unsilence.app.ui.notifications.NotificationsViewModel
import com.unsilence.app.ui.onboarding.StartYourGraphScreen
import com.unsilence.app.ui.onboarding.StartYourGraphViewModel
import com.unsilence.app.ui.profile.ProfileScreen
import com.unsilence.app.ui.profile.UserProfileScreen
import com.unsilence.app.ui.profile.ConnectionsScreen
import com.unsilence.app.ui.profile.ConnectionsTab
import com.unsilence.app.ui.profile.ProfileRelaysScreen
import com.unsilence.app.ui.relays.CreateRelaySetScreen
import com.unsilence.app.ui.relays.RelayDetailScreen
import com.unsilence.app.ui.relays.RelayDiscoveryScreen
import com.unsilence.app.ui.relays.RelayManagementScreen
import com.unsilence.app.ui.relays.RelayManagementViewModel
import com.unsilence.app.ui.search.SearchScreen
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.common.LocalOpenRelayDetail
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.common.LocalZapPreferences
import com.unsilence.app.ui.settings.ZapSettingsViewModel
import com.unsilence.app.domain.model.GlobalFeedLens
import com.unsilence.app.domain.model.ShowType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.BrandDeep
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap
import com.unsilence.app.ui.thread.ThreadScreen
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

private val NavUnselected = Text3

private data class NavTab(val icon: ImageVector, val contentDescription: String)

private data class ThreadDestination(
    val eventId: String,
    val relayHints: List<String> = emptyList(),
    val openArticleOnLoad: Boolean = false,
)

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
fun AppNavigation(
    ownPubkey: String,
    sessionKey: String,
    onLogout: () -> Unit,
) {
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
    var relayDetailUrl       by remember { mutableStateOf<String?>(null) }
    var showDiscovery        by remember { mutableStateOf(false) }
    var threadDestination    by remember { mutableStateOf<ThreadDestination?>(null) }
    var replyToEventId       by remember { mutableStateOf<String?>(null) }
    var quoteNoteId          by remember { mutableStateOf<String?>(null) }
    var userProfilePubkey    by remember { mutableStateOf<String?>(null) }
    var connectionsTarget    by remember { mutableStateOf<Pair<String, ConnectionsTab>?>(null) }
    var profileRelaysPubkey  by remember { mutableStateOf<String?>(null) }
    var scrollToTopTrigger   by remember { mutableIntStateOf(0) }
    var showEmojiSettings    by remember { mutableStateOf(false) }
    var showZapSettings      by remember { mutableStateOf(false) }
    var hashtagSearchQuery   by remember { mutableStateOf<String?>(null) }
    var showStartGraph       by remember { mutableStateOf(false) }
    val pullRefreshFraction = remember { mutableFloatStateOf(0f) }
    val updatePullRefreshFraction: (Float) -> Unit = remember(pullRefreshFraction) {
        { fraction -> pullRefreshFraction.floatValue = fraction }
    }

    BackHandler(enabled = selectedTab != 0) { selectedTab = 0 }

    val onAuthorClick: (String) -> Unit = { pubkey -> userProfilePubkey = pubkey }
    val onHashtagClick: (String) -> Unit = { tag ->
        // Clear any open overlays (thread / user profile) so the search tab isn't
        // hidden behind them when a hashtag is tapped from inside one (incl. the
        // article reader hosted there).
        threadDestination = null
        userProfilePubkey = null
        hashtagSearchQuery = "#$tag"
        selectedTab = 1
    }

    // Key VMs by pubkey so logout → re-login with a different npub creates fresh
    // instances. Without keying, hiltViewModel() returns the Activity-scoped VM that
    // captured the old user's pubkey at init and never re-initializes.
    val feedViewModel: FeedViewModel = hiltViewModel(key = "feed-$sessionKey")
    val relayManagementVm: RelayManagementViewModel = hiltViewModel(key = "relay-$sessionKey")
    // Browse a relay's feed (§05 detail footer): make it active WITHOUT pinning it.
    // The source pill names this transient relay until the user switches away.
    val onBrowseRelayFeed: (String, String) -> Unit = { url, lbl ->
        feedViewModel.setFeedType(FeedType.SingleRelay(url, lbl))
        relayDetailUrl = null
        showRelaySettings = false
        showDiscovery = false
        profileRelaysPubkey = null
        connectionsTarget = null
        threadDestination = null
        userProfilePubkey = null
        selectedTab = 0
    }
    val notifViewModel: NotificationsViewModel = hiltViewModel(key = "notif-$sessionKey")
    val zapSettingsVm: ZapSettingsViewModel = hiltViewModel(key = "zap-settings-$sessionKey")
    val noteActionsVm: NoteActionsViewModel = hiltViewModel(key = "note-actions-$sessionKey")
    val deepLinkVm: DeepLinkNavigationViewModel = hiltViewModel(key = "deep-links-$sessionKey")
    val startGraphVm: StartYourGraphViewModel = hiltViewModel(key = "start-graph-$sessionKey")
    val splashDone    by feedViewModel.splashDone.collectAsStateWithLifecycle()
    val feedType      by feedViewModel.feedType.collectAsStateWithLifecycle()
    val userSets      by feedViewModel.userSetsFlow.collectAsStateWithLifecycle()
    val pinnedRelays  by feedViewModel.pinnedRelays.collectAsStateWithLifecycle()
    val relayHealth   by relayManagementVm.relayHealth.collectAsStateWithLifecycle(initialValue = emptyMap())
    val currentFilter by feedViewModel.filterFlow.collectAsStateWithLifecycle()
    val globalFeedLens by feedViewModel.globalFeedLens.collectAsStateWithLifecycle()
    val isFeedRefreshing by feedViewModel.isRefreshing.collectAsStateWithLifecycle()
    val userAvatarUrl by feedViewModel.userAvatarUrl.collectAsStateWithLifecycle()
    val hasNewTopPost by feedViewModel.showDot.collectAsStateWithLifecycle()
    val notifFilter        by notifViewModel.filter.collectAsStateWithLifecycle()
    val hasNewNotifications by notifViewModel.hasNewNotifications.collectAsStateWithLifecycle()
    val zapPreferences      by zapSettingsVm.preferences.collectAsStateWithLifecycle()
    val pendingDeepLink     by deepLinkVm.pendingTarget.collectAsStateWithLifecycle()
    val pendingDeepLinkFailure by deepLinkVm.pendingFailure.collectAsStateWithLifecycle()
    val startGraphState by startGraphVm.uiState.collectAsStateWithLifecycle()
    val startGraphAutoOpen by startGraphVm.autoOpen.collectAsStateWithLifecycle()
    val showEmptyFollowingEntry by startGraphVm.showEmptyFollowingEntry.collectAsStateWithLifecycle()
    val isPowerSaveMode = rememberPowerSaveMode()
    val animatorDurationScale = rememberAnimatorDurationScale()
    val headerMotionEnabled = feedHeaderMotionEnabled(isPowerSaveMode, animatorDurationScale)

    LaunchedEffect(startGraphAutoOpen) {
        if (startGraphAutoOpen) {
            showStartGraph = true
            startGraphVm.consumeAutoOpen()
        }
    }

    LaunchedEffect(startGraphVm) {
        startGraphVm.landingEvents.collect { landing ->
            showStartGraph = false
            selectedTab = 0
            when (landing) {
                GraphLanding.FOLLOWING -> feedViewModel.setFeedType(FeedType.Following)
                GraphLanding.GLOBAL_TRUSTED -> {
                    feedViewModel.setGlobalFeedLens(GlobalFeedLens.TRUSTED)
                    feedViewModel.setFeedType(FeedType.Global)
                }
            }
        }
    }

    LaunchedEffect(pendingDeepLinkFailure) {
        if (pendingDeepLinkFailure && deepLinkVm.consumeFailure()) {
            showSnackbar("Couldn't open link")
        }
    }

    LaunchedEffect(pendingDeepLink) {
        val target = pendingDeepLink ?: return@LaunchedEffect
        if (!deepLinkVm.consume(target)) return@LaunchedEffect

        showCompose = false
        replyToEventId = null
        quoteNoteId = null
        connectionsTarget = null
        profileRelaysPubkey = null
        when (target) {
            is DeepLinkTarget.Profile -> {
                deepLinkVm.prefetchProfile(target)
                threadDestination = null
                userProfilePubkey = target.pubkey
            }
            is DeepLinkTarget.Note -> {
                userProfilePubkey = null
                threadDestination = ThreadDestination(
                    eventId = target.eventId,
                    relayHints = target.relayHints,
                )
            }
            is DeepLinkTarget.Address -> {
                val eventId = deepLinkVm.resolveAddress(target)
                if (eventId == null) {
                    showSnackbar("Couldn't open link")
                } else {
                    userProfilePubkey = null
                    threadDestination = ThreadDestination(
                        eventId = eventId,
                        relayHints = target.relayHints,
                        openArticleOnLoad = target.kind == 30023,
                    )
                }
            }
        }
    }

    val density = LocalDensity.current
    val statusBarHeight = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val navBarHeight    = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }

    val immersiveVideoMode = selectedTab == 0 && currentFilter.isImmersiveVideoMode()
    val topBarShown    = splashDone && barsVisible && selectedTab != 1 && selectedTab != 3 && !immersiveVideoMode
    val bottomBarShown = splashDone && barsVisible && !immersiveVideoMode
    val activeTopBarHeight = if (selectedTab == 0) 68.dp else Sizing.topBarHeight

    val topBarOffset by animateDpAsState(
        targetValue   = if (topBarShown) 0.dp else -(activeTopBarHeight + statusBarHeight + 8.dp),
        animationSpec = animSpec,
        label         = "topBarOffset",
    )
    val bottomBarOffset by animateDpAsState(
        targetValue   = if (bottomBarShown) 0.dp else (Sizing.bottomNavHeight + navBarHeight + 8.dp),
        animationSpec = animSpec,
        label         = "bottomBarOffset",
    )
    // Constant: top spacing moved to LazyColumn contentPadding (no animation = no jerk).
    val staticTopPadding = activeTopBarHeight + statusBarHeight
    val animatedContentBottomPadding by animateDpAsState(
        targetValue   = if (bottomBarShown) Sizing.bottomNavHeight + navBarHeight else 0.dp,
        animationSpec = animSpec,
        label         = "contentBottomPadding",
    )
    val contentBottomPadding = if (immersiveVideoMode) 0.dp else animatedContentBottomPadding

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
        LocalAppSessionKey provides sessionKey,
        LocalShowSnackbar provides showSnackbar,
        LocalOpenRelayDetail provides { url -> relayDetailUrl = url },
        LocalZapPreferences provides zapPreferences,
        com.unsilence.app.ui.common.LocalOpenEmojiSettings provides { showEmojiSettings = true },
        com.unsilence.app.ui.common.LocalOpenZapSettings provides { showZapSettings = true },
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
                        onNoteClick        = { eventId -> threadDestination = ThreadDestination(eventId) },
                        onComment          = { eventId -> replyToEventId = eventId },
                        onAuthorClick      = onAuthorClick,
                        onHashtagClick     = onHashtagClick,
                        onQuote            = { noteId  -> quoteNoteId   = noteId  },
                        onPullRefreshProgress = updatePullRefreshFraction,
                        showFindPeopleEmptyState = showEmptyFollowingEntry,
                        onFindPeople = {
                            startGraphVm.open()
                            showStartGraph = true
                        },
                        viewModel          = feedViewModel,
                        actionsViewModel   = noteActionsVm,
                    )
                    1    -> Box(Modifier.padding(top = statusBarHeight)) {
                        SearchScreen(
                            onNoteClick   = { eventId -> threadDestination = ThreadDestination(eventId) },
                            onComment     = { eventId -> replyToEventId = eventId },
                            onAuthorClick = onAuthorClick,
                            onHashtagClick = onHashtagClick,
                            onQuote       = { noteId  -> quoteNoteId   = noteId  },
                            initialQuery  = hashtagSearchQuery,
                            onInitialQueryConsumed = { hashtagSearchQuery = null },
                            actionsViewModel = noteActionsVm,
                        )
                    }
                    2    -> NotificationsScreen(
                        onNoteClick      = { eventId -> threadDestination = ThreadDestination(eventId) },
                        onProfileClick   = onAuthorClick,
                        staticTopPadding = staticTopPadding,
                        viewModel        = notifViewModel,
                    )
                    3    -> ProfileScreen(
                        onLogout = onLogout,
                        onBack = { selectedTab = 0 },
                        onNoteClick = { eventId -> threadDestination = ThreadDestination(eventId) },
                        onComment = { eventId -> replyToEventId = eventId },
                        onAuthorClick = onAuthorClick,
                        onConnectionsClick = { tab -> connectionsTarget = ownPubkey to tab },
                        onRelaysClick = { profileRelaysPubkey = ownPubkey },
                        onHashtagClick = onHashtagClick,
                        onBrowseRelay = onBrowseRelayFeed,
                        viewModel = hiltViewModel(key = "profile-$sessionKey"),
                        actionsViewModel = noteActionsVm,
                    )
                    else -> PlaceholderScreen()
                }
            }

            // ── Top bar overlay ───────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(0, topBarOffset.roundToPx()) }
                    .fillMaxWidth()
                    .background(Black)
                    .statusBarsPadding()
                    .height(activeTopBarHeight),
                contentAlignment = Alignment.Center,
            ) {
                if (selectedTab == 2) {
                    // Notification header keeps its established 52dp geometry.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.medium),
                    ) {
                        LogoMark(
                            sizeDp = Spacing.xxl,
                            static = false,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .offset(x = (-8).dp),
                        )
                        // Center: notification filter carousel
                        NotifFilterCarousel(
                            current = notifFilter,
                            onChanged = { notifViewModel.setFilter(it) },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                } else {
                    UnifiedFeedHeader(
                        feedType = feedType,
                        lens = globalFeedLens,
                        filter = currentFilter,
                        pullFraction = pullRefreshFraction.floatValue,
                        isRefreshing = isFeedRefreshing,
                        motionEnabled = headerMotionEnabled,
                        onLogoClick = { scrollToTopTrigger++ },
                        onSourceClick = { showFeedSheet = true },
                        onLensToggle = feedViewModel::setGlobalFeedLens,
                        onFilterClick = { showFilter = true },
                    )
                }
            }

            // ── Floating compose FAB (feed tab only) ──────────────────────────
            if (selectedTab == 0) {
                val fabVisible = splashDone && barsVisible && !immersiveVideoMode
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
                    .offset { IntOffset(0, bottomBarOffset.roundToPx()) }
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
                    userSets        = userSets,
                    pinnedRelays    = pinnedRelays,
                    relayHealth     = relayHealth,
                    onFeedChanged   = { type ->
                        feedViewModel.setFeedType(type)
                        showFeedSheet = false
                    },
                    onRemoveFavorite = { url -> relayManagementVm.removeFavoriteRelay(url) },
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
                CreateRelaySetScreen(
                    onDismiss = { showCreateRelaySet = false },
                    viewModel = relayManagementVm,
                )
            }

            // ── Relay settings overlay ──────────────────────────────────────
            if (showRelaySettings) {
                RelayManagementScreen(
                    onDismiss    = { showRelaySettings = false },
                    onOpenDetail = { url -> relayDetailUrl = url },
                    onOpenDiscovery = { showDiscovery = true },
                    viewModel = relayManagementVm,
                )
            }

            // ── Relay discovery overlay (§04) — over the relay list ─────────
            if (showDiscovery) {
                RelayDiscoveryScreen(
                    onDismiss = { showDiscovery = false },
                    onOpenDetail = { url -> relayDetailUrl = url },
                    viewModel = relayManagementVm,
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
                    onNoteClick   = { eventId -> threadDestination = ThreadDestination(eventId) },
                    onComment     = { eventId -> replyToEventId = eventId },
                    onAuthorClick = onAuthorClick,
                    onConnectionsClick = { tab -> connectionsTarget = pubkey to tab },
                    onRelaysClick = { profileRelaysPubkey = pubkey },
                    onHashtagClick = onHashtagClick,
                    actionsViewModel = noteActionsVm,
                )
            }

            connectionsTarget?.let { (pubkey, initialTab) ->
                ConnectionsScreen(
                    pubkey = pubkey,
                    initialTab = initialTab,
                    onDismiss = { connectionsTarget = null },
                    onProfileClick = { targetPubkey ->
                        connectionsTarget = null
                        userProfilePubkey = targetPubkey
                    },
                )
            }

            profileRelaysPubkey?.let { pubkey ->
                ProfileRelaysScreen(
                    pubkey = pubkey,
                    onDismiss = { profileRelaysPubkey = null },
                    onOpenRelay = { url -> relayDetailUrl = url },
                )
            }

            // ── Thread overlay ────────────────────────────────────────────────
            // Must come AFTER user profile so it renders on top when both are visible
            threadDestination?.let { destination ->
                key(destination.eventId) {
                    ThreadScreen(
                        eventId       = destination.eventId,
                        relayHints    = destination.relayHints,
                        openArticleOnLoad = destination.openArticleOnLoad,
                        onDismiss     = { threadDestination = null },
                        onQuote       = { noteId -> quoteNoteId = noteId },
                        onComment     = { replyEventId -> replyToEventId = replyEventId },
                        onAuthorClick = { pubkey ->
                            threadDestination = null  // dismiss thread so profile is visible
                            userProfilePubkey = pubkey
                        },
                        onHashtagClick = onHashtagClick,
                        actionsViewModel = noteActionsVm,
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

            // ── Zap settings overlay ────────────────────────────────────────
            if (showZapSettings) {
                com.unsilence.app.ui.settings.ZapSettingsScreen(
                    onDismiss = {
                        showZapSettings = false
                        noteActionsVm.refreshNwcConfigured()
                    },
                    vm = zapSettingsVm,
                )
            }

            if (showStartGraph) {
                StartYourGraphScreen(
                    state = startGraphState,
                    onTogglePack = startGraphVm::togglePack,
                    onTogglePerson = startGraphVm::togglePerson,
                    onPersonVisible = startGraphVm::requestVisiblePerson,
                    onDone = startGraphVm::finish,
                    onRetry = startGraphVm::retry,
                )
            }

            // Globally topmost content overlay: relay rows can be opened from a profile,
            // thread, search result, or bottom sheet without dismissing that context.
            relayDetailUrl?.let { url ->
                RelayDetailScreen(
                    relayUrl = url,
                    onDismiss = { relayDetailUrl = null },
                    onOpenProfile = { pubkey ->
                        relayDetailUrl = null
                        profileRelaysPubkey = null
                        connectionsTarget = null
                        threadDestination = null
                        userProfilePubkey = pubkey
                    },
                    onBrowse = onBrowseRelayFeed,
                    viewModel = relayManagementVm,
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

// ── Unified feed header ──────────────────────────────────────────────────

@Composable
private fun UnifiedFeedHeader(
    feedType: FeedType,
    lens: GlobalFeedLens,
    filter: FeedFilter,
    pullFraction: Float,
    isRefreshing: Boolean,
    motionEnabled: Boolean,
    onLogoClick: () -> Unit,
    onSourceClick: () -> Unit,
    onLensToggle: (GlobalFeedLens) -> Unit,
    onFilterClick: () -> Unit,
) {
    val elements = feedHeaderElements(feedType, lens, filter)
    val targetLensAccent = when (elements.lens) {
        GlobalFeedLens.TRUSTED -> Mint
        GlobalFeedLens.RAW -> Zap
        null -> Brand
    }
    var previousLens by remember { mutableStateOf(elements.lens) }
    val lensAnimationSpec = remember(elements.lens, motionEnabled) {
        if (shouldAnimateLensTransition(previousLens, elements.lens, motionEnabled)) {
            tween<Color>(durationMillis = LENS_TINT_TRANSITION_MS)
        } else {
            snap<Color>()
        }
    }
    SideEffect { previousLens = elements.lens }
    val lensAccent by animateColorAsState(
        targetValue = targetLensAccent,
        animationSpec = lensAnimationSpec,
        label = "feedLensAccent",
    )
    val barHeightScale = rememberPullBarHeightScale(
        pullFraction = pullFraction,
        isRefreshing = isRefreshing,
        motionEnabled = motionEnabled,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp),
        contentAlignment = Alignment.Center,
    ) {
        FeedHeaderHairline(
            accent = lensAccent,
            isRefreshing = isRefreshing,
            motionEnabled = motionEnabled,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.large),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogoMark(
                sizeDp = Spacing.xxl,
                firstBarColor = lensAccent,
                barHeightScale = barHeightScale,
                static = !motionEnabled,
                modifier = Modifier
                    .semantics { contentDescription = "Scroll feed to top" }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onLogoClick,
                    ),
            )
            FeedSourcePill(
                label = elements.sourceLabel,
                onClick = onSourceClick,
            )
            elements.lens?.let { activeLens ->
                FeedTrustChip(
                    lens = activeLens,
                    onClick = {
                        onLensToggle(
                            if (activeLens == GlobalFeedLens.TRUSTED) GlobalFeedLens.RAW
                            else GlobalFeedLens.TRUSTED,
                        )
                    },
                )
            }
            FeedFormatAction(
                activeShowTypes = elements.activeShowTypes,
                contentDescription = elements.formatContentDescription,
                filterActive = filter.isNonDefault,
                onClick = onFilterClick,
            )
        }
    }
}

@Composable
private fun rememberPullBarHeightScale(
    pullFraction: Float,
    isRefreshing: Boolean,
    motionEnabled: Boolean,
): Float {
    val animatedScale = remember { Animatable(1f) }
    val activePullFraction = if (isRefreshing) 0f else pullFraction
    LaunchedEffect(activePullFraction, motionEnabled) {
        val target = effectivePullStretchFactor(activePullFraction, motionEnabled)
        if (!motionEnabled || activePullFraction > 0f) {
            animatedScale.snapTo(target)
        } else {
            animatedScale.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }
    }
    return animatedScale.value
}

@Composable
private fun FeedHeaderHairline(
    accent: Color,
    isRefreshing: Boolean,
    motionEnabled: Boolean,
) {
    val sweepStartState = if (isRefreshing && motionEnabled) {
        val transition = rememberInfiniteTransition(label = "refreshSweep")
        transition.animateFloat(
            initialValue = -REFRESH_SWEEP_SEGMENT_FRACTION,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = REFRESH_SWEEP_PERIOD_MS,
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Restart,
            ),
            label = "refreshSweepPosition",
        )
    } else {
        null
    }
    val accentAlpha = if (isRefreshing && !motionEnabled) 0.88f else 0.62f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.3f to Color.White.copy(alpha = 0.10f),
                        0.7f to Color.White.copy(alpha = 0.10f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 68.dp)
                .width(40.dp)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        0f to accent.copy(alpha = accentAlpha),
                        1f to Color.Transparent,
                    ),
                ),
        )
        sweepStartState?.let { startState ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                val segmentWidth = size.width * REFRESH_SWEEP_SEGMENT_FRACTION
                val startX = size.width * startState.value
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.White.copy(alpha = 0.18f),
                        0.72f to Color.White.copy(alpha = 0.68f),
                        1f to Color.Transparent,
                        startX = startX,
                        endX = startX + segmentWidth,
                    ),
                    topLeft = Offset(startX, 0f),
                    size = androidx.compose.ui.geometry.Size(segmentWidth, size.height),
                )
            }
        }
    }
}

@Composable
private fun FeedSourcePill(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .widthIn(max = 118.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .semantics { contentDescription = "Feed source: $label. Tap to change" }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(start = 10.dp, end = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.micro),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun FeedTrustChip(
    lens: GlobalFeedLens,
    onClick: () -> Unit,
) {
    val trusted = lens == GlobalFeedLens.TRUSTED
    val accent = if (trusted) Mint else Zap
    val description = if (trusted) {
        "Trusted lens — tap for raw"
    } else {
        "Raw feed — tap for trusted"
    }
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .semantics { contentDescription = description }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = if (trusted) 7.dp else 9.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.micro),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (trusted) Icons.Outlined.GppGood else Icons.Outlined.GppBad,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(17.dp),
        )
        if (!trusted) {
            Text(
                text = "Raw",
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun FeedFormatAction(
    activeShowTypes: List<ShowType>,
    contentDescription: String?,
    filterActive: Boolean,
    onClick: () -> Unit,
) {
    // Keep an unstyled launcher at Show=All; otherwise the new header would make
    // the filter sheet unreachable. Active time/activity-only filters tint it too.
    val formatActive = activeShowTypes.isNotEmpty()
    val icon = if (!formatActive) {
        Icons.Filled.Tune
    } else if (activeShowTypes.size > 1) {
        Icons.Filled.GridView
    } else {
        when (filterIconKind(activeShowTypes.single())) {
            FilterIconKind.GRID -> Icons.Filled.GridView
            FilterIconKind.TEXT -> Icons.AutoMirrored.Filled.FormatAlignLeft
            FilterIconKind.IMAGE -> Icons.Filled.Photo
            FilterIconKind.VIDEO -> Icons.Filled.SmartDisplay
            FilterIconKind.ARTICLE -> Icons.AutoMirrored.Filled.Article
        }
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (filterActive) Brand.copy(alpha = 0.14f) else Black)
            .then(
                if (filterActive) Modifier.border(1.dp, Brand.copy(alpha = 0.48f), CircleShape)
                else Modifier,
            )
            .semantics {
                this.contentDescription = contentDescription
                    ?: if (filterActive) "Active feed filters" else "Open feed filters"
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (filterActive) Brand else Color.White.copy(alpha = 0.68f),
            modifier = Modifier.size(18.dp),
        )
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

            Box(
                modifier = Modifier
                    .height(pageHeightDp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val pageOffset = ((pagerState.currentPage - page) +
                            pagerState.currentPageOffsetFraction).absoluteValue
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
            Text(
                text       = label,
                color      = if (selected) Brand else Color(0xFFDDDDDD),
                fontSize   = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = Brand,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    @Composable
    fun SectionLabel(text: String) {
            Text(
                text = text.uppercase(),
                color = Text3,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 4.dp),
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
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
            SheetItem("Following", FeedType.Following)
            SheetItem("Global", FeedType.Global)

            // ── Pinned relays ──
            val visiblePinned = pinnedRelays
            if (visiblePinned.isNotEmpty()) {
                SectionLabel("Favorites")
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
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                            modifier   = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Selected",
                                tint = Brand,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(Spacing.small))
                        }
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
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = Brand,
                    modifier = Modifier.size(18.dp),
                )
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
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = Text3,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text("Manage relays", color = Color(0xFF999999), fontSize = 14.sp)
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
