package com.unsilence.app.ui.feed

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.ZapAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostActionsBottomSheet(
    authorPubkey: String,
    authorProfile: UserEntity?,
    onDismiss: () -> Unit,
    onCopyText: () -> Unit,
    onCopyLink: () -> Unit,
    onShare: () -> Unit,
    onMuteUser: () -> Unit,
    onReport: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary) },
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
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

            SheetActionRow(Icons.Default.ContentCopy, "Copy text") { onCopyText(); onDismiss() }
            SheetActionRow(Icons.Default.Link, "Copy link") { onCopyLink(); onDismiss() }
            SheetActionRow(Icons.Default.Share, "Share") { onShare(); onDismiss() }

            HorizontalDivider(
                color = Color.White.copy(alpha = 0.1f),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            SheetActionRow(Icons.AutoMirrored.Filled.VolumeOff, "Mute user", ZapAmber) {
                onMuteUser(); onDismiss()
            }
            SheetActionRow(Icons.Default.Flag, "Report", ZapAmber) {
                onReport()
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
