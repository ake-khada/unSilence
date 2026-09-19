package com.unsilence.app.ui.shared

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.ui.common.LocalShowSnackbar

/** Ordinary back stays predictive; an already-started write finishes before entry disposal. */
@Composable
internal fun rememberWriteAwareDismiss(
    edits: PendingEdits,
    navigationOwnsBack: Boolean,
    onDismiss: () -> Unit,
): () -> Unit {
    val count by edits.count.collectAsStateWithLifecycle()
    var requested by rememberSaveable { mutableStateOf(false) }
    val dismissNow by rememberUpdatedState(onDismiss)
    val showSnackbar = LocalShowSnackbar.current
    val requestDismiss = {
        if (edits.count.value == 0) {
            requested = false
            dismissNow()
        } else if (!requested) {
            requested = true
            showSnackbar("Finishing changes…")
        }
    }
    BackHandler(enabled = !navigationOwnsBack || count > 0 || requested, onBack = requestDismiss)
    ResumedEffect(count, requested) {
        if (requested && count == 0) {
            requested = false
            dismissNow()
        }
    }
    return requestDismiss
}
