package com.unsilence.app.ui.relays

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.relay.EyesAlliance
import com.unsilence.app.data.relay.Reachability
import com.unsilence.app.data.relay.RelayDirectory
import com.unsilence.app.data.relay.RelayDirectoryEntry
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.BrandSoft
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap

/**
 * §04 Relay Discovery — the directory's front door. Turns "paste a wss:// URL you already know"
 * into a browsable, health-ranked, social directory. The DEFAULT SORT is the product identity:
 * computeDirectoryScore is reachability-weighted, so "works from here" outranks "globally famous"
 * — and DNS-blocked/dead relays sort to the bottom but stay VISIBLE (honesty over polish). The
 * directory firehose fires HERE (on open) and nowhere else — it costs nothing until you discover.
 */
enum class DiscoveryFacet(val label: String) {
    ALL("All"), FAST("Fast"), FREE("Free"), PAID("Paid"),
    SEARCH("Search"), MEDIA("Media"), OUTSIDE_14("Outside 14 Eyes")
}

@Composable
fun RelayDiscoveryScreen(
    onDismiss: () -> Unit,
    onOpenDetail: (url: String) -> Unit,
    viewModel: RelayManagementViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { viewModel.ensureDirectory() }   // firehose runs ONLY here

    val directory by viewModel.relayDirectory.collectAsStateWithLifecycle()
    val building by viewModel.directoryBuilding.collectAsStateWithLifecycle()
    // Before the build flag has flipped true at least once, assume we're loading — avoids a
    // one-frame "no relays" flash on first open while ensureDirectory() spins up.
    var sawBuild by remember { mutableStateOf(false) }
    LaunchedEffect(building) { if (building) sawBuild = true }
    var query by remember { mutableStateOf("") }
    var facet by remember { mutableStateOf(DiscoveryFacet.ALL) }

    val ranked = remember(directory, query, facet) {
        directory.values.asSequence()
            .filter { matchesQuery(it, query) }
            .filter { matchesFacet(it, facet) }
            .sortedByDescending { score(it) }     // reachability-weighted: works-from-here wins
            .toList()
    }
    val searching = query.isNotBlank()
    val faceted = facet != DiscoveryFacet.ALL

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top bar ──────────────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(Sizing.topBarHeight).padding(horizontal = Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    Text("Discover relays", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            DiscoverySearchField(query = query, onQuery = { query = it })
            FacetChips(active = facet, onSelect = { facet = it })

            when {
                // Empty only until the firehose (triggered on open) populates it; a prior build
                // this session already filled directoryFlow, so re-opens skip straight to content.
                directory.isEmpty() -> DiscoveryLoading(loading = building || !sawBuild)
                else -> {
                    val listState = rememberLazyListState()
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        if (searching || faceted) {
                            if (ranked.isEmpty()) {
                                item { NoMatches(query) }
                            } else {
                                items(ranked, key = { it.url }) { DiscoveryCard(it) { onOpenDetail(it.url) } }
                            }
                        } else {
                            // Sections (no query/facet). "Works on your network" leads — the
                            // identity statement; then the social killer module; then specialty.
                            relaySection("Works on your network", ranked.filter { it.reachability == Reachability.REACHABLE }.take(SECTION_CAP), onOpenDetail)
                            relaySection("Used by people you follow", ranked.filter { it.followsUsing > 0 }.sortedByDescending { it.followsUsing }.take(SECTION_CAP), onOpenDetail)
                            relaySection("Specialty & community", ranked.filter { it.restrictedWrites || it.auth }.take(SECTION_CAP), onOpenDetail)
                            item { Spacer(Modifier.height(32.dp)) }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.relaySection(label: String, list: List<RelayDirectoryEntry>, onOpenDetail: (String) -> Unit) {
    if (list.isEmpty()) return
    item(key = "sec-$label") {
        Text(
            label.uppercase(),
            color = Text3,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
            modifier = Modifier.fillMaxWidth().padding(start = Spacing.medium, end = Spacing.medium, top = Spacing.medium, bottom = Spacing.small),
        )
    }
    items(list, key = { "${label}:${it.url}" }) { DiscoveryCard(it) { onOpenDetail(it.url) } }
}

// ── Card ──────────────────────────────────────────────────────────────────────

@Composable
private fun DiscoveryCard(e: RelayDirectoryEntry, onClick: () -> Unit) {
    val unreachable = e.reachability == Reachability.DNS_BLOCKED || e.reachability == Reachability.DEAD
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .clickable(onClick = onClick)
            .padding(Spacing.medium),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RelayIcon(e.icon, Modifier.size(34.dp))
            Spacer(Modifier.width(Spacing.small))
            Column(modifier = Modifier.weight(1f)) {
                Text(e.name ?: discoveryHost(e.url), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(discoveryHost(e.url), color = Text3, fontSize = 11.5f.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!e.description.isNullOrBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(e.description!!, color = TextSecondary, fontSize = 12.5f.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (unreachable) {
                FootStat(dot = Like, text = "unreachable on your network", color = Like)
            } else {
                val latency = e.ourRttMs ?: e.monitorRttMs
                latencyLabel(latency)?.let { FootStat(dot = latencyTier(latency), text = it, color = TextSecondary) }
            }
            if (e.followsUsing > 0) FootStat(dot = null, text = "${e.followsUsing} you follow", color = TextSecondary)
            if (e.supportedNips.contains(50) && !unreachable) Text("Search", color = Brand.copy(alpha = 0.7f), fontSize = 11.5f.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (e.payment) PaidBadge(e.feeMsats)
        }
    }
}

@Composable
private fun FootStat(dot: Color?, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) {
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color = color, fontSize = 11.5f.sp, maxLines = 1)
    }
}

@Composable
private fun PaidBadge(feeMsats: Long?) {
    val label = feeMsats?.let { "PAID · ${it / 1000} sats" } ?: "PAID"
    Box(modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Zap.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(label, color = Zap, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

// ── Search + chips ──────────────────────────────────────────────────────────

@Composable
private fun DiscoverySearchField(query: String, onQuery: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small)
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .padding(horizontal = Spacing.medium, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Text3, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("Search by name, topic, or operator…", color = Text3, fontSize = 14.sp)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = SolidColor(Brand),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(Icons.Filled.Close, contentDescription = "Clear", tint = Text3, modifier = Modifier.size(16.dp).clickable { onQuery("") })
        }
    }
}

@Composable
private fun FacetChips(active: DiscoveryFacet, onSelect: (DiscoveryFacet) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.small),
        contentPadding = PaddingValues(horizontal = Spacing.medium),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        items(DiscoveryFacet.entries, key = { it.name }) { f ->
            val on = f == active
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (on) BrandSoft else Surface2)
                    .border(1.dp, if (on) Brand.copy(alpha = 0.4f) else Color.Transparent, RoundedCornerShape(16.dp))
                    .clickable { onSelect(f) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(f.label, color = if (on) Brand else TextSecondary, fontSize = 12.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

@Composable
private fun DiscoveryLoading(loading: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) {
                CircularProgressIndicator(color = Brand, modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.height(Spacing.medium))
                Text("Scanning the relay directory…", color = TextSecondary, fontSize = 13.sp)
            } else {
                Text("No relays found — check your connection.", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(Spacing.xl))
            }
        }
    }
}

@Composable
private fun NoMatches(query: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
        Text(if (query.isNotBlank()) "No relays match \"$query\"." else "No relays match this filter.", color = TextSecondary, fontSize = 13.sp)
    }
}

// ── Pure helpers ────────────────────────────────────────────────────────────

private const val SECTION_CAP = 8

private fun score(e: RelayDirectoryEntry): Int =
    RelayDirectory.computeDirectoryScore(e.reachability, e.trustScore, e.monitorRttMs, e.popularity)

private fun matchesQuery(e: RelayDirectoryEntry, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return e.url.lowercase().contains(q) ||
        e.name?.lowercase()?.contains(q) == true ||
        e.description?.lowercase()?.contains(q) == true ||
        e.software?.lowercase()?.contains(q) == true
}

private fun matchesFacet(e: RelayDirectoryEntry, facet: DiscoveryFacet): Boolean = when (facet) {
    DiscoveryFacet.ALL -> true
    DiscoveryFacet.FAST -> (e.ourRttMs ?: e.monitorRttMs)?.let { it in 0..149 } == true
    DiscoveryFacet.FREE -> !e.payment
    DiscoveryFacet.PAID -> e.payment
    DiscoveryFacet.SEARCH -> e.supportedNips.contains(50)
    DiscoveryFacet.MEDIA -> e.supportedNips.contains(96)
    // Unknown geo is EXCLUDED, never assumed outside — no fabrication.
    DiscoveryFacet.OUTSIDE_14 -> e.countryCode != null && RelayDirectory.eyesAlliance(e.countryCode) == EyesAlliance.NONE
}

private fun latencyLabel(ms: Int?): String? = when {
    ms == null -> null
    ms < 150 -> "fast · ${ms}ms"
    ms <= 500 -> "ok · ${ms}ms"
    else -> "slow · ${ms}ms"
}

private fun discoveryHost(url: String): String =
    url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/")
