package com.unsilence.app.ui.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
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
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.ui.compose.ComposeScreen
import com.unsilence.app.ui.feed.FeedScreen
import com.unsilence.app.ui.search.SearchScreen
import com.unsilence.app.ui.thread.ThreadScreen
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.unsilence.app.ui.profile.ProfileScreen
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

private val NavUnselected = Color(0xFF555555)

private data class NavTab(val icon: ImageVector, val contentDescription: String)

private val TABS = listOf(
    NavTab(Icons.Filled.Home,          "Home"),
    NavTab(Icons.Filled.Search,        "Search"),
    NavTab(Icons.Filled.Notifications, "Notifications"),
    NavTab(Icons.Filled.Person,        "Profile"),
)

private val animSpec = tween<androidx.compose.ui.unit.Dp>(250, easing = FastOutSlowInEasing)

@Composable
fun AppNavigation(keyManager: KeyManager, onLogout: () -> Unit) {
    var selectedTab        by rememberSaveable { mutableIntStateOf(0) }
    var barsVisible        by remember { mutableStateOf(true) }
    var showCompose        by remember { mutableStateOf(false) }
    var showFeedDropdown   by remember { mutableStateOf(false) }
    var threadEventId      by remember { mutableStateOf<String?>(null) }
    var quoteNoteId        by remember { mutableStateOf<String?>(null) }
    var scrollToTopTrigger by remember { mutableIntStateOf(0) }

    // Shared FeedViewModel instance — same object FeedScreen uses (same Activity scope)
    val feedViewModel: FeedViewModel = hiltViewModel()
    val feedType by feedViewModel.feedType.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val density = LocalDensity.current
    val statusBarHeight = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val navBarHeight    = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }

    val topBarOffset by animateDpAsState(
        targetValue   = if (barsVisible) 0.dp else -(Sizing.topBarHeight + statusBarHeight + 8.dp),
        animationSpec = animSpec,
        label         = "topBarOffset",
    )
    val bottomBarOffset by animateDpAsState(
        targetValue   = if (barsVisible) 0.dp else (Sizing.bottomNavHeight + navBarHeight + 8.dp),
        animationSpec = animSpec,
        label         = "bottomBarOffset",
    )
    val contentTopPadding by animateDpAsState(
        targetValue   = if (barsVisible) Sizing.topBarHeight + statusBarHeight else 0.dp,
        animationSpec = animSpec,
        label         = "contentTopPadding",
    )
    val contentBottomPadding by animateDpAsState(
        targetValue   = if (barsVisible) Sizing.bottomNavHeight + navBarHeight else 0.dp,
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

    ModalNavigationDrawer(
        drawerState   = drawerState,
        drawerContent = {
            AppDrawer(
                keyManager = keyManager,
                onLogout   = onLogout,
            )
        },
    ) {
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
                        onQuote            = { noteId  -> quoteNoteId   = noteId  },
                    )
                    1    -> SearchScreen(
                        onNoteClick = { eventId -> threadEventId = eventId },
                        onQuote     = { noteId  -> quoteNoteId   = noteId  },
                    )
                    3    -> ProfileScreen()
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
                                    .clickable { showFeedDropdown = true },
                            )
                            DropdownMenu(
                                expanded         = showFeedDropdown,
                                onDismissRequest = { showFeedDropdown = false },
                            ) {
                                DropdownMenuItem(
                                    text    = { Text("Global",    color = if (feedType == FeedType.GLOBAL)    Cyan else Color.White, fontSize = 14.sp) },
                                    onClick = { feedViewModel.setFeedType(FeedType.GLOBAL);    showFeedDropdown = false },
                                )
                                DropdownMenuItem(
                                    text    = { Text("Following", color = if (feedType == FeedType.FOLLOWING) Cyan else Color.White, fontSize = 14.sp) },
                                    onClick = { feedViewModel.setFeedType(FeedType.FOLLOWING); showFeedDropdown = false },
                                )
                            }
                        }
                        Icon(
                            imageVector        = Icons.Filled.Tune,
                            contentDescription = "Filter",
                            tint               = NavUnselected,
                            modifier           = Modifier.size(Sizing.navIcon),
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
                    IconButton(onClick = {
                        if (index == 0 && selectedTab == 0) scrollToTopTrigger++
                        selectedTab = index
                    }) {
                        Box {
                            Icon(
                                imageVector        = tab.icon,
                                contentDescription = tab.contentDescription,
                                tint               = if (index == selectedTab) Cyan else NavUnselected,
                                modifier           = Modifier.size(Sizing.navIcon),
                            )
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

            // ── Compose overlay ───────────────────────────────────────────────
            if (showCompose) {
                ComposeScreen(onDismiss = { showCompose = false })
            }

            // ── Thread overlay ────────────────────────────────────────────────
            threadEventId?.let { eventId ->
                ThreadScreen(
                    eventId   = eventId,
                    onDismiss = { threadEventId = null },
                    onQuote   = { noteId -> quoteNoteId = noteId },
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
