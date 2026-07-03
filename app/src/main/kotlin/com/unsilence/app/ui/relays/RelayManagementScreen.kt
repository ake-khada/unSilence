package com.unsilence.app.ui.relays

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.memory.RelayHealthInfo
import com.unsilence.app.data.memory.FavoriteEntry
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.memory.RelayConfig
import com.unsilence.app.data.memory.RelaySet
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.BorderDefault
import com.unsilence.app.ui.theme.BorderFaint
import com.unsilence.app.ui.theme.BorderSubtle
import com.unsilence.app.ui.theme.BrandSoft
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Text4
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class RelayCategory(val label: String, val icon: ImageVector, val description: String)

// §02: keep all six categories; labels + copy verbatim from the doc.
private val RelayCategories = listOf(
    RelayCategory("My relays", Icons.Filled.Dns, "where your notes are published & read"),
    RelayCategory("Index", Icons.Filled.PersonSearch, "where unSilence looks up profiles & follow lists"),
    RelayCategory("Search", Icons.Filled.Search, "relays used for full-text search"),
    RelayCategory("Sets", Icons.Filled.Layers, "your saved relay groupings"),
    RelayCategory("Favorites", Icons.Filled.Star, "relays you've starred to reuse"),
    RelayCategory("Blocked", Icons.Filled.Block, "relays unSilence will never connect to"),
)

/** §02 category rail — scrollable pills with leading icons, filled active pill, a
 *  right-edge fade (kills the cut-off "Blo…"), and the active category's description +
 *  count beneath. Replaces the ScrollableTabRow; drives the existing pager. */
