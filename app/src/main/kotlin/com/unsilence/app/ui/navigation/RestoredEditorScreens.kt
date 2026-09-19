package com.unsilence.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.unsilence.app.data.drafts.Draft
import com.unsilence.app.data.memory.RelaySet
import com.unsilence.app.ui.common.LoadingScreen
import com.unsilence.app.ui.compose.ComposeScreen
import com.unsilence.app.ui.drafts.DraftsViewModel
import com.unsilence.app.ui.feed.NoteActionsViewModel
import com.unsilence.app.ui.relays.RelayManagementViewModel
import com.unsilence.app.ui.relays.RelaySetEditorScreen
import com.unsilence.app.ui.theme.Black

private data class ResolvedDraft(val draft: Draft?)
private data class ResolvedRelaySet(val relaySet: RelaySet?)

@Composable
internal fun ResumeDraftScreen(
    draftKey: String,
    onDismiss: () -> Unit,
    actions: NoteActionsViewModel,
    viewModel: DraftsViewModel = hiltViewModel(),
) {
    val resolved by produceState<ResolvedDraft?>(null, viewModel, draftKey) {
        value = ResolvedDraft(viewModel.resolveForResume(draftKey))
    }
    val result = resolved
    when {
        result == null -> LoadingScreen()
        result.draft == null -> MissingDestination("This draft is no longer available.", onDismiss)
        else -> ComposeScreen(
            initialDraft = result.draft,
            onDismiss = onDismiss,
            navigationOwnsBack = true,
            actionsViewModel = actions,
        )
    }
}

@Composable
internal fun EditRelaySetScreen(
    dTag: String,
    onDismiss: () -> Unit,
    viewModel: RelayManagementViewModel = hiltViewModel(),
) {
    val resolved by produceState<ResolvedRelaySet?>(null, viewModel, dTag) {
        value = ResolvedRelaySet(viewModel.resolveForEdit(dTag))
    }
    val result = resolved
    when {
        result == null -> LoadingScreen()
        result.relaySet == null -> MissingDestination("This relay set is no longer available.", onDismiss)
        else -> RelaySetEditorScreen(
            relaySet = result.relaySet,
            onDismiss = onDismiss,
            navigationOwnsBack = true,
            viewModel = viewModel,
        )
    }
}

@Composable
internal fun MissingDestination(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Black),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message)
        TextButton(onClick = onDismiss) { Text("Go back") }
    }
}
