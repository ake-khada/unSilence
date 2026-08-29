package com.unsilence.app.ui.settings.console

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.relay.ConnectionPurpose
import com.unsilence.app.data.relay.Reachability
import com.unsilence.app.data.relay.RelayState
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BorderFaint
import com.unsilence.app.ui.theme.BorderSubtle
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.Text4
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Warn

@Composable
fun ConsoleScreen(
    onDismiss: () -> Unit,
    viewModel: ConsoleViewModel = hiltViewModel(
        key = "console-${LocalAppSessionKey.current}",
    ),
) {
    BackHandler(onBack = onDismiss)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Box(modifier = Modifier.fillMaxSize().background(Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            ConsoleTopBar(
                refreshing = state.refreshing,
                onRefresh = { viewModel.refresh() },
                onDismiss = onDismiss,
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = Spacing.small,
                    bottom = Spacing.xl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                item {
                    RelaysCard(state)
                }
                item {
                    StoreCard(state)
                }
                item {
                    LogTailCard(
                        state = state,
                        copied = copied,
                        onFilter = viewModel::setFilter,
                        onCopy = {
                            copyDiagnostics(context, viewModel.diagnosticsText(state))
                            copied = true
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsoleTopBar(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(Sizing.topBarHeight)
            .padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Console",
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRefresh, enabled = !refreshing) {
            if (refreshing) {
                CircularProgressIndicator(
                    color = Brand,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = TextSecondary)
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
        }
    }
}

@Composable
private fun RelaysCard(state: ConsoleUiState) {
    ConsoleCard(title = "Relays", icon = Icons.Filled.Wifi) {
        Text(
            text = state.relaySummary,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = state.relayCatalogSummary,
            color = Text3,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
        Spacer(Modifier.height(Spacing.small))
        if (state.relays.isEmpty()) {
            Text("No relay connections", color = Text3, fontSize = 12.sp)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.relays.forEach { row ->
                    RelayRow(row)
                }
            }
        }
    }
}

@Composable
private fun RelayRow(row: ConsoleRelayRow) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(relayStateColor(row.state)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = row.url,
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = row.state?.name?.lowercase() ?: "not active",
                color = Text3,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        val meta = relayMeta(row)
        if (meta.isNotEmpty()) {
            Text(
                text = meta.joinToString(" · "),
                color = Text3,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(start = 16.dp, top = 3.dp),
            )
        }
    }
}

@Composable
private fun StoreCard(state: ConsoleUiState) {
    ConsoleCard(title = "Store & gates", icon = Icons.Filled.Storage) {
        val store = state.store
        if (store == null) {
            Text("Store snapshot unavailable", color = Text3, fontSize = 12.sp)
            return@ConsoleCard
        }
        Text(
            text = "${store.eventCount} events · ${formatConsoleBytes(store.totalEstimatedBytes)} estimated",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "${store.profileCount} profiles · ${store.followsEntries} follow lists · snapshot ${formatSnapshotAgeSeconds(state.snapshotAgeSeconds)}",
            color = Text3,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(Spacing.small))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            store.eventsByKind.toSortedMap().forEach { (kind, count) ->
                ConsoleChip("kind $kind: $count")
            }
        }
        Spacer(Modifier.height(Spacing.small))
        Text(
            text = state.gatesSummary,
            color = Color.White,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        state.gates.wotTargetsHash.takeIf { it.isNotBlank() }?.let { hash ->
            Text(
                text = "WoT targets ${hash.take(10)}",
                color = Text3,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun LogTailCard(
    state: ConsoleUiState,
    copied: Boolean,
    onFilter: (ConsoleLogFilter) -> Unit,
    onCopy: () -> Unit,
) {
    ConsoleCard(title = "Log tail", icon = Icons.Filled.Terminal) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            ConsoleLogFilter.entries.forEach { filter ->
                FilterChip(
                    label = filter.label,
                    selected = state.selectedFilter == filter,
                    onClick = { onFilter(filter) },
                )
            }
        }
        Spacer(Modifier.height(Spacing.small))
        ConsoleButton(
            text = if (copied) "Copied" else "Copy diagnostics",
            icon = Icons.Filled.ContentCopy,
            onClick = onCopy,
        )
        Spacer(Modifier.height(Spacing.small))
        val unavailable = state.logsUnavailableMessage
        when {
            unavailable != null -> Text(
                text = "logs unavailable on this device",
                color = Text3,
                fontSize = 12.sp,
            )
            state.filteredLogs.isEmpty() -> Text(
                text = "No matching logs",
                color = Text3,
                fontSize = 12.sp,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.filteredLogs.take(80).forEach { line ->
                    LogLineRow(line)
                }
            }
        }
    }
}

@Composable
private fun LogLineRow(line: ConsoleLogLine) {
    Column {
        Text(
            text = "${line.time} ${line.level}/${line.tag}".trim(),
            color = logLevelColor(line.level),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = line.message,
            color = TextSecondary,
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        HorizontalDivider(color = BorderFaint, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ConsoleCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1)
            .border(1.dp, BorderFaint, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun ConsoleButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ConsoleChip(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Surface2)
            .border(1.dp, BorderSubtle, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = if (selected) Color.Black else TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Brand else Surface2)
            .border(1.dp, if (selected) Brand else BorderSubtle, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

private fun relayStateColor(state: RelayState?): Color = when (state) {
    RelayState.CONNECTED -> Mint
    RelayState.CONNECTING -> Brand
    RelayState.FAILED -> Warn
    RelayState.DISCONNECTED, null -> Text4
}

private fun logLevelColor(level: String): Color = when (level) {
    "E", "F" -> Warn
    "W" -> Warn
    "I" -> Mint
    "D", "V" -> Text3
    else -> Text4
}

private fun relayMeta(row: ConsoleRelayRow): List<String> = buildList {
    if (row.purposes.isNotEmpty()) {
        add(row.purposes.joinToString("+") { it.label() })
    }
    if (row.isTransient) add("transient")
    if (row.hasActiveSubscription) add("live sub")
    if (row.oneShotCount > 0) add("${row.oneShotCount} one-shot")
    if (row.queuedReqCount > 0) add("${row.queuedReqCount} queued")
    row.directory?.let { entry ->
        add(entry.reachability.label())
        entry.monitorRttMs?.let { add("${it}ms") }
        entry.trustScore?.let { add("trust $it") }
    }
}.distinct()

private fun ConnectionPurpose.label(): String = when (this) {
    ConnectionPurpose.PERSISTENT -> "persistent"
    ConnectionPurpose.BROWSE -> "browse"
    ConnectionPurpose.FEED_SUB -> "feed"
    ConnectionPurpose.FEED_WARM -> "warm"
}

private fun Reachability.label(): String = when (this) {
    Reachability.REACHABLE -> "reachable"
    Reachability.AUTH_WALL -> "auth wall"
    Reachability.DNS_BLOCKED -> "dns blocked"
    Reachability.TIMEOUT_PRONE -> "timeout prone"
    Reachability.DEAD -> "dead"
    Reachability.UNKNOWN -> "unknown"
}

private fun copyDiagnostics(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("unSilence diagnostics", text))
}
