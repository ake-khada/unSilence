package com.unsilence.app.ui.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.unsilence.app.ui.feed.FeedScreen
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
fun AppNavigation() {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var barsVisible by remember { mutableStateOf(true) }

    val density = LocalDensity.current
    val statusBarHeight = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val navBarHeight    = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }

    val topBarOffset by animateDpAsState(
        targetValue  = if (barsVisible) 0.dp else -(Sizing.topBarHeight + statusBarHeight + 8.dp),
        animationSpec = animSpec,
        label        = "topBarOffset",
    )
    val bottomBarOffset by animateDpAsState(
        targetValue  = if (barsVisible) 0.dp else (Sizing.bottomNavHeight + navBarHeight + 8.dp),
        animationSpec = animSpec,
        label        = "bottomBarOffset",
    )
    val contentTopPadding by animateDpAsState(
        targetValue  = if (barsVisible) Sizing.topBarHeight + statusBarHeight else 0.dp,
        animationSpec = animSpec,
        label        = "contentTopPadding",
    )
    val contentBottomPadding by animateDpAsState(
        targetValue  = if (barsVisible) Sizing.bottomNavHeight + navBarHeight else 0.dp,
        animationSpec = animSpec,
        label        = "contentBottomPadding",
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

        // ── Content — fills full screen, padded so it sits between bars at rest ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = contentTopPadding, bottom = contentBottomPadding),
        ) {
            when (selectedTab) {
                0    -> FeedScreen()
                else -> PlaceholderScreen()
            }
        }

        // ── Top bar overlay ───────────────────────────────────────────────────
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
                    Text(
                        text     = "Global ▾",
                        color    = Cyan,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 120.dp)
                            .clickable { /* Sprint 4: open feed selector */ },
                    )
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
                        modifier           = Modifier.size(Sizing.navIcon),
                    )
                }
            }
        }

        // ── Bottom nav overlay ────────────────────────────────────────────────
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
                IconButton(onClick = { selectedTab = index }) {
                    Icon(
                        imageVector        = tab.icon,
                        contentDescription = tab.contentDescription,
                        tint               = if (index == selectedTab) Cyan else NavUnselected,
                        modifier           = Modifier.size(Sizing.navIcon),
                    )
                }
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
