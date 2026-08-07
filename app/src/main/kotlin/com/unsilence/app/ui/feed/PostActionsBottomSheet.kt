package com.unsilence.app.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap
import com.unsilence.app.ui.relays.RelayIcon
import com.unsilence.app.ui.shared.RelayProvenanceItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostActionsBottomSheet(
    authorPubkey: String,
    authorProfile: UserEntity?,
    onDismiss: () -> Unit,
    onCopyText: (() -> Unit)?,
    onCopyLink: () -> Unit,
    onShare: () -> Unit,
    relayItemsProvider: () -> List<RelayProvenanceItem> = { emptyList() },
    onRelayClick: (String) -> Unit = {},
    onMuteUser: () -> Unit,
    onReport: () -> Unit,
    canDelete: Boolean = false,
    onDelete: () -> Unit = {},
    showModerationActions: Boolean = true,
) {
    val relayItems = remember { relayItemsProvider() }
    var relaysExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary) },
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // Author context header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarImage(
                    pubkey = authorPubkey,
                    picture = authorProfile?.picture,
                    modifier = Modifier.size(36.dp),
                    sizeDp = 36.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = authorProfile?.displayName?.takeIf { it.isNotBlank() }
                            ?: authorProfile?.name?.takeIf { it.isNotBlank() }
                            ?: "${authorPubkey.take(8)}…",
                        fontSize = AppType.body,
                        color = Color.White,
                    )
                    authorProfile?.nip05?.takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = AppType.caption, color = TextSecondary)
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            if (onCopyText != null) {
                SheetActionRow(Icons.Default.ContentCopy, "Copy text") { onCopyText(); onDismiss() }
            }
            SheetActionRow(Icons.Default.Link, "Copy link") { onCopyLink(); onDismiss() }
            SheetActionRow(Icons.Default.Share, "Share") { onShare(); onDismiss() }

            if (relayItems.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { relaysExpanded = !relaysExpanded }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Dns,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = "Seen on ${relayItems.size} ${if (relayItems.size == 1) "relay" else "relays"}",
                        fontSize = AppType.body,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (relaysExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (relaysExpanded) "Collapse relays" else "Expand relays",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                AnimatedVisibility(
                    visible = relaysExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column {
                        relayItems.forEach { relay ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onRelayClick(relay.url)
                                        onDismiss()
                                    }
                                    .padding(start = 56.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RelayIcon(relay.iconUrl, Modifier.size(28.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = relay.host,
                                    fontSize = AppType.body,
                                    color = TextSecondary,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
            if (canDelete) {
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.1f),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                SheetActionRow(Icons.Default.Delete, "Delete", Zap) {
                    onDelete()
                    onDismiss()
                }
            }

            if (showModerationActions) {
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.1f),
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                SheetActionRow(Icons.AutoMirrored.Filled.VolumeOff, "Mute user", Zap) {
                    onMuteUser(); onDismiss()
                }
                SheetActionRow(Icons.Default.Flag, "Report", Zap) {
                    onReport()
                }
            }
        }
    }
}

@Composable
internal fun SheetActionRow(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = AppType.body, color = tint)
    }
}
