package com.unsilence.app.ui.relays

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.runtime.LaunchedEffect
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
import com.unsilence.app.data.memory.RelayHealthInfo
import com.unsilence.app.data.memory.FavoriteEntry
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.memory.RelayConfig
import com.unsilence.app.data.memory.RelaySet
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay
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
    val relayHealth     by viewModel.relayHealth.collectAsStateWithLifecycle(initialValue = emptyMap())

    var showCreateRelaySet by remember { mutableStateOf(false) }
    var healthDetailRelay by remember { mutableStateOf<RelayHealthInfo?>(null) }

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
                contentColor = Brand,
                edgePadding = Spacing.medium,
                indicator = { tabPositions ->
                    if (pagerState.currentPage < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                            color = Brand,
                        )
                    }
                },
                divider = { HorizontalDivider(color = Surface2) },
            ) {
                TabLabels.forEachIndexed { index, label ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = {
                            Text(
                                text = label,
                                color = if (pagerState.currentPage == index) Brand else TextSecondary,
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
                        items(readWriteRelays, key = { it.url }) { relay ->
                            ReadWriteRelayRow(
                                relay          = relay,
                                health         = relayHealth.lookup(relay.url),
                                onToggleMarker = { viewModel.toggleMarker(relay) },
                                onRemove       = { viewModel.removeReadWriteRelay(relay.url) },
                                onHealthTap    = { relayHealth.lookup(relay.url)?.let { healthDetailRelay = it } },
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
                                onHealthTap = { relayHealth.lookup(url)?.let { healthDetailRelay = it } },
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
                        items(searchRelays, key = { it }) { url ->
                            SimpleRelayRow(
                                url        = url,
                                onRemove   = { viewModel.removeSearchRelay(url) },
                                health     = relayHealth.lookup(url),
                                onHealthTap = { relayHealth.lookup(url)?.let { healthDetailRelay = it } },
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
                        items(relayFavorites, key = { it.url!! }) { fav ->
                            FavoriteRelayRow(
                                url        = fav.url!!,
                                relaySets  = relaySets,
                                health     = fav.url?.let { relayHealth.lookup(it) },
                                onRemove   = { viewModel.removeFavoriteRelay(fav.url!!) },
                                onAddToSet = { dTag -> viewModel.addRelayToSet(dTag, fav.url!!) },
                                onHealthTap = { fav.url?.let { relayHealth.lookup(it) }?.let { healthDetailRelay = it } },
                                onStartFeed = onStartFeed?.let { cb ->
                                    { cb(fav.url!!, displayUrl(fav.url!!)) }
                                },
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
                                onHealthTap = { relayHealth.lookup(url)?.let { healthDetailRelay = it } },
                            )
                        }
                        item { Spacer(Modifier.height(Spacing.xl)) }
                    }
                }
            }
        }
    }

    // Relay health detail bottom sheet
    healthDetailRelay?.let { health ->
        RelayHealthDetailSheet(
            health = health,
            onDismiss = { healthDetailRelay = null },
        )
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
            cursorBrush   = SolidColor(Brand),
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
            Icon(Icons.Filled.Add, contentDescription = "Add", tint = Brand)
        }
    }
}

/** Display URL without wss:// prefix for compactness. */
private fun displayUrl(url: String): String =
    url.removePrefix("wss://").removePrefix("ws://")

/** Look up health info, trying both raw and normalized URL forms. */
private fun Map<String, RelayHealthInfo>.lookup(url: String): RelayHealthInfo? =
    this[url] ?: normalizeRelayUrl(url)?.let { this[it] }

@Composable
private fun SimpleRelayRow(
    url: String,
    onRemove: () -> Unit,
    health: RelayHealthInfo? = null,
    onHealthTap: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HealthDot(health, onClick = onHealthTap)
        Spacer(Modifier.width(8.dp))
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text     = displayUrl(url),
                color    = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HealthInfoButton(health, onClick = onHealthTap)
        }
        PingLabel(health)
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = TextSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ReadWriteRelayRow(
    relay: RelayConfig,
    health: RelayHealthInfo?,
    onToggleMarker: () -> Unit,
    onRemove: () -> Unit,
    onHealthTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HealthDot(health, onClick = onHealthTap)
        Spacer(Modifier.width(8.dp))
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text     = displayUrl(relay.url),
                color    = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HealthInfoButton(health, onClick = onHealthTap)
        }
        PingLabel(health)
        // R/W marker chip
        val markerLabel = when (relay.marker) {
            "read"  -> "R"
            "write" -> "W"
            else    -> "R/W"
        }
        Text(
            text     = markerLabel,
            color    = Brand,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Brand.copy(alpha = 0.15f))
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
    relaySets: List<RelaySet>,
    health: RelayHealthInfo? = null,
    onRemove: () -> Unit,
    onAddToSet: (dTag: String) -> Unit,
    onHealthTap: () -> Unit = {},
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
        HealthDot(health, onClick = onHealthTap)
        Spacer(Modifier.width(8.dp))
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = displayUrl(url),
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HealthInfoButton(health, onClick = onHealthTap)
        }
        PingLabel(health)
        if (onStartFeed != null) {
            var justAdded by remember { mutableStateOf(false) }
            LaunchedEffect(justAdded) {
                if (justAdded) {
                    delay(1500)
                    justAdded = false
                }
            }
            IconButton(
                onClick = {
                    onStartFeed()
                    justAdded = true
                },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    if (justAdded) Icons.Filled.Check else Icons.Filled.Add,
                    contentDescription = "Add to Feed",
                    tint = if (justAdded) Mint else Brand,
                    modifier = Modifier.size(18.dp),
                )
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
private fun RelaySetRow(
    set: RelaySet,
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
                        Text(displayUrl(member), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { viewModel.removeRelayFromSet(set.dTag, member) },
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

// ── Relay health components ─────────────────────────────────────────────────

private val HealthGreen  = Mint
private val HealthYellow = Color(0xFFFFC107)
private val HealthRed    = Color(0xFFFF5252)
private val HealthGray   = Text3

private fun trustColor(score: Int?): Color = when {
    score == null -> HealthGray
    score >= 70   -> HealthGreen
    score >= 40   -> HealthYellow
    else          -> HealthRed
}

private fun pingColor(ms: Int): Color = when {
    ms <= 200  -> HealthGreen
    ms <= 500  -> HealthYellow
    else       -> HealthRed
}

/** Dot color: trust score if available, otherwise ping-based, otherwise grey. */
private fun healthDotColor(health: RelayHealthInfo?): Color {
    if (health == null) return HealthGray
    health.score?.let { return trustColor(it) }
    health.ping?.let { return pingColor(it) }
    return HealthGray
}

@Composable
private fun HealthDot(health: RelayHealthInfo?, onClick: () -> Unit) {
    val color = healthDotColor(health)
    Canvas(modifier = Modifier.size(10.dp)) {
        drawCircle(color = color)
    }
}

@Composable
private fun HealthInfoButton(health: RelayHealthInfo?, onClick: () -> Unit) {
    if (health == null) return
    IconButton(onClick = onClick, modifier = Modifier.size(24.dp)) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = "Relay info",
            tint = TextSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun PingLabel(health: RelayHealthInfo?) {
    val ping = health?.ping ?: return
    Text(
        text = "${ping}ms",
        color = pingColor(ping),
        fontSize = 10.sp,
        modifier = Modifier.padding(end = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelayHealthDetailSheet(
    health: RelayHealthInfo,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1A1A1A),
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.medium)
                .padding(bottom = 32.dp),
        ) {
            // Header
            Text(
                text = displayUrl(health.relayUrl),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )

            // ── Trust score section ─────────────────────────────────────
            val score = health.trustScore
            if (score != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(modifier = Modifier.size(10.dp)) {
                        drawCircle(color = trustColor(score.score))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Trust Score: ${score.score}/100",
                        color = trustColor(score.score),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = score.confidence.replaceFirstChar { it.uppercase() } + " confidence",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }

                Spacer(Modifier.height(16.dp))

                TrustBar("Reliability", score.reliability, 0.40f)
                TrustBar("Quality", score.quality, 0.35f)
                TrustBar("Accessibility", score.accessibility, 0.25f)

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    score.policy?.let { policy ->
                        MetadataChip(label = "Policy", value = policy)
                    }
                    score.countryCode?.let { cc ->
                        MetadataChip(label = "Region", value = cc)
                    }
                    MetadataChip(label = "Observations", value = score.observations.toString())
                }

                score.operatorVerified?.let { method ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Operator verified: $method",
                        color = HealthGreen,
                        fontSize = 12.sp,
                    )
                }
            }

            // ── Monitor / liveness section ──────────────────────────────
            val monitor = health.monitor
            if (monitor != null) {
                if (score != null) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = Surface2)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Liveness Monitor",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    monitor.rttOpen?.let { MetadataChip(label = "Open", value = "${it}ms") }
                    monitor.rttRead?.let { MetadataChip(label = "Read", value = "${it}ms") }
                    monitor.rttWrite?.let { MetadataChip(label = "Write", value = "${it}ms") }
                }

                if (monitor.supportedNips.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "NIPs: ${monitor.supportedNips.sorted().joinToString(", ")}",
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                }

                monitor.network?.let { net ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Network: $net",
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }

            // No data at all
            if (score == null && monitor == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "No health data available for this relay.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun TrustBar(label: String, value: Int, weight: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.width(100.dp),
        )
        Text(
            text = "$value",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(28.dp),
        )
        LinearProgressIndicator(
            progress = { value / 100f },
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = trustColor(value),
            trackColor = Color(0xFF333333),
        )
        Text(
            text = "${(weight * 100).toInt()}%",
            color = TextSecondary.copy(alpha = 0.6f),
            fontSize = 10.sp,
            modifier = Modifier.width(28.dp).padding(start = 4.dp),
        )
    }
}

@Composable
private fun MetadataChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(text = label, color = TextSecondary, fontSize = 10.sp)
    }
}
