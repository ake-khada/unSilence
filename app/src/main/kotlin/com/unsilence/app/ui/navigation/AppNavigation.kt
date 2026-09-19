package com.unsilence.app.ui.navigation

import com.unsilence.app.ui.theme.AppTextStyles
import android.os.Bundle
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.unsilence.app.ui.shared.ResumedEffect
import com.unsilence.app.ui.shared.FeedDividerBrush
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
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
import androidx.compose.ui.semantics.Role
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
import com.unsilence.app.ui.relays.RelaySetEditorScreen
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

private val TABS = listOf(
    NavTab(Icons.Outlined.Home,          "Home"),
    NavTab(Icons.Outlined.Search,        "Search"),
    NavTab(Icons.Outlined.Notifications, "Notifications"),
    NavTab(Icons.Outlined.Person,        "Profile"),
)

internal enum class TabReselectAction { NONE, FEED_TOP, PROFILE_TOP }

internal fun tabReselectAction(tappedTab: Int, selectedTab: Int): TabReselectAction {
    if (tappedTab != selectedTab) return TabReselectAction.NONE
    return when (tappedTab) {
        0 -> TabReselectAction.FEED_TOP
        3 -> TabReselectAction.PROFILE_TOP
        else -> TabReselectAction.NONE
    }
}

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
    var showFeedSheet        by rememberSaveable { mutableStateOf(false) }
    var showFilter           by rememberSaveable { mutableStateOf(false) }
    val navigator = rememberAppNavigator(sessionKey)
    var scrollToTopTrigger   by rememberSaveable { mutableIntStateOf(0) }
    var profileScrollToTopTrigger by rememberSaveable { mutableIntStateOf(0) }
    var hashtagSearchQuery   by rememberSaveable { mutableStateOf<String?>(null) }
    val pullRefreshFraction = remember { mutableFloatStateOf(0f) }
    val updatePullRefreshFraction: (Float) -> Unit = remember(pullRefreshFraction) {
        { fraction -> pullRefreshFraction.floatValue = fraction }
    }

    BackHandler(enabled = selectedTab != 0) { selectedTab = 0 }

    val onAuthorClick: (String) -> Unit = remember(navigator) {
        { pubkey -> navigator.push(AppDestination.Profile(pubkey)) }
    }
    val onArticleClick: (com.unsilence.app.data.memory.FeedRow) -> Unit = remember(navigator) {
        { row -> navigator.push(articleDestination(row)) }
    }
    val onNoteClick: (String) -> Unit = remember(navigator) {
        { eventId -> navigator.push(AppDestination.Thread(eventId)) }
    }
    val onHashtagClick: (String) -> Unit = { tag ->
        // Clear any open overlays (thread / user profile) so the search tab isn't
        // hidden behind them when a hashtag is tapped from inside one (incl. the
        // article reader hosted there).
        navigator.popToTabs()
        hashtagSearchQuery = "#$tag"
        selectedTab = 1
    }

    // SessionContent owns these shell VMs and clears them on account replacement.
    // Entry-local screens below get independent stores from AppNavDisplay.
    val feedViewModel: FeedViewModel = hiltViewModel(key = "feed-$sessionKey")
    // Browse a relay's feed (§05 detail footer): make it active WITHOUT pinning it.
    // The source pill names this transient relay until the user switches away.
    val onBrowseRelayFeed: (String, String) -> Unit = { url, lbl ->
        feedViewModel.setFeedType(FeedType.SingleRelay(url, lbl))
        navigator.popToTabs()
        selectedTab = 0
    }
    val notifViewModel: NotificationsViewModel? = if (selectedTab == 2) {
        hiltViewModel(key = "notif-$sessionKey")
    } else null
    val noteActionsVm: NoteActionsViewModel = hiltViewModel(key = "note-actions-$sessionKey")
    val haptic = LocalHapticFeedback.current
    ResumedEffect(noteActionsVm, haptic) {
        noteActionsVm.actionConfirmed.collect {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }
    val deepLinkVm: DeepLinkNavigationViewModel = hiltViewModel(key = "deep-links-$sessionKey")
    val splashDone    by feedViewModel.splashDone.collectAsStateWithLifecycle()
    val feedType      by feedViewModel.feedType.collectAsStateWithLifecycle()
    val userSets      by feedViewModel.userSetsFlow.collectAsStateWithLifecycle()
    val pinnedRelays  by feedViewModel.pinnedRelays.collectAsStateWithLifecycle()
    val currentFilter by feedViewModel.filterFlow.collectAsStateWithLifecycle()
    val globalFeedLens by feedViewModel.globalFeedLens.collectAsStateWithLifecycle()
    val isFeedRefreshing by feedViewModel.isRefreshing.collectAsStateWithLifecycle()
    val userAvatarUrl by feedViewModel.userAvatarUrl.collectAsStateWithLifecycle()
    val hasNewTopPost by feedViewModel.showDot.collectAsStateWithLifecycle()
    val hasNewNotifications by feedViewModel.hasNewNotifications.collectAsStateWithLifecycle()
    val zapPreferences by noteActionsVm.zapPreferences.collectAsStateWithLifecycle()
    val pendingDeepLink     by deepLinkVm.pendingTarget.collectAsStateWithLifecycle()
    val pendingDeepLinkFailure by deepLinkVm.pendingFailure.collectAsStateWithLifecycle()
    val graphPrompt by feedViewModel.graphOnboardingPrompt.collectAsStateWithLifecycle()
    val isPowerSaveMode = rememberPowerSaveMode()
    val animatorDurationScale = rememberAnimatorDurationScale()
    val headerMotionEnabled = feedHeaderMotionEnabled(isPowerSaveMode, animatorDurationScale)

    LaunchedEffect(graphPrompt.autoOpen) {
        if (graphPrompt.autoOpen) navigator.push(AppDestination.StartGraph)
    }

    LaunchedEffect(pendingDeepLinkFailure) {
        if (pendingDeepLinkFailure && deepLinkVm.consumeFailure()) {
            showSnackbar("Couldn't open link")
        }
    }

    LaunchedEffect(pendingDeepLink) {
        val target = pendingDeepLink ?: return@LaunchedEffect
        if (!deepLinkVm.consume(target)) return@LaunchedEffect

        when (target) {
            is DeepLinkTarget.Profile -> {
                deepLinkVm.prefetchProfile(target)
                navigator.replaceAboveTabs(AppDestination.Profile(target.pubkey))
            }
            is DeepLinkTarget.Note -> {
                navigator.replaceAboveTabs(AppDestination.Thread(
                    eventId = target.eventId,
                    relayHints = target.relayHints,
                ))
            }
            is DeepLinkTarget.Address -> {
                val eventId = deepLinkVm.resolveAddress(target)
                if (eventId == null) {
                    showSnackbar("Couldn't open link")
                } else {
                    navigator.replaceAboveTabs(AppDestination.Thread(
                        eventId = eventId,
                        relayHints = target.relayHints,
                        openArticleOnLoad = target.kind == 30023,
                    ))
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
    val activeTopBarHeight = if (selectedTab == 0) Sizing.feedTopBarHeight else Sizing.topBarHeight

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
    // Both insets belong inside the scrolling content, not around its viewport.
    // Keep them independent of bar visibility so only the overlays move.
    val staticTopPadding = activeTopBarHeight + statusBarHeight
    val staticBottomPadding = if (immersiveVideoMode) 0.dp else Sizing.bottomNavHeight + navBarHeight

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
        LocalOpenRelayDetail provides { url -> navigator.push(AppDestination.RelayDetail(url)) },
        LocalZapPreferences provides zapPreferences,
        com.unsilence.app.ui.common.LocalOpenEmojiSettings provides { navigator.push(AppDestination.EmojiSettings) },
        com.unsilence.app.ui.common.LocalOpenZapSettings provides { navigator.push(AppDestination.ZapSettings) },
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {

        AppNavDisplay(navigator) { entry ->
            when (val destination = entry.destination) {
                AppDestination.Tabs -> Box(
                    Modifier.fillMaxSize().nestedScroll(nestedScrollConnection),
                ) {
                    // ── Content ───────────────────────────────────────────────────────
                    // Full-height viewports let content pass behind the sliding bars.
                    // Each tab reserves bottom clearance in its own scrollable content.
                    Box(
                        modifier = Modifier
                            .fillMaxSize(),
                    ) {
                        when (selectedTab) {
                            0    -> FeedScreen(
                                onArticleClick = onArticleClick,
                                scrollToTopTrigger = scrollToTopTrigger,
                                topBarShown        = topBarShown,
                                staticTopPadding   = staticTopPadding,
                                staticBottomPadding = staticBottomPadding,
                                onNoteClick        = onNoteClick,
                                onComment          = { eventId -> navigator.push(AppDestination.Compose(replyToEventId = eventId)) },
                                onAuthorClick      = onAuthorClick,
                                onHashtagClick     = onHashtagClick,
                                onQuote            = { noteId  -> navigator.push(AppDestination.Compose(quoteEventId = noteId))  },
                                onPullRefreshProgress = updatePullRefreshFraction,
                                showFindPeopleEmptyState = graphPrompt.showEmptyFollowing,
                                onFindPeople = {
                                    navigator.push(AppDestination.StartGraph)
                                },
                                viewModel          = feedViewModel,
                                actionsViewModel   = noteActionsVm,
                            )
                            1    -> Box(Modifier.padding(top = statusBarHeight)) {
                                SearchScreen(
                                    onArticleClick = onArticleClick,
                                    staticBottomPadding = staticBottomPadding,
                                    onNoteClick   = onNoteClick,
                                    onComment     = { eventId -> navigator.push(AppDestination.Compose(replyToEventId = eventId)) },
                                    onAuthorClick = onAuthorClick,
                                    onHashtagClick = onHashtagClick,
                                    onQuote       = { noteId  -> navigator.push(AppDestination.Compose(quoteEventId = noteId))  },
                                    initialQuery  = hashtagSearchQuery,
                                    onInitialQueryConsumed = { hashtagSearchQuery = null },
                                    actionsViewModel = noteActionsVm,
                                )
                            }
                            2    -> NotificationsScreen(
                                onNoteClick      = onNoteClick,
                                onProfileClick   = onAuthorClick,
                                onHashtagClick   = onHashtagClick,
                                onQuote          = { navigator.push(AppDestination.Compose(quoteEventId = it)) },
                                actionsViewModel = noteActionsVm,
                                staticTopPadding = staticTopPadding,
                                staticBottomPadding = staticBottomPadding,
                                viewModel        = requireNotNull(notifViewModel),
                            )
                            3    -> ProfileScreen(
                                onArticleClick = onArticleClick,
                                onOpenSettings = { navigator.push(AppDestination.Settings) },
                                onEditProfile = { navigator.push(AppDestination.EditProfile) },
                                staticBottomPadding = staticBottomPadding,
                                scrollToTopTrigger = profileScrollToTopTrigger,
                                onBack = { selectedTab = 0 },
                                onNoteClick = onNoteClick,
                                onComment = { eventId -> navigator.push(AppDestination.Compose(replyToEventId = eventId)) },
                                onAuthorClick = onAuthorClick,
                                onQuote = { noteId -> navigator.push(AppDestination.Compose(quoteEventId = noteId)) },
                                onConnectionsClick = { tab -> navigator.push(AppDestination.Connections(ownPubkey, tab)) },
                                onRelaysClick = { navigator.push(AppDestination.ProfileRelays(ownPubkey)) },
                                onHashtagClick = onHashtagClick,
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
                                    current = requireNotNull(notifViewModel).filter.collectAsStateWithLifecycle().value,
                                    onChanged = { requireNotNull(notifViewModel).setFilter(it) },
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                        } else {
                            UnifiedFeedHeader(
                                feedType = feedType,
                                lens = globalFeedLens,
                                filter = currentFilter,
                                pullFraction = pullRefreshFraction,
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
                                    .clickable { navigator.push(AppDestination.Compose()) },
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
                    // Surface blocks touches in the bar's padding from reaching the feed.
                    // Keep this barrier on the moving overlay, not on the content below.
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .offset { IntOffset(0, bottomBarOffset.roundToPx()) }
                            .fillMaxWidth(),
                        color = Black,
                    ) {
                        Row(
                            modifier = Modifier
                                .navigationBarsPadding()
                                .height(Sizing.bottomNavHeight)
                                .padding(horizontal = Spacing.medium)
                                .selectableGroup(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TABS.forEachIndexed { index, tab ->
                                val isSelected = index == selectedTab
                                val iconSize = 24.dp  // constant — selection via tint only

                                // The entire tab slot is a target, not just the icon's circle.
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .selectable(
                                            selected = isSelected,
                                            role = Role.Tab,
                                            onClick = {
                                                when (tabReselectAction(index, selectedTab)) {
                                                    TabReselectAction.FEED_TOP -> {
                                                        scrollToTopTrigger++
                                                        feedViewModel.clearNewTopPost()
                                                    }
                                                    TabReselectAction.PROFILE_TOP -> profileScrollToTopTrigger++
                                                    TabReselectAction.NONE -> Unit
                                                }
                                                selectedTab = index
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
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
                    }

                }
                is AppDestination.Thread -> ThreadScreen(
                    onArticleClick = { row, focus -> navigator.push(articleDestination(row, focus)) },
                    entryId = entry.id,
                    eventId = destination.eventId,
                    relayHints = destination.relayHints,
                    openArticleOnLoad = destination.openArticleOnLoad,
                    onDismiss = navigator::pop,
                    onQuote = { navigator.push(AppDestination.Compose(quoteEventId = it)) },
                    onComment = { navigator.push(AppDestination.Compose(replyToEventId = it)) },
                    onAuthorClick = onAuthorClick,
                    onHashtagClick = onHashtagClick,
                    actionsViewModel = noteActionsVm,
                )
                is AppDestination.Profile -> UserProfileScreen(
                    onArticleClick = onArticleClick,
                    entryId = entry.id,
                    pubkey = destination.pubkey,
                    onDismiss = navigator::pop,
                    onNoteClick = onNoteClick,
                    onComment = { navigator.push(AppDestination.Compose(replyToEventId = it)) },
                    onAuthorClick = onAuthorClick,
                    onQuote = { navigator.push(AppDestination.Compose(quoteEventId = it)) },
                    onConnectionsClick = { navigator.push(AppDestination.Connections(destination.pubkey, it)) },
                    onRelaysClick = { navigator.push(AppDestination.ProfileRelays(destination.pubkey)) },
                    onHashtagClick = onHashtagClick,
                    actionsViewModel = noteActionsVm,
                )

                is AppDestination.Compose -> ComposeScreen(
                    navigationOwnsBack = true,
                    replyToEventId = destination.replyToEventId,
                    quoteEventId = destination.quoteEventId,
                    articleCommentTarget = destination.articleComment,
                    onDismiss = navigator::pop,
                    actionsViewModel = noteActionsVm,
                )
                is AppDestination.Article -> ArticleDestinationScreen(
                    entryId = entry.id,
                    destination = destination,
                    onDismiss = navigator::pop,
                    onNoteClick = onNoteClick,
                    onAuthorClick = onAuthorClick,
                    onHashtagClick = onHashtagClick,
                    onCompose = navigator::push,
                    actions = noteActionsVm,
                )
                AppDestination.Settings -> com.unsilence.app.ui.profile.SettingsScreen(
                    onDismiss = navigator::pop,
                    onLogout = onLogout,
                    onEditProfile = { navigator.push(AppDestination.EditProfile) },
                    onOpen = { navigator.push(settingsDestination(it)) },
                    navigationOwnsBack = true,
                )
                AppDestination.EditProfile -> com.unsilence.app.ui.profile.EditProfileScreen(
                    onDismiss = navigator::pop,
                    navigationOwnsBack = true,
                )
                AppDestination.MediaUploads -> com.unsilence.app.ui.settings.MediaUploadSettingsScreen(
                    onDismiss = navigator::pop,
                    navigationOwnsBack = true,
                )
                AppDestination.Filters -> com.unsilence.app.ui.profile.FiltersScreen(
                    onDismiss = navigator::pop,
                    navigationOwnsBack = true,
                )
                AppDestination.Keys -> com.unsilence.app.ui.settings.keys.KeysScreen(
                    onDismiss = navigator::pop,
                    navigationOwnsBack = true,
                )
                AppDestination.Console -> com.unsilence.app.ui.settings.console.ConsoleScreen(
                    onDismiss = navigator::pop,
                    navigationOwnsBack = true,
                )
                AppDestination.SocialGraph -> com.unsilence.app.ui.profile.SocialGraphScreen(
                    onDismiss = navigator::pop,
                    navigationOwnsBack = true,
                )
                AppDestination.Drafts -> com.unsilence.app.ui.drafts.DraftsScreen(
                    onDismiss = navigator::pop,
                    onResume = { navigator.push(AppDestination.ResumeDraft(it.key)) },
                    navigationOwnsBack = true,
                )
                is AppDestination.ResumeDraft -> ResumeDraftScreen(
                    draftKey = destination.key,
                    onDismiss = navigator::pop,
                    actions = noteActionsVm,
                )
                is AppDestination.EditRelaySet -> EditRelaySetScreen(
                    dTag = destination.dTag,
                    onDismiss = navigator::pop,
                )
                is AppDestination.Connections -> ConnectionsScreen(
                    pubkey = destination.pubkey,
                    initialTab = destination.tab,
                    onDismiss = navigator::pop,
                    onProfileClick = onAuthorClick,
                    navigationOwnsBack = true,
                )
                is AppDestination.ProfileRelays -> ProfileRelaysScreen(
                    pubkey = destination.pubkey,
                    onDismiss = navigator::pop,
                    onOpenRelay = { navigator.push(AppDestination.RelayDetail(it)) },
                    navigationOwnsBack = true,
                )
                is AppDestination.RelayDetail -> RelayDetailScreen(
                    relayUrl = destination.url,
                    onDismiss = navigator::pop,
                    onOpenProfile = onAuthorClick,
                    onBrowse = onBrowseRelayFeed,
                    navigationOwnsBack = true,
                )
                AppDestination.RelaySettings -> RelayManagementScreen(
                    onEditRelaySet = { dTag ->
                        navigator.push(dTag?.let(AppDestination::EditRelaySet) ?: AppDestination.CreateRelaySet)
                    },
                    onDismiss = navigator::pop,
                    onOpenDetail = { navigator.push(AppDestination.RelayDetail(it)) },
                    onOpenDiscovery = { navigator.push(AppDestination.RelayDiscovery) },
                    navigationOwnsBack = true,
                )
                AppDestination.RelayDiscovery -> RelayDiscoveryScreen(
                    onDismiss = navigator::pop,
                    onOpenDetail = { navigator.push(AppDestination.RelayDetail(it)) },
                    navigationOwnsBack = true,
                )
                AppDestination.CreateRelaySet -> RelaySetEditorScreen(
                    onDismiss = navigator::pop,
                    viewModel = hiltViewModel(key = "relay-editor-${entry.id}"),
                    navigationOwnsBack = true,
                )
                AppDestination.EmojiSettings -> com.unsilence.app.ui.settings.CustomEmojisScreen(
                    onDismiss = navigator::pop,
                    navigationOwnsBack = true,
                )
                AppDestination.ZapSettings -> com.unsilence.app.ui.settings.ZapSettingsScreen(
                    onDismiss = {
                        navigator.pop()
                        noteActionsVm.refreshNwcConfigured()
                    },
                    navigationOwnsBack = true,
                )
                AppDestination.StartGraph -> {
                    val vm: StartYourGraphViewModel = hiltViewModel()
                    val state by vm.uiState.collectAsStateWithLifecycle()
                    LaunchedEffect(vm) {
                        vm.landingEvents.collect { landing ->
                            navigator.popToTabs()
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
                    StartYourGraphScreen(
                        state = state,
                        onTogglePack = vm::togglePack,
                        onTogglePerson = vm::togglePerson,
                        onPersonVisible = vm::requestVisiblePerson,
                        onDone = vm::finish,
                        onRetry = vm::retry,
                    )
                }
            }
        }

            // ── Feed selector bottom sheet ───────────────────────────────────
            if (showFeedSheet) {
                val relayManagementVm: RelayManagementViewModel = hiltViewModel(key = "relay-sheet-$sessionKey")
                FeedSelectorSheet(
                    feedType        = feedType,
                    userSets        = userSets,
                    pinnedRelays    = pinnedRelays,
                    viewModel       = relayManagementVm,
                    onFeedChanged   = { type ->
                        feedViewModel.setFeedType(type)
                        showFeedSheet = false
                    },
                    onRemoveFavorite = { url -> relayManagementVm.removeFavoriteRelay(url) },
                    onNewRelaySet   = { showFeedSheet = false; navigator.push(AppDestination.CreateRelaySet) },
                    onRelaySettings = { showFeedSheet = false; navigator.push(AppDestination.RelaySettings) },
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
    pullFraction: State<Float>,
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
            .height(Sizing.feedTopBarHeight),
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
                barHeightScale = { barHeightScale.value },
                static = !motionEnabled,
                modifier = Modifier
                    .semantics { contentDescription = "Scroll feed to top" }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.foundation.LocalIndication.current,
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
    pullFraction: State<Float>,
    isRefreshing: Boolean,
    motionEnabled: Boolean,
): State<Float> {
    val animatedScale = remember { Animatable(1f) }
    LaunchedEffect(pullFraction, isRefreshing, motionEnabled) {
        snapshotFlow { if (isRefreshing) 0f else pullFraction.value }.collectLatest { fraction ->
            val target = effectivePullStretchFactor(fraction, motionEnabled)
            if (!motionEnabled || fraction > 0f) {
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
    }
    return animatedScale.asState()
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
                .background(FeedDividerBrush),
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
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick,
            )
            .padding(start = 10.dp, end = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.micro),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White,
            style = AppTextStyles.bodySmall,
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
                indication = androidx.compose.foundation.LocalIndication.current,
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
                style = AppTextStyles.footnote,
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
                indication = androidx.compose.foundation.LocalIndication.current,
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

    val selectedNow by rememberUpdatedState(current)
    val onChangedNow by rememberUpdatedState(onChanged)
    val haptic = LocalHapticFeedback.current

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
            .pointerInput(pagerState, haptic) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startingSelection = selectedNow
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.pressed) {
                            val dragY = change.positionChange().y
                            change.consume()
                            pagerState.dispatchRawDelta(-dragY)
                        } else {
                            break
                        }
                    } while (true)
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage)
                        val settled = items[pagerState.settledPage.mod(realCount)]
                        if (settled != selectedNow) onChangedNow(settled)
                        if (settled != startingSelection) {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
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
                    style = AppTextStyles.body,
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
    viewModel: RelayManagementViewModel,
    onFeedChanged: (FeedType) -> Unit,
    onRemoveFavorite: (String) -> Unit,
    onNewRelaySet: () -> Unit,
    onRelaySettings: () -> Unit,
    onDeleteSet: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val relayHealth by viewModel.relayHealth.collectAsStateWithLifecycle(initialValue = emptyMap())
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
                color      = if (selected) Brand else com.unsilence.app.ui.theme.White,
                style = AppTextStyles.bodyLarge,
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
                style = AppTextStyles.caption,
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
                    .background(com.unsilence.app.ui.theme.Surface2, RoundedCornerShape(2.dp)),
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
                        healthScore >= 40   -> com.unsilence.app.ui.theme.Zap
                        else                -> com.unsilence.app.ui.theme.Like
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
                            color      = if (selected) Brand else com.unsilence.app.ui.theme.White,
                            style = AppTextStyles.bodyLarge,
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
                Text("New Relay Set", color = Brand, style = AppTextStyles.body)
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
                Text("Manage relays", color = com.unsilence.app.ui.theme.TextSecondary, style = AppTextStyles.body)
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
                }) { Text("Delete", color = com.unsilence.app.ui.theme.Like) }
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
            style = AppTextStyles.bodyLarge,
        )
    }
}
