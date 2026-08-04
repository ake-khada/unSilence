package com.unsilence.app.ui.shared

import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.model.ReportType
import com.unsilence.app.data.repository.MuteResult
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.common.LocalOpenRelayDetail
import com.unsilence.app.ui.feed.PostActionsBottomSheet
import com.unsilence.app.ui.feed.ReportTypeSheet
import com.unsilence.app.ui.feed.collectProfileAsState
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import kotlinx.coroutines.flow.StateFlow

@Composable
fun PostActionsHost(
    row: FeedRow?,
    profileFlow: (String) -> StateFlow<UserEntity?>,
    canDelete: (FeedRow) -> Boolean,
    onMuteUser: (String) -> MuteResult,
    onReport: (FeedRow, ReportType) -> Unit,
    onDelete: (FeedRow) -> Unit,
    relayProvenance: (String) -> List<RelayProvenanceItem> = { emptyList() },
    onDismiss: () -> Unit,
    showModerationActions: Boolean = true,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val showSnackbar = LocalShowSnackbar.current
    val openRelayDetail = LocalOpenRelayDetail.current
    var reportRow by remember { mutableStateOf<FeedRow?>(null) }
    var deleteRow by remember { mutableStateOf<FeedRow?>(null) }

    row?.let { activeRow ->
        val authorProfile = collectProfileAsState(activeRow.pubkey, profileFlow)
        key(activeRow.id) { PostActionsBottomSheet(
            authorPubkey = activeRow.pubkey,
            authorProfile = authorProfile,
            onDismiss = onDismiss,
            onCopyText = { clipboard.setText(AnnotatedString(activeRow.content)) },
            onCopyLink = {
                val nevent = NEvent.create(activeRow.id, null, null, null as NormalizedRelayUrl?)
                clipboard.setText(AnnotatedString("nostr:$nevent"))
                showSnackbar("Link copied")
            },
            onShare = {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "https://njump.me/${activeRow.id}")
                }
                context.startActivity(Intent.createChooser(shareIntent, null))
            },
            relayItemsProvider = { relayProvenance(activeRow.id) },
            onRelayClick = openRelayDetail,
            onMuteUser = {
                when (onMuteUser(activeRow.pubkey)) {
                    MuteResult.Queued -> showSnackbar("Muted")
                    MuteResult.PendingSync ->
                        showSnackbar("Muted locally — will sync when the mute list is ready")
                    MuteResult.Unavailable ->
                        showSnackbar("Mute unavailable — try again")
                }
            },
            onReport = {
                reportRow = activeRow
                onDismiss()
            },
            canDelete = canDelete(activeRow),
            onDelete = {
                deleteRow = activeRow
                onDismiss()
            },
            showModerationActions = showModerationActions,
        ) }
    }

    deleteRow?.let { activeRow ->
        AlertDialog(
            onDismissRequest = { deleteRow = null },
            title = { Text("Delete post?", color = Color.White) },
            text = { Text("This will publish a deletion request for this event.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(activeRow)
                    deleteRow = null
                    showSnackbar("Delete requested")
                }) {
                    Text("Delete", color = Zap)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteRow = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = Black,
        )
    }

    reportRow?.let { activeRow ->
        ReportTypeSheet(
            onDismiss = { reportRow = null },
            onTypeSelected = { type ->
                onReport(activeRow, type)
                showSnackbar("Report queued")
            },
        )
    }
}
