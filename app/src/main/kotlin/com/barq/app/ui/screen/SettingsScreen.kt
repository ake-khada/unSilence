package com.barq.app.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onRelays: () -> Unit = {},
    onMediaServers: () -> Unit = {},
    onKeys: () -> Unit = {},
    onSafety: () -> Unit = {},
    onSocialGraph: () -> Unit = {},
    onCustomEmojis: () -> Unit = {},
    onConsole: () -> Unit = {},
    onDrafts: () -> Unit = {},
    onLists: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Router, contentDescription = null) },
                label = { Text("Relays") },
                selected = false,
                onClick = onRelays,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
                label = { Text("Media Servers") },
                selected = false,
                onClick = onMediaServers,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                label = { Text("Keys") },
                selected = false,
                onClick = onKeys,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Block, contentDescription = null) },
                label = { Text("Safety") },
                selected = false,
                onClick = onSafety,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Hub, contentDescription = null) },
                label = { Text("Social Graph") },
                selected = false,
                onClick = onSocialGraph,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.EmojiEmotions, contentDescription = null) },
                label = { Text("Custom Emojis") },
                selected = false,
                onClick = onCustomEmojis,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.FormatListBulleted, contentDescription = null) },
                label = { Text("Lists") },
                selected = false,
                onClick = onLists,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                label = { Text("Drafts") },
                selected = false,
                onClick = onDrafts,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.BugReport, contentDescription = null) },
                label = { Text("Console") },
                selected = false,
                onClick = onConsole,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                icon = {
                    Icon(
                        Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                label = { Text("Logout", color = MaterialTheme.colorScheme.error) },
                selected = false,
                onClick = onLogout,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
    }
}
