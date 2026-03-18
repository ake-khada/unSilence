package com.unsilence.app.ui.relays

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.db.entity.NostrRelaySetEntity
import com.unsilence.app.data.db.entity.NostrRelaySetMemberEntity
import com.unsilence.app.data.db.entity.RelayConfigEntity
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

private val TabNames = listOf("Inbox/Outbox", "Index", "Search")

@Composable
fun RelayManagementScreen(
    onDismiss: () -> Unit,
    viewModel: RelayManagementViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onDismiss)
    var selectedTab by remember { mutableIntStateOf(0) }

    val readWriteRelays by viewModel.readWriteRelays.collectAsStateWithLifecycle(initialValue = emptyList())
    val indexerRelays   by viewModel.indexerRelays.collectAsStateWithLifecycle(initialValue = emptyList())
    val searchRelays    by viewModel.searchRelays.collectAsStateWithLifecycle(initialValue = emptyList())
    val blockedRelays   by viewModel.blockedRelays.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteRelays  by viewModel.favoriteRelays.collectAsStateWithLifecycle(initialValue = emptyList())
    val relaySets       by viewModel.relaySets.collectAsStateWithLifecycle(initialValue = emptyList())

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top bar ─────────────────────────────────────────────────────
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
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint               = Color.White,
                        )
                    }
                    Text(
                        text       = "Relay Settings",
                        color      = Color.White,
                        fontSize   = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // ── Tab row ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TabNames.forEachIndexed { index, name ->
                    val isSelected = index == selectedTab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Cyan.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { selectedTab = index }
                            .padding(vertical = Spacing.small),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text       = name,
                            color      = if (isSelected) Cyan else TextSecondary,
                            fontSize   = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF222222))

            // ── Scrollable content ──────────────────────────────────────────
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // ── Tab content ─────────────────────────────────────────────
                when (selectedTab) {
                    0 -> {
                        // Inbox/Outbox (kind 10002)
                        item {
                            AddRelayInput(placeholder = "wss://relay.example.com") { url ->
                                viewModel.addReadWriteRelay(url)
                            }
                        }
                        items(readWriteRelays, key = { it.id }) { relay ->
                            ReadWriteRelayRow(
                                relay         = relay,
                                onToggleMarker = { viewModel.toggleMarker(relay) },
                                onRemove       = { viewModel.removeReadWriteRelay(relay.relayUrl) },
                            )
                        }
                    }
                    1 -> {
                        // Index (kind 99, local-only)
                        item {
                            AddRelayInput(placeholder = "wss://indexer.example.com") { url ->
                                viewModel.addIndexerRelay(url)
                            }
                        }
                        if (indexerRelays.isEmpty()) {
                            item {
                                Text(
                                    text     = "No indexer relays configured. Profile and follow list resolution will not work.",
                                    color    = Color(0xFFFF6B6B),
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(Spacing.medium),
                                )
                            }
                        }
                        items(indexerRelays, key = { it.id }) { relay ->
                            SimpleRelayRow(
                                url      = relay.relayUrl,
                                onRemove = { viewModel.removeIndexerRelay(relay.relayUrl) },
                            )
                        }
                    }
                    2 -> {
                        // Search (kind 10007)
                        item {
                            AddRelayInput(placeholder = "wss://search.example.com") { url ->
                                viewModel.addSearchRelay(url)
                            }
                        }
                        items(searchRelays, key = { it.id }) { relay ->
                            SimpleRelayRow(
                                url      = relay.relayUrl,
                                onRemove = { viewModel.removeSearchRelay(relay.relayUrl) },
                            )
                        }
                    }
                }

                // ── Divider between tabs and sections ───────────────────────
                item { Spacer(Modifier.height(Spacing.medium)) }
                item { HorizontalDivider(color = Color(0xFF222222)) }

                // ── Collapsible: Relay Sets (kind 30002) ────────────────────
                item {
                    CollapsibleSection(
                        title = "Relay Sets",
                        count = relaySets.size,
                    ) {
                        relaySets.forEach { set ->
                            RelaySetRow(
                                set       = set,
                                viewModel = viewModel,
                                onDelete  = { viewModel.deleteRelaySet(set.dTag) },
                            )
                        }
                    }
                }

                // ── Collapsible: Favorites (kind 10012) ─────────────────────
                item {
                    CollapsibleSection(
                        title = "Favorites",
                        count = favoriteRelays.size,
                    ) {
                        AddRelayInput(placeholder = "wss://favorite.example.com") { url ->
                            viewModel.addFavoriteRelay(url)
                        }
                        favoriteRelays.filter { it.setRef == null }.forEach { relay ->
                            SimpleRelayRow(
                                url      = relay.relayUrl,
                                onRemove = { viewModel.removeFavoriteRelay(relay.relayUrl) },
                            )
                        }
                        // Display existing set-reference favorites
                        favoriteRelays.mapNotNull { it.setRef }.forEach { ref ->
                            SimpleRelayRow(
                                url      = ref,
                                onRemove = { viewModel.removeFavoriteSetRef(ref) },
                            )
                        }
                        // Set picker — add existing relay sets as favorites
                        val pk = viewModel.ownerPubkey
                        if (pk != null && relaySets.isNotEmpty()) {
                            val unfavorited = relaySets.filter { set ->
                                val ref = "30002:$pk:${set.dTag}"
                                favoriteRelays.none { it.setRef == ref }
                            }
                            if (unfavorited.isNotEmpty()) {
                                Text(
                                    text     = "Add relay set to favorites",
                                    color    = TextSecondary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(
                                        start = Spacing.medium,
                                        top = 8.dp,
                                        bottom = 4.dp,
                                    ),
                                )
                                unfavorited.forEach { set ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.addFavoriteSetRef("30002:$pk:${set.dTag}")
                                            }
                                            .padding(horizontal = Spacing.medium, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Filled.Add,
                                            contentDescription = "Add",
                                            tint = Cyan,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text     = set.title ?: set.dTag,
                                            color    = Color.White,
                                            fontSize = 14.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Collapsible: Blocked (kind 10006) ───────────────────────
                item {
                    CollapsibleSection(
                        title = "Blocked",
                        count = blockedRelays.size,
                    ) {
                        AddRelayInput(placeholder = "wss://blocked.example.com") { url ->
                            viewModel.addBlockedRelay(url)
                        }
                        blockedRelays.forEach { relay ->
                            SimpleRelayRow(
                                url      = relay.relayUrl,
                                onRemove = { viewModel.removeBlockedRelay(relay.relayUrl) },
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(Spacing.xl)) }
            }
        }
    }
}

// ── Sub-composables ─────────────────────────────────────────────────────────

@Composable
private fun AddRelayInput(placeholder: String, onAdd: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value         = input,
            onValueChange = { input = it },
            textStyle     = TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush   = SolidColor(Cyan),
            singleLine    = true,
            decorationBox = { inner ->
                Box(modifier = Modifier.weight(1f)) {
                    if (input.isEmpty()) {
                        Text(placeholder, color = TextSecondary, fontSize = 14.sp)
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = {
                if (input.isNotBlank()) {
                    onAdd(input.trim())
                    input = ""
                }
            },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add", tint = Cyan)
        }
    }
}

/** Display URL without wss:// prefix for compactness. */
private fun displayUrl(url: String): String =
    url.removePrefix("wss://").removePrefix("ws://")

@Composable
private fun SimpleRelayRow(url: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = displayUrl(url),
            color    = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = TextSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ReadWriteRelayRow(
    relay: RelayConfigEntity,
    onToggleMarker: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = displayUrl(relay.relayUrl),
            color    = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // R/W marker chip
        val markerLabel = when (relay.marker) {
            "read"  -> "R"
            "write" -> "W"
            else    -> "R/W"
        }
        Text(
            text     = markerLabel,
            color    = Cyan,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Cyan.copy(alpha = 0.15f))
                .clickable { onToggleMarker() }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = TextSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    count: Int,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text       = title,
                color      = Color.White,
                fontSize   = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.weight(1f),
            )
            Text(
                text     = "$count",
                color    = TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column { content() }
        }
    }
}

@Composable
private fun RelaySetRow(
    set: NostrRelaySetEntity,
    viewModel: RelayManagementViewModel,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val members by viewModel.getSetMembers(set.dTag).collectAsStateWithLifecycle(initialValue = emptyList())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = Spacing.medium, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text     = set.title ?: set.dTag,
                color    = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text  = "${members.size} relays",
                color = TextSecondary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete set", tint = TextSecondary, modifier = Modifier.size(16.dp))
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = Spacing.medium)) {
                members.forEach { member ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(displayUrl(member.relayUrl), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { viewModel.removeRelayFromSet(set.dTag, member.relayUrl) },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = TextSecondary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                AddRelayInput(placeholder = "Add relay to set") { url ->
                    viewModel.addRelayToSet(set.dTag, url)
                }
            }
        }
    }
}
