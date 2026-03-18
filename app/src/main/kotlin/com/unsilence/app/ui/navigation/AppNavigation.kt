package com.unsilence.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.unsilence.app.data.db.entity.NostrRelaySetEntity
import com.unsilence.app.ui.relays.RelayManagementViewModel
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.ui.feed.FeedType
import com.unsilence.app.ui.feed.FeedViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unsilence.app.ui.compose.ComposeScreen
import com.unsilence.app.ui.feed.FeedScreen
import com.unsilence.app.ui.feed.FilterScreen
import com.unsilence.app.ui.notifications.NotificationsScreen
import com.unsilence.app.ui.relays.CreateRelaySetScreen
import com.unsilence.app.ui.relays.RelayManagementScreen
import com.unsilence.app.ui.search.SearchScreen
import com.unsilence.app.ui.thread.ThreadScreen
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.unsilence.app.ui.profile.ProfileScreen
import com.unsilence.app.ui.profile.UserProfileScreen
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

private val NavUnselected = Color(0xFF555555)

private data class NavTab(val icon: ImageVector, val contentDescription: String)

private val TABS = listOf(
    NavTab(Icons.Outlined.Home,          "Home"),
    NavTab(Icons.Outlined.Search,        "Search"),
    NavTab(Icons.Outlined.Notifications, "Notifications"),
    NavTab(Icons.Outlined.Person,        "Profile"),
)

private val animSpec = tween<androidx.compose.ui.unit.Dp>(250, easing = FastOutSlowInEasing)

