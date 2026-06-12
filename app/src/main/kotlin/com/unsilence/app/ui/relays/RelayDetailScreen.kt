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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Verified
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.unsilence.app.data.relay.Nip11Source
import com.unsilence.app.data.relay.Reachability
import com.unsilence.app.data.relay.RelayDirectoryEntry
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.feed.AvatarImage
import com.unsilence.app.ui.feed.parseNip05
import com.unsilence.app.ui.theme.BorderFaint
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.BrandSoft
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub

/**
 * §05 Relay detail page. Everything about ONE relay on one screen: device-fetched NIP-11
 * (the authority — PERSPECTIVE RULE), an honest reachability verdict from THIS device, a
 * live latency probe, supported NIPs, and operator policy. Read-only here; Browse + the
 * sticky action footer land in the next commit. Reuses the shared RelayManagementViewModel
 * (activity-scoped) so membership state is consistent with the relay list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RelayDetailScreen(
    relayUrl: String,
    onDismiss: () -> Unit,
    onOpenProfile: (pubkeyHex: String) -> Unit = {},
    viewModel: RelayManagementViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onDismiss)
    val clipboard = LocalClipboardManager.current

    var entry by remember(relayUrl) { mutableStateOf<RelayDirectoryEntry?>(null) }
    var loading by remember(relayUrl) { mutableStateOf(true) }
    LaunchedEffect(relayUrl) {
        loading = true
        entry = viewModel.loadRelayDetail(relayUrl)
        loading = false
    }

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
                    Text("Relay", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            ) {
                val e = entry
                // ── Hero ─────────────────────────────────────────────────────
                RelayDetailHero(relayUrl, e, loading) {
                    clipboard.setText(AnnotatedString(relayUrl))
                }

                if (loading && e == null) {
                    DetailSkeleton()
                } else if (e != null) {
                    ReachabilityRow(e)
                    StatsStrip(e)
                    if (!e.description.isNullOrBlank()) {
                        DetailSection("About") {
                            Text(e.description!!, color = TextSecondary, fontSize = 13.5f.sp, lineHeight = 20.sp)
                        }
                    }
                    if (e.supportedNips.isNotEmpty()) {
                        DetailSection("Supported features") { NipChips(e.supportedNips) }
                    }
                    DetailSection("Policy") {
                        PolicyRows(e)
                        e.operatorPubkey?.let { op ->
                            OperatorRow(op, viewModel) { onOpenProfile(op) }
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

@Composable
private fun RelayDetailHero(url: String, e: RelayDirectoryEntry?, loading: Boolean, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.medium, vertical = Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = e?.icon
        Box(modifier = Modifier.size(50.dp).clip(RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            if (!icon.isNullOrBlank()) {
                AsyncImage(model = icon, contentDescription = null, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)))
            } else {
                IdentIcon(pubkey = e?.operatorPubkey ?: url, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(Spacing.medium))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = e?.name ?: relayHost(url),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = url.removeSuffix("/"),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy URL",
                    tint = Text3,
                    modifier = Modifier.size(15.dp).clickable(onClick = onCopy),
                )
            }
        }
    }
}

/** Our differentiator — an honest verdict from THIS device's perspective (doc has no such row). */
@Composable
private fun ReachabilityRow(e: RelayDirectoryEntry) {
    val rtt = e.ourRttMs ?: e.monitorRttMs
    val (headline, detail, color) = when (e.reachability) {
        Reachability.REACHABLE ->
            if (e.ourRttMs != null) Triple("Healthy", "${e.ourRttMs}ms observed", latencyTier(e.ourRttMs))
            else Triple("Reachable", if (rtt != null) "${rtt}ms (monitor)" else "latency untested", Mint)
        Reachability.AUTH_WALL -> Triple("Requires auth", "this relay needs NIP-42 sign-in", Zap)
        Reachability.DNS_BLOCKED -> Triple("DNS-blocked on your network", "couldn't resolve it from this device", Like)
        Reachability.TIMEOUT_PRONE -> Triple("Slow / times out", "unreliable from your network", Zap)
        Reachability.DEAD -> Triple("Unreachable", "no successful connection from here", Like)
        Reachability.UNKNOWN -> Triple("Untested from your network", "no observation yet", Text3)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = Spacing.medium, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Spacer(Modifier.width(10.dp))
        Text(headline, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        Text("· $detail", color = TextSecondary, fontSize = 12.5f.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** [latency · NIPs · you-follow]. The doc's "30d uptime" cell is dropped — no honest source
 *  (the monitor schema carries no uptime field; fabricating one would lie). */
@Composable
private fun StatsStrip(e: RelayDirectoryEntry) {
    val rtt = e.ourRttMs ?: e.monitorRttMs
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.medium)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatCell(Modifier.weight(1f), if (rtt != null) "$rtt" else "—", "ms", "Latency", if (rtt != null) latencyTier(rtt) else Text3)
        StatDivider()
        StatCell(Modifier.weight(1f), "${e.supportedNips.size}", null, "NIPs", Color.White)
        StatDivider()
        StatCell(Modifier.weight(1f), "${e.followsUsing}", null, "You follow", Color.White)
    }
}

@Composable
private fun StatCell(modifier: Modifier, value: String, unit: String?, label: String, valueColor: Color) {
    Column(modifier = modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = valueColor, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (unit != null) Text(unit, color = valueColor, fontSize = 10.sp, modifier = Modifier.padding(start = 1.dp, bottom = 2.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = Text3, fontSize = 11.sp)
    }
}

@Composable
private fun StatDivider() {
    Box(modifier = Modifier.height(34.dp).width(1.dp).background(BorderFaint))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NipChips(nips: Set<Int>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        nips.sorted().forEach { nip ->
            val label = NIP_LABELS[nip]
            NipChip(text = if (label != null) "NIP-$nip $label" else "NIP-$nip", highlighted = label != null)
        }
    }
}

@Composable
private fun NipChip(text: String, highlighted: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (highlighted) BrandSoft else Surface1)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(text, color = if (highlighted) Brand else TextSecondary, fontSize = 12.sp, fontWeight = if (highlighted) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun PolicyRows(e: RelayDirectoryEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val (payValue, payColor) = when {
            e.payment && e.feeMsats != null -> "${e.feeMsats / 1000} sats" to Zap
            e.payment -> "Required" to Zap
            else -> "Free" to Mint
        }
        PolicyRow("Payment", payValue, payColor)
        PolicyRow("Auth required", if (e.auth) "Yes" else "No", if (e.auth) Zap else TextSecondary)
        if (!e.software.isNullOrBlank()) {
            val sw = remember(e.software) { cleanSoftware(e.software!!) }
            val ver = e.version?.takeIf { it.isNotBlank() && !sw.contains(it, ignoreCase = true) }
            PolicyRow("Software", listOfNotNull(sw, ver).joinToString(" "), TextSecondary)
        }
    }
}

/** NIP-11 `software` is often a repo URL (e.g. "git+https://github.com/hoytech/strfry.git").
 *  Show its readable name ("strfry") rather than dumping the URL; bare names pass through. */
private fun cleanSoftware(software: String): String {
    val token = software.trim().substringBefore(' ')
    val s = token.removePrefix("git+").trimEnd('/')
    return if (s.startsWith("http://") || s.startsWith("https://"))
        s.substringAfterLast('/').removeSuffix(".git").ifBlank { software }
    else software
}

/** Self-declared operator (NIP-11 `pubkey`), depicted like a feed author: avatar + name +
 *  cyan NIP-05 checkmark + domain. Resolves the npub to a profile once the kind-0 lands; taps
 *  open the profile. The checkmark is NIP-05 verification of the profile's own handle — NOT a
 *  claim that this account verifiably operates the relay (self-ID ≠ verification). */
@Composable
private fun OperatorRow(pubkeyHex: String, viewModel: RelayManagementViewModel, onOpen: () -> Unit) {
    val profile by viewModel.operatorProfileFlow(pubkeyHex).collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(pubkeyHex) { viewModel.fetchOperatorProfile(pubkeyHex) }
    val npub = remember(pubkeyHex) { runCatching { pubkeyHex.hexToByteArray().toNpub() }.getOrNull() }
    val name = profile?.displayName?.takeIf { it.isNotBlank() }
        ?: profile?.name?.takeIf { it.isNotBlank() }
        ?: npub?.let { shortNpub(it) }
        ?: shortNpub(pubkeyHex)
    val parsedNip05 = remember(profile?.nip05) {
        profile?.nip05?.takeIf { it.isNotBlank() }?.let { parseNip05(it) }
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "Operator" label takes the left slack so the author chip right-aligns like the KV rows.
        Text("Operator", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        AvatarImage(pubkey = pubkeyHex, picture = profile?.picture, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            name,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp),
        )
        if (parsedNip05 != null) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.Verified, contentDescription = "NIP-05 verified", tint = Brand, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(3.dp))
            Text(parsedNip05.second, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
        }
    }
}

@Composable
private fun PolicyRow(key: String, value: String, valueColor: Color, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DetailSection(label: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.medium, vertical = Spacing.small)) {
        Text(label.uppercase(), color = Text3, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(Spacing.small))
        content()
    }
}

@Composable
private fun DetailSkeleton() {
    Column(modifier = Modifier.fillMaxWidth().padding(Spacing.medium)) {
        repeat(4) {
            Box(modifier = Modifier.fillMaxWidth().height(if (it == 0) 56.dp else 38.dp).clip(RoundedCornerShape(10.dp)).background(Surface1))
            Spacer(Modifier.height(Spacing.small))
        }
    }
}

private fun relayHost(url: String): String =
    url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/")

private fun shortNpub(npub: String): String =
    if (npub.length > 18) "${npub.take(10)}…${npub.takeLast(6)}" else npub

private val NIP_LABELS = mapOf(50 to "Search", 65 to "Outbox", 42 to "Auth")
