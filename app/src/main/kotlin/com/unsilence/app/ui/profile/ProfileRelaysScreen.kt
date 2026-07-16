package com.unsilence.app.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.memory.RelayConfig
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.relays.RelayIcon
import com.unsilence.app.ui.shared.relayDisplayHost
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BorderSubtle
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap

@Composable
fun ProfileRelaysScreen(
    pubkey: String,
    onDismiss: () -> Unit,
    onOpenRelay: (String) -> Unit,
    viewModel: ProfileRelaysViewModel = hiltViewModel(
        key = "profile-relays-${LocalAppSessionKey.current}-$pubkey",
    ),
) {
    BackHandler(onBack = onDismiss)
    LaunchedEffect(pubkey) { viewModel.initialize(pubkey) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val facts = state.facts
    LaunchedEffect(facts) { viewModel.prefetchRelayIdentities(facts) }
    val hasRows = facts.relays.isNotEmpty() ||
        facts.searchRelays.isNotEmpty() ||
        facts.blockedRelays.isNotEmpty()

    Column(modifier = Modifier.fillMaxSize().background(Black)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(Sizing.topBarHeight)
                .padding(horizontal = Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Relays",
                color = Color.White,
                fontSize = AppType.subheading,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
            }
        }
        HorizontalDivider(color = BorderSubtle)

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                hasRows -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (facts.relays.isNotEmpty()) {
                        item { RelaySectionLabel("RELAYS") }
                        items(facts.relays.size, key = { index -> facts.relays[index].url }) { index ->
                            val relay = facts.relays[index]
                            ProfileRelayRow(
                                url = relay.url,
                                iconUrl = state.relayIcons[relay.url],
                                marker = relay,
                                onVisible = { viewModel.requestVisibleRelayIdentity(relay.url) },
                                onClick = { onOpenRelay(relay.url) },
                            )
                        }
                    }
                    if (facts.searchRelays.isNotEmpty()) {
                        item { RelaySectionLabel("SEARCH · 10007") }
                        items(facts.searchRelays.size, key = { index -> facts.searchRelays[index] }) { index ->
                            val url = facts.searchRelays[index]
                            ProfileRelayRow(
                                url = url,
                                iconUrl = state.relayIcons[url],
                                onVisible = { viewModel.requestVisibleRelayIdentity(url) },
                                onClick = { onOpenRelay(url) },
                            )
                        }
                    }
                    if (facts.blockedRelays.isNotEmpty()) {
                        item { RelaySectionLabel("BLOCKED · 10006") }
                        items(facts.blockedRelays.size, key = { index -> facts.blockedRelays[index] }) { index ->
                            val url = facts.blockedRelays[index]
                            ProfileRelayRow(
                                url = url,
                                iconUrl = state.relayIcons[url],
                                onVisible = { viewModel.requestVisibleRelayIdentity(url) },
                                onClick = { onOpenRelay(url) },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(Spacing.large)) }
                }
                state.loading -> CircularProgressIndicator(
                    color = Brand,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp).align(Alignment.Center),
                )
                state.loadFailed -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Couldn't load relay lists", color = Text3, fontSize = AppType.body)
                    TextButton(onClick = viewModel::retry) { Text("Retry", color = Brand) }
                }
                else -> Text(
                    text = "No relay list published",
                    color = Text3,
                    fontSize = AppType.body,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun RelaySectionLabel(text: String) {
    Text(
        text = text,
        color = Text3,
        fontSize = AppType.caption,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(
            start = Spacing.medium,
            end = Spacing.medium,
            top = Spacing.medium,
            bottom = Spacing.small,
        ),
    )
}

@Composable
private fun ProfileRelayRow(
    url: String,
    iconUrl: String?,
    marker: RelayConfig? = null,
    onVisible: () -> Unit,
    onClick: () -> Unit,
) {
    LaunchedEffect(url) { onVisible() }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RelayIcon(iconUrl, Modifier.size(34.dp))
            Spacer(Modifier.width(Spacing.small))
            Text(
                text = relayDisplayHost(url),
                color = Color.White,
                fontSize = AppType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            marker?.let {
                Spacer(Modifier.width(Spacing.small))
                RelayMarkerChip(it.marker)
            }
        }
        HorizontalDivider(
            color = BorderSubtle,
            modifier = Modifier.padding(start = 54.dp),
        )
    }
}

@Composable
private fun RelayMarkerChip(marker: String?) {
    val (label, accent) = when (marker) {
        "read" -> "R" to Brand
        "write" -> "W" to Zap
        else -> "RW" to Mint
    }
    Box(
        modifier = Modifier
            .height(22.dp)
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .border(0.5.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = accent,
            fontSize = AppType.caption,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
