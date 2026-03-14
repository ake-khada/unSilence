package com.unsilence.app.ui.profile

import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.TextSecondary

enum class ProfileTab { NOTES, REPLIES, LONGFORM }

@Composable
fun ProfileTabRow(
    selectedTab: ProfileTab,
    onTabSelected: (ProfileTab) -> Unit,
) {
    TabRow(
        selectedTabIndex = selectedTab.ordinal,
        containerColor   = Black,
        contentColor     = Color.White,
        indicator        = { tabPositions ->
            if (selectedTab.ordinal < tabPositions.size) {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                    color    = Cyan,
                )
            }
        },
    ) {
        ProfileTab.entries.forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick  = { onTabSelected(tab) },
                text     = {
                    Text(
                        text  = when (tab) {
                            ProfileTab.NOTES    -> "Notes"
                            ProfileTab.REPLIES  -> "Replies"
                            ProfileTab.LONGFORM -> "Longform"
                        },
                        color = if (selectedTab == tab) Color.White else TextSecondary,
                    )
                },
            )
        }
    }
}
