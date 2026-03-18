package com.unsilence.app.ui.relays

import com.unsilence.app.data.relay.normalizeRelayUrl
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

@Composable
fun CreateRelaySetScreen(
    onDismiss: () -> Unit,
    viewModel: RelayManagementViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onDismiss)
    var name by remember { mutableStateOf("") }
    val relayUrls = remember { mutableStateListOf<String>() }
    var newRelayUrl by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top bar ───────────────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Sizing.topBarHeight)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector        = Icons.Filled.Close,
                        contentDescription = "Cancel",
                        tint               = Color.White,
                    )
                }
                Text(
                    text     = "New Relay Set",
                    color    = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick  = {
                        if (name.isNotBlank() && relayUrls.isNotEmpty()) {
                            viewModel.createRelaySet(name.trim(), relayUrls.toList())
                            onDismiss()
                        }
                    },
                    enabled  = name.isNotBlank() && relayUrls.isNotEmpty(),
                ) {
                    Text(
                        text  = "Create",
                        color = if (name.isNotBlank() && relayUrls.isNotEmpty()) Cyan else TextSecondary,
                    )
                }
            }
            } // end statusBarsPadding Box

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // ── Name field ────────────────────────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small)) {
                Text(text = "Name", color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                BasicTextField(
                    value         = name,
                    onValueChange = { name = it },
                    textStyle     = TextStyle(color = Color.White, fontSize = 15.sp),
                    cursorBrush   = SolidColor(Cyan),
                    singleLine    = true,
                    decorationBox = { inner ->
                        Box {
                            if (name.isEmpty()) {
                                Text("e.g. Bitcoin Relays", color = TextSecondary, fontSize = 15.sp)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }

            // ── Add relay row ─────────────────────────────────────────────────
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value         = newRelayUrl,
                    onValueChange = { newRelayUrl = it },
                    textStyle     = TextStyle(color = Color.White, fontSize = 14.sp),
                    cursorBrush   = SolidColor(Cyan),
                    singleLine    = true,
                    decorationBox = { inner ->
                        Box(modifier = Modifier.weight(1f)) {
                            if (newRelayUrl.isEmpty()) {
                                Text("wss://relay.example.com", color = TextSecondary, fontSize = 14.sp)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick  = {
                        val normalized = normalizeRelayUrl(newRelayUrl)
                        if (normalized != null && !relayUrls.contains(normalized)) {
                            relayUrls.add(normalized)
                            newRelayUrl = ""
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector        = Icons.Filled.Add,
                        contentDescription = "Add relay",
                        tint               = Cyan,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // ── Relay list ────────────────────────────────────────────────────
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(relayUrls) { index, url ->
                    Row(
                        modifier          = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.medium, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text     = url,
                            color    = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick  = { relayUrls.removeAt(index) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector        = Icons.Filled.Delete,
                                contentDescription = "Remove",
                                tint               = TextSecondary,
                                modifier           = Modifier.size(18.dp),
                            )
                        }
                    }
                    HorizontalDivider(
                        color     = MaterialTheme.colorScheme.surfaceVariant,
                        thickness = 0.5.dp,
                    )
                }
            }
        }
    }
}