@Composable
fun AppNavigation(onLogout: () -> Unit) {
    var selectedTab          by rememberSaveable { mutableIntStateOf(0) }
    var barsVisible          by remember { mutableStateOf(true) }
    var showCompose          by remember { mutableStateOf(false) }
    var showFeedDropdown     by remember { mutableStateOf(false) }
    var showFilter           by remember { mutableStateOf(false) }
    var showCreateRelaySet   by remember { mutableStateOf(false) }
    var showRelaySettings    by remember { mutableStateOf(false) }
    var threadEventId        by remember { mutableStateOf<String?>(null) }
    var quoteNoteId          by remember { mutableStateOf<String?>(null) }
    var userProfilePubkey    by remember { mutableStateOf<String?>(null) }
    var scrollToTopTrigger   by remember { mutableIntStateOf(0) }

    BackHandler(enabled = selectedTab != 0) { selectedTab = 0 }

    // Single lambda passed to every NoteCard-hosting screen.
    // Opening a user profile does NOT open the own-profile tab — it opens the overlay.
    val onAuthorClick: (String) -> Unit = { pubkey -> userProfilePubkey = pubkey }

    // Shared FeedViewModel instance — same object FeedScreen uses (same Activity scope)
    val feedViewModel: FeedViewModel = hiltViewModel()
    val relayManagementVm: RelayManagementViewModel = hiltViewModel()
    val feedType      by feedViewModel.feedType.collectAsStateWithLifecycle()
    val userSets      by feedViewModel.userSetsFlow.collectAsStateWithLifecycle()
    val currentFilter by feedViewModel.filterFlow.collectAsStateWithLifecycle()
    val userAvatarUrl by feedViewModel.userAvatarUrl.collectAsStateWithLifecycle()

    val density = LocalDensity.current
    val statusBarHeight = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val navBarHeight    = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }

    // Profile tab (index 3) has its own top bar — hide the app top bar but keep bottom nav.
    val topBarShown    = barsVisible && selectedTab != 3
    val bottomBarShown = barsVisible

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
    val contentTopPadding by animateDpAsState(
        targetValue   = if (topBarShown) Sizing.topBarHeight + statusBarHeight else 0.dp,
        animationSpec = animSpec,
        label         = "contentTopPadding",
    )
    val contentBottomPadding by animateDpAsState(
        targetValue   = if (bottomBarShown) Sizing.bottomNavHeight + navBarHeight else 0.dp,
        animationSpec = animSpec,
        label         = "contentBottomPadding",
    )

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                when {
                    available.y < -0.5f -> barsVisible = false
                    available.y >  0.5f -> barsVisible = true
                }
                return Offset.Zero
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .nestedScroll(nestedScrollConnection),
    ) {

            // ── Content ───────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = contentTopPadding, bottom = contentBottomPadding),
            ) {
                when (selectedTab) {
                    0    -> FeedScreen(
                        scrollToTopTrigger = scrollToTopTrigger,
                        onNoteClick        = { eventId -> threadEventId = eventId },
                        onAuthorClick      = onAuthorClick,
                        onQuote            = { noteId  -> quoteNoteId   = noteId  },
                    )
                    1    -> SearchScreen(
                        onNoteClick   = { eventId -> threadEventId = eventId },
                        onAuthorClick = onAuthorClick,
                        onQuote       = { noteId  -> quoteNoteId   = noteId  },
                    )
                    2    -> NotificationsScreen(
                        onNoteClick = { eventId -> threadEventId = eventId },
                    )
                    3    -> ProfileScreen(onLogout = onLogout, onBack = { selectedTab = 0 }, onNoteClick = { eventId -> threadEventId = eventId }, onAuthorClick = onAuthorClick)
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text       = "unSilence",
                        color      = Color.White,
                        fontSize   = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(Modifier.weight(1f))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        Box {
                            Text(
                                text     = "${feedViewModel.feedTypeLabel} ▾",
                                color    = Cyan,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .widthIn(max = 120.dp)
                                    .clickable { showFeedDropdown = !showFeedDropdown },
                            )

                            if (showFeedDropdown) {
                                FeedPickerPopup(
                                    feedType       = feedType,
                                    userSets       = userSets,
                                    onSelect       = { type ->
                                        feedViewModel.setFeedType(type)
                                        showFeedDropdown = false
                                    },
                                    onNewRelaySet  = { showFeedDropdown = false; showCreateRelaySet = true },
                                    onRelaySettings = { showFeedDropdown = false; showRelaySettings = true },
                                    onDeleteSet    = { dTag ->
                                        relayManagementVm.deleteRelaySet(dTag)
                                        if (feedType is FeedType.RelaySet && (feedType as FeedType.RelaySet).dTag == dTag) {
                                            feedViewModel.setFeedType(FeedType.Global)
                                        }
                                    },
                                    onDismiss      = { showFeedDropdown = false },
                                )
                            }
                        }
                        Icon(
                            imageVector        = Icons.Filled.Tune,
                            contentDescription = "Filter",
                            tint               = if (currentFilter.isNonDefault) Cyan else NavUnselected,
                            modifier           = Modifier
                                .size(Sizing.navIcon)
                                .clickable { showFilter = true },
                        )
                        Icon(
                            imageVector        = Icons.Filled.Edit,
                            contentDescription = "New post",
                            tint               = Cyan,
                            modifier           = Modifier
                                .size(Sizing.navIcon)
                                .clickable { showCompose = true },
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
                    val iconSize   = if (isSelected) Sizing.navIconSelected else Sizing.navIcon

                    IconButton(onClick = {
                        if (index == 0 && selectedTab == 0) {
                            scrollToTopTrigger++
                            feedViewModel.clearNewTopPost()
                        }
                        selectedTab = index
                    }) {
                        Box(contentAlignment = Alignment.Center) {
                            if (index == 3 && userAvatarUrl != null) {
                                // Profile tab — show user avatar
                                Box(
                                    modifier = Modifier
                                        .size(iconSize)
                                        .then(
                                            if (isSelected) Modifier.border(1.5.dp, Cyan, CircleShape)
                                            else Modifier
                                        )
                                        .clip(CircleShape),
                                ) {
                                    AsyncImage(
                                        model = userAvatarUrl,
                                        contentDescription = "Profile",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector        = tab.icon,
                                    contentDescription = tab.contentDescription,
                                    tint               = if (isSelected) Cyan else NavUnselected,
                                    modifier           = Modifier.size(iconSize),
                                )
                            }
                            if (index == 0 && feedViewModel.hasNewTopPost) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .align(Alignment.TopEnd)
                                        .background(Cyan, CircleShape),
                                )
                            }
                        }
                    }
                }
            }

            // ── Filter overlay ────────────────────────────────────────────────
            if (showFilter) {
                FilterScreen(
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
                        feedViewModel.setFeedType(FeedType.SingleRelay(url, label))
                        showRelaySettings = false
                    },
                )
            }

            // ── Compose overlay ───────────────────────────────────────────────
            if (showCompose) {
                ComposeScreen(onDismiss = { showCompose = false })
            }

            // ── Thread overlay ────────────────────────────────────────────────
            threadEventId?.let { eventId ->
                ThreadScreen(
                    eventId       = eventId,
                    onDismiss     = { threadEventId = null },
                    onQuote       = { noteId -> quoteNoteId = noteId },
                    onAuthorClick = onAuthorClick,
                )
            }

            // ── User profile overlay ──────────────────────────────────────────
            userProfilePubkey?.let { pubkey ->
                UserProfileScreen(
                    pubkey        = pubkey,
                    onDismiss     = { userProfilePubkey = null },
                    onNoteClick   = { eventId -> threadEventId = eventId },
                    onAuthorClick = onAuthorClick,
                )
            }

            // ── Quote-compose overlay ─────────────────────────────────────────
            quoteNoteId?.let { noteId ->
                val nevent = NEvent.create(noteId, null, null, null as NormalizedRelayUrl?)
                ComposeScreen(
                    initialText = "\n\nnostr:$nevent",
                    onDismiss   = { quoteNoteId = null },
                )
            }
        }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedPickerPopup(
    feedType: FeedType,
    userSets: List<NostrRelaySetEntity>,
    onSelect: (FeedType) -> Unit,
    onNewRelaySet: () -> Unit,
    onRelaySettings: () -> Unit,
    onDeleteSet: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDeleteDTag by remember { mutableStateOf<String?>(null) }

    Popup(
        alignment = Alignment.TopEnd,
        offset    = IntOffset(0, 0),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(160.dp)
                .background(Color(0xFF111111), RoundedCornerShape(8.dp))
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            data class FeedItem(val type: FeedType?, val label: String, val isDivider: Boolean = false, val dTag: String? = null)

            val items = buildList {
                add(FeedItem(FeedType.Global, "Global"))
                add(FeedItem(FeedType.Following, "Following"))
                if (userSets.isNotEmpty()) {
                    add(FeedItem(null, "", isDivider = true))
                    userSets.forEach { set ->
                        add(FeedItem(FeedType.RelaySet(set.dTag, set.title ?: set.dTag), set.title ?: set.dTag, dTag = set.dTag))
                    }
                }
            }

            val currentIndex = items.indexOfFirst { item ->
                item.type != null && when {
                    item.type is FeedType.Global && feedType is FeedType.Global -> true
                    item.type is FeedType.Following && feedType is FeedType.Following -> true
                    item.type is FeedType.RelaySet && feedType is FeedType.RelaySet ->
                        item.type.dTag == feedType.dTag
                    else -> false
                }
            }.coerceAtLeast(0)

            val spinnerState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))
            val flingBehavior = rememberSnapFlingBehavior(lazyListState = spinnerState)

            val itemHeight = 36.dp
            val spinnerHeight = itemHeight * 3

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(spinnerHeight),
            ) {
                LazyColumn(
                    state = spinnerState,
                    flingBehavior = flingBehavior,
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item { Spacer(Modifier.height(itemHeight)) }

                    itemsIndexed(items) { index, item ->
                        if (item.isDivider) {
                            HorizontalDivider(
                                color = Color(0xFF333333),
                                modifier = Modifier
                                    .height(itemHeight)
                                    .padding(vertical = 14.dp)
                                    .fillMaxWidth(0.5f),
                            )
                        } else {
                            val centerIndex by remember {
                                derivedStateOf {
                                    val layoutInfo = spinnerState.layoutInfo
                                    val center = layoutInfo.viewportStartOffset + layoutInfo.viewportSize.height / 2
                                    layoutInfo.visibleItemsInfo.minByOrNull {
                                        kotlin.math.abs((it.offset + it.size / 2) - center)
                                    }?.index?.minus(1) ?: 0
                                }
                            }
                            val isCenter = index == centerIndex
                            Box(
                                modifier = Modifier
                                    .height(itemHeight)
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { item.type?.let { onSelect(it) } },
                                        onLongClick = {
                                            if (item.dTag != null) confirmDeleteDTag = item.dTag
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text       = item.label,
                                    color      = if (isCenter) Cyan else TextSecondary,
                                    fontSize   = if (isCenter) 14.sp else 12.sp,
                                    fontWeight = if (isCenter) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }

                    item { Spacer(Modifier.height(itemHeight)) }
                }
            }

            HorizontalDivider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text     = "+ New Relay Set",
                color    = Cyan,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNewRelaySet() }
                    .padding(horizontal = Spacing.medium, vertical = 8.dp),
            )
            Text(
                text     = "⚙ Relay Settings",
                color    = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRelaySettings() }
                    .padding(horizontal = Spacing.medium, vertical = 8.dp),
            )
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
                    Text("Cancel", color = Cyan)
                }
            },
            containerColor = Color(0xFF1A1A1A),
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
