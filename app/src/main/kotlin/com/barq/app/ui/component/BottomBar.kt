package com.barq.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.barq.app.Routes

enum class BottomTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME(Routes.FEED, "Home", Icons.Filled.Home, Icons.Outlined.Home),
    SEARCH(Routes.SEARCH, "Search", Icons.Filled.Search, Icons.Outlined.Search),
    NOTIFICATIONS(Routes.NOTIFICATIONS, "Notifications", Icons.Filled.Notifications, Icons.Outlined.Notifications),
    PROFILE(Routes.MY_PROFILE, "Profile", Icons.Filled.Person, Icons.Outlined.Person)
}

@Composable
fun BarqBottomBar(
    currentRoute: String?,
    hasUnreadHome: Boolean,
    hasUnreadNotifications: Boolean,
    isZapAnimating: Boolean = false,
    onTabSelected: (BottomTab) -> Unit
) {
    NavigationBar(
        containerColor = Color(0xFF000000),
        windowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        BottomTab.entries.forEach { tab ->
            val selected = currentRoute == tab.route
            val hasUnread = when (tab) {
                BottomTab.HOME -> hasUnreadHome
                BottomTab.NOTIFICATIONS -> hasUnreadNotifications
                else -> false
            }

            NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(tab) },
                icon = {
                    Box(
                        modifier = Modifier.requiredSize(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val icon = if (tab == BottomTab.NOTIFICATIONS && isZapAnimating)
                            Icons.Outlined.ElectricBolt
                        else if (selected) tab.selectedIcon
                        else tab.unselectedIcon
                        Icon(
                            imageVector = icon,
                            contentDescription = tab.label
                        )
                        if (hasUnread) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .align(Alignment.TopEnd)
                                    .offset(x = 2.dp, y = (-2).dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = CircleShape
                                    )
                            )
                        }
                        if (tab == BottomTab.NOTIFICATIONS) {
                            ZapBurstEffect(
                                isActive = isZapAnimating,
                                modifier = Modifier
                                    .size(120.dp)
                                    .layout { measurable, constraints ->
                                        val placeable = measurable.measure(
                                            constraints.copy(
                                                minWidth = 0,
                                                minHeight = 0
                                            )
                                        )
                                        layout(0, 0) {
                                            placeable.place(
                                                -placeable.width / 2,
                                                -placeable.height / 2
                                            )
                                        }
                                    }
                            )
                        }
                    }
                },
                label = null
            )
        }
    }
}
