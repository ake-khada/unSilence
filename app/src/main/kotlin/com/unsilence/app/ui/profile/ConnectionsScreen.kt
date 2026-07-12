package com.unsilence.app.ui.profile

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.relay.formatFollowerCount
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.ui.shared.WotInlineLabel
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BorderSubtle
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.TextSecondary
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ConnectionsScreen(
    pubkey: String,
    initialTab: ConnectionsTab,
    onDismiss: () -> Unit,
    onProfileClick: (String) -> Unit,
    viewModel: ConnectionsViewModel = hiltViewModel(
        key = "connections-${LocalAppSessionKey.current}-$pubkey-${initialTab.name}",
    ),
) {
    BackHandler(onBack = onDismiss)
    LaunchedEffect(pubkey, initialTab) { viewModel.initialize(pubkey, initialTab) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(state.selectedTab) { listState.scrollToItem(0) }

    LaunchedEffect(listState, state.rows) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                state.rows.getOrNull(item.index)?.pubkey
            }
        }.distinctUntilChanged().collect(viewModel::requestVisibleRows)
    }
    LaunchedEffect(state.rows) {
        viewModel.requestVisibleRows(state.rows.take(30).map(ConnectionRow::pubkey))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(Sizing.topBarHeight)
                .padding(horizontal = Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Connections",
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

        ConnectionsTabs(
            selected = state.selectedTab,
            onSelected = viewModel::selectTab,
            followerCount = state.followerCount,
        )

        if (state.selectedTab == ConnectionsTab.Followers) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface1)
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(Spacing.small))
                Text(
                    text = "Approximate — gathered from index relays.",
                    color = TextSecondary,
                    fontSize = AppType.caption,
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.loading && state.rows.isEmpty() -> CircularProgressIndicator(
                    color = Brand,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.Center),
                )
                state.loadFailed && state.rows.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Couldn't load connections", color = Text3, fontSize = AppType.body)
                    TextButton(onClick = viewModel::retry) { Text("Retry", color = Brand) }
                }
                !state.loading && state.rows.isEmpty() -> Text(
                    text = if (state.selectedTab == ConnectionsTab.Following) {
                        "No follows found"
                    } else {
                        "No followers found"
                    },
                    color = Text3,
                    fontSize = AppType.body,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.rows, key = ConnectionRow::pubkey) { row ->
                        ConnectionPersonRow(row = row, onClick = { onProfileClick(row.pubkey) })
                        HorizontalDivider(
                            color = BorderSubtle,
                            modifier = Modifier.padding(start = 72.dp),
                        )
                    }
                    if (state.selectedTab == ConnectionsTab.Followers &&
                        (state.hasMoreFollowers || state.loadingMore || state.loadFailed)
                    ) {
                        item(key = "load-more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (state.loadingMore) {
                                    CircularProgressIndicator(
                                        color = Brand,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(20.dp),
                                    )
                                } else {
                                    TextButton(onClick = { viewModel.loadMoreFollowers() }) {
                                        Text(
                                            if (state.loadFailed) "Retry" else "Load more",
                                            color = Brand,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionsTabs(
    selected: ConnectionsTab,
    onSelected: (ConnectionsTab) -> Unit,
    followerCount: Long?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        ConnectionsTab.entries.forEach { tab ->
            val active = selected == tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable { onSelected(tab) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (tab == ConnectionsTab.Following) {
                        "Following"
                    } else {
                        followerCount?.let { "Followers ${formatFollowerCount(it)}" } ?: "Followers"
                    },
                    color = if (active) Color.White else Text3,
                    fontSize = AppType.body,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (active) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(0.42f)
                            .height(2.dp)
                            .background(Brand),
                    )
                }
            }
        }
    }
    HorizontalDivider(color = BorderSubtle)
}

@Composable
private fun ConnectionPersonRow(row: ConnectionRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(Sizing.avatar)
                .clip(CircleShape),
        ) {
            IdentIcon(pubkey = row.pubkey, modifier = Modifier.fillMaxSize())
            if (!row.user.picture.isNullOrBlank()) {
                AsyncImage(
                    model = rememberAvatarImageRequest(row.user.picture, Sizing.avatar),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(Spacing.small))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.user.displayName?.takeIf(String::isNotBlank)
                        ?: row.user.name?.takeIf(String::isNotBlank)
                        ?: shortConnectionNpub(row.pubkey),
                    color = Color.White,
                    fontSize = AppType.body,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.followsViewer) {
                    Spacer(Modifier.width(Spacing.small))
                    Text(
                        text = "follows you",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = shortConnectionNpub(row.pubkey),
                color = Text3,
                fontSize = AppType.caption,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier.width(58.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            val scored = row.wot as? WotLookup.Scored
            if (scored != null) {
                WotInlineLabel(assertion = scored.assertion)
            }
        }
    }
}

private fun shortConnectionNpub(pubkey: String): String =
    runCatching { pubkey.hexToByteArray().toNpub() }
        .getOrNull()
        ?.let { "${it.take(12)}…${it.takeLast(5)}" }
        ?: "${pubkey.take(10)}…"