@Composable
private fun RelayCategoryRail(
    selectedIndex: Int,
    counts: List<Int>,
    onSelect: (Int) -> Unit,
) {
    Column {
        val listState = rememberLazyListState()
        // Follow the pager — bring the active pill into view (so the last one, Blocked, doesn't
        // stay out of bounds when you swipe to it).
        LaunchedEffect(selectedIndex) { listState.animateScrollToItem(selectedIndex) }
        Box {
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            ) {
                itemsIndexed(RelayCategories) { i, cat ->
                    val active = i == selectedIndex
                    val fg = if (active) Color(0xFF001012) else TextSecondary
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (active) Brand else Surface1)
                            .then(if (active) Modifier else Modifier.border(1.dp, BorderSubtle, RoundedCornerShape(999.dp)))
                            .clickable { onSelect(i) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(cat.icon, null, tint = fg, modifier = Modifier.size(13.dp))
                        Text(cat.label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
            // Right-edge fade hint — ONLY while there's more to scroll, so the last pill
            // (Blocked) is fully revealed at max scroll. matchParentSize (not fillMaxHeight)
            // keeps the row compact; background-only → never blocks taps.
            if (listState.canScrollForward) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Brush.horizontalGradient(0.86f to Color.Transparent, 1f to Black)),
                )
            }
        }
        val cat = RelayCategories[selectedIndex.coerceIn(RelayCategories.indices)]
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = cat.description,
                color = TextSecondary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = counts.getOrElse(selectedIndex) { 0 }.toString(),
                color = Text3,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RelayManagementScreen(
    onDismiss: () -> Unit,
    onOpenDetail: (url: String) -> Unit = {},
    onOpenDiscovery: () -> Unit = {},
    viewModel: RelayManagementViewModel = hiltViewModel(
        key = "relay-management-${LocalAppSessionKey.current}",
    ),
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
    val relayHealth     by viewModel.relayHealth.collectAsStateWithLifecycle(initialValue = emptyMap())

    // NB: the relay-directory firehose is NO LONGER triggered here — it fires only when the user
    // opens §04 Discovery (the firehose costs nothing until someone asks to discover).

    var showCreateRelaySet by remember { mutableStateOf(false) }
    // Item 3: user-initiated "Test" results. value -1 = tested & offline; absent = not tested
    // (falls back to monitor RTT). No background pinger, no persistence (amendment a).
    val testedRtt = remember { mutableStateMapOf<String, Int>() }
    var testing by remember { mutableStateOf(false) }

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
                        .padding(start = Spacing.medium, end = Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text       = "Relay Settings",
                        color      = Color.White,
                        fontSize   = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier   = Modifier.weight(1f),
                    )
                    // §04 Discovery entry — find relays you don't already know the URL of.
                    IconButton(onClick = onOpenDiscovery) {
                        Icon(Icons.Filled.Search, contentDescription = "Discover relays", tint = Brand, modifier = Modifier.size(22.dp))
                    }
                }
            }

            // ── Category rail (pills) + active-category description + count ──
            val categoryCounts = listOf(
                readWriteRelays.size,
                indexerRelays.size,
                searchRelays.size,
                relaySets.size,
                favoriteRelays.count { it.setRef == null && it.url != null },
                blockedRelays.size,
            )
            RelayCategoryRail(
                selectedIndex = pagerState.currentPage,
                counts = categoryCounts,
                onSelect = { scope.launch { pagerState.animateScrollToPage(it) } },
            )

            // ── Health summary bar (active relay-list category; not Sets) ────
            val activeRelayUrls = when (pagerState.currentPage) {
                0 -> readWriteRelays.map { it.url }
                1 -> indexerRelays
                2 -> searchRelays
                4 -> favoriteRelays.filter { it.setRef == null && it.url != null }.mapNotNull { it.url }
                5 -> blockedRelays
                else -> emptyList()
            }
            if (activeRelayUrls.isNotEmpty()) {
                RelayHealthSummary(
                    urls      = activeRelayUrls,
                    latencyOf = { url -> testedRtt[url] ?: relayHealth.lookup(url)?.ping },
                    testing   = testing,
                    onTest    = {
                        scope.launch {
                            testing = true
                            activeRelayUrls.forEach { url -> testedRtt[url] = viewModel.measureRtt(url) ?: -1 }
                            testing = false
                        }
                    },
                )
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
                        if (readWriteRelays.isEmpty()) item { DiscoverEmptyCta(onOpenDiscovery) }
                        items(readWriteRelays, key = { it.url }) { relay ->
                            ReadWriteRelayRow(
                                relay          = relay,
                                health         = relayHealth.lookup(relay.url),
                                testedMs       = testedRtt[relay.url],
                                onSetMarker    = { m -> viewModel.setRelayMarker(relay, m) },
                                onRemove       = { viewModel.removeReadWriteRelay(relay.url) },
                                onOpenDetail   = { onOpenDetail(relay.url) },
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
                        items(indexerRelays, key = { it }) { url ->
                            SimpleRelayRow(
                                url        = url,
                                onRemove   = { viewModel.removeIndexerRelay(url) },
                                health     = relayHealth.lookup(url),
                                testedMs   = testedRtt[url],
                                onOpenDetail = { onOpenDetail(url) },
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
                        if (searchRelays.isEmpty()) item { DiscoverEmptyCta(onOpenDiscovery) }
                        items(searchRelays, key = { it }) { url ->
                            SimpleRelayRow(
                                url        = url,
                                onRemove   = { viewModel.removeSearchRelay(url) },
                                health     = relayHealth.lookup(url),
                                testedMs   = testedRtt[url],
                                onOpenDetail = { onOpenDetail(url) },
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
                                Icon(Icons.Filled.Add, contentDescription = null, tint = Brand, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("New Relay Set", color = Brand, fontSize = 14.sp)
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
                        val relayFavorites = favoriteRelays.filter { it.setRef == null && it.url != null }
                        if (relayFavorites.isEmpty()) item { DiscoverEmptyCta(onOpenDiscovery) }
                        items(relayFavorites, key = { it.url!! }) { fav ->
                            FavoriteRelayRow(
                                url        = fav.url!!,
                                relaySets  = relaySets,
                                health     = fav.url?.let { relayHealth.lookup(it) },
                                testedMs   = fav.url?.let { testedRtt[it] },
                                onRemove   = { viewModel.removeFavoriteRelay(fav.url!!) },
                                onAddToSet = { dTag -> viewModel.addRelayToSet(dTag, fav.url!!) },
                                onOpenDetail = { onOpenDetail(fav.url!!) },
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
                        items(blockedRelays, key = { it }) { url ->
                            SimpleRelayRow(
                                url        = url,
                                onRemove   = { viewModel.removeBlockedRelay(url) },
                                health     = relayHealth.lookup(url),
                                testedMs   = testedRtt[url],
                                onOpenDetail = { onOpenDetail(url) },
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
    val hint = placeholder.substringAfter("://", placeholder)   // wss:// is a fixed prefix now
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Surface1)
            .border(1.dp, BorderSubtle, RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("wss://", color = Text4, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        BasicTextField(
            value         = input,
            onValueChange = { input = it },
            textStyle     = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
            cursorBrush   = SolidColor(Brand),
            singleLine    = true,
            decorationBox = { inner ->
                // CenterStart + matching font/size = cursor sits on the placeholder baseline.
                Box(contentAlignment = Alignment.CenterStart) {
                    if (input.isEmpty()) {
                        Text(hint, color = Text3, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(BrandSoft)
                .clickable {
                    if (input.isNotBlank()) { onAdd(input.trim()); input = "" }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add", tint = Brand, modifier = Modifier.size(14.dp))
        }
    }
}

/** Display URL without wss:// prefix for compactness. */
private fun displayUrl(url: String): String =
    url.removePrefix("wss://").removePrefix("ws://")

/** §03 safe removal — swipe left reveals a deliberate red "Remove", then a confirm dialog.
 *  Replaces the one-tap trash (no more accidental deletes). Removal publishes kind-10002/6
 *  through the caller's existing repository path (onRemove). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToRemove(
    onRemove: () -> Unit,
    label: String,
    title: String = "Remove relay?",
    content: @Composable () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            // Don't auto-dismiss — reveal + confirm. Returning false snaps the row back.
            if (v == SwipeToDismissBoxValue.EndToStart) confirm = true
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Like),
            ) {
                // Icon + label side by side at the right edge, vertically centered.
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.Delete, null, tint = Black, modifier = Modifier.size(15.dp))
                    Text("Remove", color = Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        content = { Box(modifier = Modifier.background(Black)) { content() } },
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            confirmButton = { TextButton(onClick = { confirm = false; onRemove() }) { Text("Remove", color = Like) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = TextSecondary) } },
            title = { Text(title, color = Color.White) },
            text = { Text(label, color = TextSecondary) },
            containerColor = Surface1,
        )
    }
}

/** §03 R / W toggle pill — filled cyan = enabled, ghost = disabled. Shared with the §05
 *  detail footer's R/W editor. */
@Composable
internal fun RwPill(label: String, on: Boolean, onClick: () -> Unit) {
    // 22dp visual pill kept compact; square 36dp tap area (wider than the glyph
    // but not so tall it dominates the row).
    com.unsilence.app.ui.common.TouchTarget(onClick = onClick, minSize = 36.dp) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (on) BrandSoft else Color.Transparent)
                .border(1.dp, if (on) Brand.copy(alpha = 0.4f) else BorderDefault, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = if (on) Brand else Text3, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

/** §04 empty-state CTA — shown when a relay category is empty, routes to Discovery. */
@Composable
private fun DiscoverEmptyCta(onOpenDiscovery: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small)
            .clip(RoundedCornerShape(12.dp))
            .background(BrandSoft)
            .clickable(onClick = onOpenDiscovery)
            .padding(Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Brand, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.small))
        Column(modifier = Modifier.weight(1f)) {
            Text("Discover relays", color = Brand, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Find relays by name, topic, or who you follow", color = TextSecondary, fontSize = 12.sp)
        }
    }
}

/** Look up health info, trying both raw and normalized URL forms. */
private fun Map<String, RelayHealthInfo>.lookup(url: String): RelayHealthInfo? =
    this[url] ?: normalizeRelayUrl(url)?.let { this[it] }

@Composable
private fun SimpleRelayRow(
    url: String,
    onRemove: () -> Unit,
    health: RelayHealthInfo? = null,
    testedMs: Int? = null,
    onOpenDetail: () -> Unit = {},
) {
    SwipeToRemove(onRemove = onRemove, label = displayUrl(url)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenDetail)
                .padding(horizontal = Spacing.medium, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HealthDot(testedMs ?: health?.ping)
            Spacer(Modifier.width(8.dp))
            Text(
                text     = displayUrl(url),
                color    = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PingLabel(testedMs ?: health?.ping)
            RowChevron()
        }
    }
}

@Composable
private fun ReadWriteRelayRow(
    relay: RelayConfig,
    health: RelayHealthInfo?,
    testedMs: Int? = null,
    onSetMarker: (String?) -> Unit,
    onRemove: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    // marker: null = read+write · "read" = read-only · "write" = write-only.
    val readOn = relay.marker != "write"
    val writeOn = relay.marker != "read"
    SwipeToRemove(onRemove = onRemove, label = displayUrl(relay.url)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenDetail)
                .padding(horizontal = Spacing.medium, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HealthDot(testedMs ?: health?.ping)
            Spacer(Modifier.width(8.dp))
            Text(
                text     = displayUrl(relay.url),
                color    = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PingLabel(testedMs ?: health?.ping)
            Spacer(Modifier.width(8.dp))
            // Two INDEPENDENT toggles. Can't disable both (a relay must read or write) —
            // such a tap is a no-op; removal is via swipe.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RwPill("R", readOn) { val n = !readOn; if (n || writeOn) onSetMarker(rwMarker(n, writeOn)) }
                RwPill("W", writeOn) { val n = !writeOn; if (readOn || n) onSetMarker(rwMarker(readOn, n)) }
            }
            RowChevron()
        }
    }
}

private fun rwMarker(read: Boolean, write: Boolean): String? = when {
    read && write -> null
    read -> "read"
    else -> "write"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteRelayRow(
    url: String,
    relaySets: List<RelaySet>,
    health: RelayHealthInfo? = null,
    testedMs: Int? = null,
    onRemove: () -> Unit,
    onAddToSet: (dTag: String) -> Unit,
    onOpenDetail: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    SwipeToRemove(onRemove = onRemove, label = displayUrl(url)) {
      Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpenDetail, onLongClick = { showMenu = true })
            .padding(horizontal = Spacing.medium, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HealthDot(testedMs ?: health?.ping)
        Spacer(Modifier.width(8.dp))
        Text(
            text = displayUrl(url),
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        PingLabel(testedMs ?: health?.ping)
        RowChevron()
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
}

@Composable
private fun RelaySetRow(
    set: RelaySet,
    viewModel: RelayManagementViewModel,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val members by viewModel.getSetMembers(set.dTag).collectAsStateWithLifecycle(initialValue = emptyList())

    Column(modifier = Modifier.fillMaxWidth()) {
        // Swipe applies to the HEADER only — so the expanded members/add-field below stay
        // interactive without triggering a whole-set delete.
        SwipeToRemove(onRemove = onDelete, label = set.title ?: set.dTag, title = "Delete set?") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = Spacing.medium, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = Spacing.medium, end = Spacing.medium, bottom = Spacing.small)) {
                members.forEach { member ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(displayUrl(member), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { viewModel.removeRelayFromSet(set.dTag, member) },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = TextSecondary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                AddRelayInput(placeholder = "wss://relay.example.com") { url ->
                    viewModel.addRelayToSet(set.dTag, url)
                }
            }
        }
    }
}

/** §02/§03 health summary bar — "N healthy · N slow · N off" over the active category, with
 *  a user-initiated "Test" action (re-pings the category; no background pinger). */
@Composable
private fun RelayHealthSummary(
    urls: List<String>,
    latencyOf: (String) -> Int?,
    testing: Boolean,
    onTest: () -> Unit,
) {
    var healthy = 0; var slowYellow = 0; var slowRed = 0; var off = 0
    urls.forEach { url ->
        when (val ms = latencyOf(url)) {
            null -> {}                       // unknown — not counted
            in 0..149 -> healthy++
            in 150..500 -> slowYellow++
            else -> if (ms < 0) off++ else slowRed++   // -1 = offline; >500 = very-slow (red), NOT off
        }
    }
    val slow = slowYellow + slowRed
    // "off" is for OFF; >500 is slow-but-online. Slow segment takes the dominant sub-tier's color.
    val slowColor = if (slowRed > slowYellow) Like else Zap
    Row(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(Surface1)
            .border(1.dp, BorderFaint, RoundedCornerShape(11.dp))
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummarySeg(Mint, "$healthy healthy")
        SummarySeg(slowColor, "$slow slow")
        SummarySeg(Text4, "$off off")
        Spacer(Modifier.weight(1f))
        Text(
            text = if (testing) "Testing…" else "Test",
            color = Brand,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = !testing, onClick = onTest)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun SummarySeg(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(modifier = Modifier.size(7.dp)) { drawCircle(color = color) }
        Text(text, color = TextSecondary, fontSize = 11.sp)
    }
}

// ── Relay health components ─────────────────────────────────────────────────

private val HealthGray   = Text3

// §02/§03 latency tiers: mint <150 (fast) · zap 150–500 (slow) · like >500 or offline.
// ms == null → no data (neutral "—"); ms < 0 → tested-and-offline. Trust score is NO LONGER
// a list signal (amendment b) — the dot is the ONE coherent latency signal. Shared with the
// §05 detail page (RelayDetailScreen) for the verdict row + stats strip.
internal fun latencyTier(ms: Int?): Color = when {
    ms == null -> HealthGray
    ms < 0     -> Like
    ms < 150   -> Mint
    ms <= 500  -> Zap
    else       -> Like
}

private fun latencyLabel(ms: Int?): String = when {
    ms == null -> "—"
    ms < 0     -> "off"
    ms < 150   -> "fast · ${ms}ms"
    else       -> "slow · ${ms}ms"
}

@Composable
private fun HealthDot(latencyMs: Int?) {
    Canvas(modifier = Modifier.size(10.dp)) {
        drawCircle(color = latencyTier(latencyMs))
    }
}

/** Disclosure chevron — the row opens the §05 relay detail page. Replaces the old ⓘ
 *  (which opened the now-removed health sheet); a trailing chevron is the conventional
 *  "tap to open detail" affordance, so the rows read as navigable. */
@Composable
private fun RowChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = TextSecondary,
        modifier = Modifier.padding(start = 2.dp).size(18.dp),
    )
}

/** Fixed-column latency: [qualifier 28dp] [· ] [ms 42dp] so they line up straight down the
 *  list (no jitter from ping length) and a 4-digit ms fits before the R/W pills. */
@Composable
private fun PingLabel(latencyMs: Int?) {
    val color = latencyTier(latencyMs)
    val qualifier = when {
        latencyMs == null -> "—"
        latencyMs < 0     -> "off"
        latencyMs < 150   -> "fast"
        else              -> "slow"
    }
    val pingStr = if (latencyMs != null && latencyMs >= 0) "${latencyMs}ms" else ""
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Text(qualifier, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.width(28.dp))
        Text(if (pingStr.isEmpty()) "   " else " · ", color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        Text(pingStr, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.width(42.dp))
    }
}
