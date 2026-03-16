package com.unsilence.app.ui.relays

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.unsilence.app.data.db.entity.RelayConfigEntity
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

    val readWrite by viewModel.readWriteRelays.collectAsStateWithLifecycle(initialValue = emptyList())
    val blocked by viewModel.blockedRelays.collectAsStateWithLifecycle(initialValue = emptyList())
    val search by viewModel.searchRelays.collectAsStateWithLifecycle(initialValue = emptyList())
    val favorites by viewModel.favoriteRelays.collectAsStateWithLifecycle(initialValue = emptyList())
    val relaySets by viewModel.relaySets.collectAsStateWithLifecycle(initialValue = emptyList())

    var rwExpanded by rememberSaveable { mutableStateOf(true) }
    var searchExpanded by rememberSaveable { mutableStateOf(true) }
    var blockedExpanded by rememberSaveable { mutableStateOf(true) }
    var setsExpanded by rememberSaveable { mutableStateOf(true) }
    var favoritesExpanded by rememberSaveable { mutableStateOf(true) }

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

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // ── 1. READ/WRITE (kind 10002) ──────────────────────────────
                item {
                    RelaySection(
                        title = "Read / Write",
                        count = readWrite.size,
                        expanded = rwExpanded,
                        onToggle = { rwExpanded = !rwExpanded },
                    )
                    AnimatedVisibility(
                        visible = rwExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Column {
                            readWrite.forEach { relay ->
                                ReadWriteRelayRow(
                                    relay = relay,
                                    onToggleMarker = { viewModel.toggleMarker(relay) },
                                    onDelete = { viewModel.removeReadWriteRelay(relay.relayUrl) },
                                )
                            }
                            AddRelayInput(
                                placeholder = "wss://relay.example.com",
                                onAdd = { viewModel.addReadWriteRelay(it) },
                            )
                        }
                    }
                    SectionDivider()
                }

                // ── 2. SEARCH (kind 10007) ──────────────────────────────────
                item {
                    RelaySection(
                        title = "Search",
                        count = search.size,
                        expanded = searchExpanded,
                        onToggle = { searchExpanded = !searchExpanded },
                    )
                    AnimatedVisibility(
                        visible = searchExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Column {
                            search.forEach { relay ->
                                SimpleRelayRow(
                                    url = relay.relayUrl,
                                    onDelete = { viewModel.removeSearchRelay(relay.relayUrl) },
                                )
                            }
                            AddRelayInput(
                                placeholder = "wss://search.relay.com",
                                onAdd = { viewModel.addSearchRelay(it) },
                            )
                        }
                    }
                    SectionDivider()
                }

                // ── 3. BLOCKED (kind 10006) ─────────────────────────────────
                item {
                    RelaySection(
                        title = "Blocked",
                        count = blocked.size,
                        expanded = blockedExpanded,
                        onToggle = { blockedExpanded = !blockedExpanded },
                    )
                    AnimatedVisibility(
                        visible = blockedExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Column {
                            blocked.forEach { relay ->
                                SimpleRelayRow(
                                    url = relay.relayUrl,
                                    onDelete = { viewModel.removeBlockedRelay(relay.relayUrl) },
                                )
                            }
                            AddRelayInput(
                                placeholder = "wss://spam.relay.com",
                                onAdd = { viewModel.addBlockedRelay(it) },
                            )
                        }
                    }
                    SectionDivider()
                }

                // ── 4. RELAY SETS (kind 30002) ──────────────────────────────
                item {
                    RelaySection(
                        title = "Relay Sets",
                        count = relaySets.size,
                        expanded = setsExpanded,
                        onToggle = { setsExpanded = !setsExpanded },
                    )
                    AnimatedVisibility(
                        visible = setsExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Column {
                            relaySets.forEach { set ->
                                ExpandableRelaySetRow(
                                    set = set,
                                    viewModel = viewModel,
                                    onDelete = { viewModel.deleteRelaySet(set.dTag) },
                                )
                            }
                        }
                    }
                    SectionDivider()
                }

                // ── 5. FAVORITES (kind 10012) ───────────────────────────────
                item {
                    RelaySection(
                        title = "Favorites",
                        count = favorites.size,
                        expanded = favoritesExpanded,
                        onToggle = { favoritesExpanded = !favoritesExpanded },
                    )
                    AnimatedVisibility(
                        visible = favoritesExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Column {
                            favorites.forEach { relay ->
                                SimpleRelayRow(
                                    url = relay.setRef ?: relay.relayUrl,
                                    onDelete = {
                                        val ref = relay.setRef
                                        if (ref != null) {
                                            viewModel.removeFavoriteSetRef(ref)
                                        } else {
                                            viewModel.removeFavoriteRelay(relay.relayUrl)
                                        }
                                    },
                                )
                            }
                            AddRelayInput(
                                placeholder = "wss://favorite.relay.com",
                                onAdd = { viewModel.addFavoriteRelay(it) },
                            )
                            // Relay set picker — add existing sets as favorites
                            val pk = viewModel.ownerPubkey
                            if (pk != null && relaySets.isNotEmpty()) {
                                val unfavorited = relaySets.filter { set ->
                                    val ref = "30002:$pk:${set.dTag}"
                                    favorites.none { it.setRef == ref }
                                }
                                if (unfavorited.isNotEmpty()) {
                                    Text(
                                        text = "Add relay set to favorites",
                                        color = TextSecondary,
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
                                                text = set.title ?: set.dTag,
                                                color = Color.White,
                                                fontSize = 14.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
private fun RelaySection(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.medium, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = Cyan,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count",
            color = TextSecondary,
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = TextSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ReadWriteRelayRow(
    relay: RelayConfigEntity,
    onToggleMarker: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = relay.relayUrl.removePrefix("wss://"),
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
            )
            Row {
                val isRead = relay.marker == null || relay.marker == "read"
                val isWrite = relay.marker == null || relay.marker == "write"
                MarkerChip(label = "Read", active = isRead, onClick = onToggleMarker)
                Spacer(Modifier.width(6.dp))
                MarkerChip(label = "Write", active = isWrite, onClick = onToggleMarker)
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Delete, "Remove", tint = Color(0xFFCF6679), modifier = Modifier.size(18.dp))
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)
}

@Composable
private fun SimpleRelayRow(url: String, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = url.removePrefix("wss://"),
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Delete, "Remove", tint = Color(0xFFCF6679), modifier = Modifier.size(18.dp))
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)
}

@Composable
private fun ExpandableRelaySetRow(
    set: com.unsilence.app.data.db.entity.NostrRelaySetEntity,
    viewModel: RelayManagementViewModel,
    onDelete: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = Spacing.medium, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = set.title ?: set.dTag,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (set.description != null) {
                    Text(
                        text = set.description,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, "Remove", tint = Color(0xFFCF6679), modifier = Modifier.size(18.dp))
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            val members by viewModel.getSetMembers(set.dTag)
                .collectAsState(initial = emptyList())
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp),
            ) {
                for (member in members) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = Spacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = member.relayUrl.removePrefix("wss://"),
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        IconButton(
                            onClick = { viewModel.removeRelayFromSet(set.dTag, member.relayUrl) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Filled.Delete, "Remove", tint = Color(0xFFCF6679), modifier = Modifier.size(14.dp))
                        }
                    }
                }
                AddRelayInput(
                    placeholder = "wss://relay.example.com",
                    onAdd = { viewModel.addRelayToSet(set.dTag, it) },
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)
    }
}

@Composable
private fun AddRelayInput(placeholder: String, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush = SolidColor(Cyan),
            singleLine = true,
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text(placeholder, color = TextSecondary, fontSize = 14.sp)
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.width(Spacing.small))
        IconButton(
            onClick = {
                if (text.isNotBlank()) {
                    onAdd(text)
                    text = ""
                }
            },
        ) {
            Icon(Icons.Filled.Add, "Add relay", tint = Cyan)
        }
    }
}

@Composable
private fun MarkerChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (active) Cyan else TextSecondary,
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

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = Cyan.copy(alpha = 0.2f), thickness = 1.dp)
    Spacer(Modifier.height(4.dp))
}
