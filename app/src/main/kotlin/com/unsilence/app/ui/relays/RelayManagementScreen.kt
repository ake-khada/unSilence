package com.unsilence.app.ui.relays

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.db.entity.OwnRelayEntity
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

@Composable
fun RelayManagementScreen(
    onDismiss: () -> Unit,
    viewModel: RelayManagementViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onDismiss)
    val relays by viewModel.relays.collectAsStateWithLifecycle(initialValue = emptyList())
    var newRelayUrl by remember { mutableStateOf("") }

    Box(
        modifier = Modifier.fillMaxSize().background(Black),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Black)
                    .statusBarsPadding()
                    .height(Sizing.topBarHeight)
                    .padding(horizontal = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Text("Relays", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // Add relay input
            Row(
                modifier = Modifier
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
                    modifier      = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (newRelayUrl.isEmpty()) {
                                Text("wss://relay.example.com", color = TextSecondary, fontSize = 14.sp)
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(Spacing.small))
                IconButton(
                    onClick = {
                        if (newRelayUrl.isNotBlank()) {
                            viewModel.addRelay(newRelayUrl)
                            newRelayUrl = ""
                        }
                    },
                ) {
                    Icon(Icons.Filled.Add, "Add relay", tint = Cyan)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)

            // Relay list
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(relays, key = { it.url }) { relay ->
                    RelayRow(
                        relay        = relay,
                        onToggleRead  = { viewModel.toggleRead(relay) },
                        onToggleWrite = { viewModel.toggleWrite(relay) },
                        onDelete      = { viewModel.removeRelay(relay.url) },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        thickness = 0.5.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun RelayRow(
    relay: OwnRelayEntity,
    onToggleRead: () -> Unit,
    onToggleWrite: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = relay.url.removePrefix("wss://"),
                color    = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
            )
            Row {
                MarkerChip(label = "Read", active = relay.read, onClick = onToggleRead)
                Spacer(Modifier.width(6.dp))
                MarkerChip(label = "Write", active = relay.write, onClick = onToggleWrite)
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Delete, "Remove relay", tint = Color(0xFFCF6679), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun MarkerChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text     = label,
        color    = if (active) Cyan else TextSecondary,
        fontSize = 11.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                if (active) Cyan.copy(alpha = 0.1f) else Color.Transparent,
                RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
