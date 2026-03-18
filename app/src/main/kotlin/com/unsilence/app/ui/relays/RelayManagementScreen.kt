package com.unsilence.app.ui.relays

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.unsilence.app.data.db.entity.RelayConfigEntity
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private val TabLabels = listOf("Inbox/Outbox", "Index", "Search", "Relay Sets", "Favorites", "Blocked")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RelayManagementScreen(
    onDismiss: () -> Unit,
    onStartFeed: ((url: String, label: String) -> Unit)? = null,
    viewModel: RelayManagementViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onDismiss)

    val pagerState = rememberPagerState(pageCount = { 6 })
    val scope = rememberCoroutineScope()

    val readWriteRelays by viewModel.readWriteRelays.collectAsStateWithLifecycle(initialValue = emptyList())
    val indexerRelays   by viewModel.indexerRelays.collectAsStateWithLifecycle(initialValue = emptyList())
    val searchRelays    by viewModel.searchRelays.collectAsStateWithLifecycle(initialValue = emptyList())
    val blockedRelays   by viewModel.blockedRelays.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteRelays  by viewModel.favoriteRelays.collectAsStateWithLifecycle(initialValue = emptyList())
    val relaySets       by viewModel.relaySets.collectAsStateWithLifecycle(initialValue = emptyList())

    var showCreateRelaySet by remember { mutableStateOf(false) }

    if (showCreateRelaySet) {
        CreateRelaySetScreen(
            onDismiss = { showCreateRelaySet = false },
            viewModel = viewModel,
        )
        return
    }

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

            // ── Scrollable tab row ───────────────────────────────────────────
            ScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Black,
                contentColor = Cyan,
                edgePadding = Spacing.medium,
                indicator = { tabPositions ->
                    if (pagerState.currentPage < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                            color = Cyan,
                        )
                    }
                },
                divider = { HorizontalDivider(color = Color(0xFF222222)) },
            ) {
                TabLabels.forEachIndexed { index, label ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = {
                            Text(
                                text = label,
                                color = if (pagerState.currentPage == index) Cyan else TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = if (pagerState.currentPage == index) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                    )
                }
            }

            // ── Pager ────────────────────────────────────────────────────────
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                when (page) {
                    // ── Tab 0: Inbox/Outbox ──────────────────────────────────
                    0 -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            AddRelayInput(placeholder = "wss://relay.example.com") { url ->
                                viewModel.addReadWriteRelay(url)
                            }
                        }
                        items(readWriteRelays, key = { it.id }) { relay ->
                            ReadWriteRelayRow(
                                relay          = relay,
                                onToggleMarker = { viewModel.toggleMarker(relay) },
                                onRemove       = { viewModel.removeReadWriteRelay(relay.relayUrl) },
                            )
                        }
                        item { Spacer(Modifier.height(Spacing.xl)) }
                    }

                    // ── Tab 1: Index ─────────────────────────────────────────
                    1 -> LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                        item { Spacer(Modifier.height(Spacing.xl)) }
                    }

                    // ── Tab 2: Search ────────────────────────────────────────
                    2 -> LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                        item { Spacer(Modifier.height(Spacing.xl)) }
                    }

                    // ── Tab 3: Relay Sets ────────────────────────────────────
                    3 -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            TextButton(
                                onClick = { showCreateRelaySet = true },
                                modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, tint = Cyan, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("New Relay Set", color = Cyan, fontSize = 14.sp)
                            }
                        }
                        items(relaySets, key = { it.dTag }) { set ->
                            RelaySetRow(
                                set       = set,
                                viewModel = viewModel,
                                onDelete  = { viewModel.deleteRelaySet(set.dTag) },
                            )
                        }
                        item { Spacer(Modifier.height(Spacing.xl)) }
                    }

                    // ── Tab 4: Favorites ─────────────────────────────────────
                    4 -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            AddRelayInput(placeholder = "wss://favorite.example.com") { url ->
                                viewModel.addFavoriteRelay(url)
                            }
                        }
                        // Individual relay favorites
                        val relayFavorites = favoriteRelays.filter { it.setRef == null }
                        items(relayFavorites, key = { it.id }) { relay ->
                            FavoriteRelayRow(
                                url        = relay.relayUrl,
                                relaySets  = relaySets,
                                onRemove   = { viewModel.removeFavoriteRelay(relay.relayUrl) },
                                onAddToSet = { dTag -> viewModel.addRelayToSet(dTag, relay.relayUrl) },
                                onStartFeed = onStartFeed?.let { cb ->
                                    { cb(relay.relayUrl, displayUrl(relay.relayUrl)) }
                                },
                            )
                        }
                        // Set-reference favorites
                        val setRefs = favoriteRelays.mapNotNull { it.setRef }.distinct()
                        items(setRefs, key = { "setref_$it" }) { ref ->
                            SimpleRelayRow(
                                url      = ref,
                                onRemove = { viewModel.removeFavoriteSetRef(ref) },
                            )
                        }
                        // Set picker
                        item {
                            FavoriteSetPicker(
                                ownerPubkey    = viewModel.ownerPubkey,
                                relaySets      = relaySets,
                                favoriteRelays = favoriteRelays,
                                onAddSetRef    = { viewModel.addFavoriteSetRef(it) },
                            )
                        }
                        item { Spacer(Modifier.height(Spacing.xl)) }
                    }

                    // ── Tab 5: Blocked ───────────────────────────────────────
                    5 -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            AddRelayInput(placeholder = "wss://blocked.example.com") { url ->
                                viewModel.addBlockedRelay(url)
                            }
                        }
                        items(blockedRelays, key = { it.id }) { relay ->
                            SimpleRelayRow(
                                url      = relay.relayUrl,
                                onRemove = { viewModel.removeBlockedRelay(relay.relayUrl) },
                            )
                        }
                        item { Spacer(Modifier.height(Spacing.xl)) }
                    }
                }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteRelayRow(
    url: String,
    relaySets: List<NostrRelaySetEntity>,
    onRemove: () -> Unit,
    onAddToSet: (dTag: String) -> Unit,
    onStartFeed: (() -> Unit)?,
) {
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { showMenu = true })
            .padding(horizontal = Spacing.medium, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = displayUrl(url),
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onStartFeed != null) {
            IconButton(onClick = onStartFeed, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "Add to Feed", tint = Cyan, modifier = Modifier.size(18.dp))
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = TextSecondary, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            if (relaySets.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Add to Set", color = Color.White, fontSize = 14.sp) },
                    onClick = {},
                    enabled = false,
                )
                relaySets.forEach { set ->
                    DropdownMenuItem(
                        text = { Text("  ${set.title ?: set.dTag}", color = TextSecondary, fontSize = 13.sp) },
                        onClick = { showMenu = false; onAddToSet(set.dTag) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteSetPicker(
    ownerPubkey: String?,
    relaySets: List<NostrRelaySetEntity>,
    favoriteRelays: List<RelayConfigEntity>,
    onAddSetRef: (String) -> Unit,
) {
    val pk = ownerPubkey ?: return
    if (relaySets.isEmpty()) return
    val unfavorited = relaySets.filter { set ->
        val ref = "30002:$pk:${set.dTag}"
        favoriteRelays.none { it.setRef == ref }
    }
    if (unfavorited.isEmpty()) return
    Text(
        text = "Add relay set to favorites",
        color = TextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = Spacing.medium, top = 8.dp, bottom = 4.dp),
    )
    unfavorited.forEach { set ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAddSetRef("30002:$pk:${set.dTag}") }
                .padding(horizontal = Spacing.medium, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add", tint = Cyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text = set.title ?: set.dTag, color = Color.White, fontSize = 14.sp)
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
