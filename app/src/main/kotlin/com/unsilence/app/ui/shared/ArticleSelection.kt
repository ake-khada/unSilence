package com.unsilence.app.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.ui.feed.NoteActionsViewModel
import kotlinx.coroutines.flow.flowOf

/** Save only the identity; resolve the row again after a covered entry or process is restored. */
@Composable
internal fun rememberArticleSelection(actions: NoteActionsViewModel): MutableState<FeedRow?> {
    val selectedId = rememberSaveable { mutableStateOf<String?>(null) }
    val clickedRow = remember { mutableStateOf<FeedRow?>(null) }
    val restoredFlow = remember(actions, selectedId.value) {
        selectedId.value?.let(actions::eventRowFlow) ?: flowOf(null)
    }
    val restored = restoredFlow.collectAsStateWithLifecycle(initialValue = null)
    return remember(selectedId, clickedRow, restored) {
        object : MutableState<FeedRow?> {
            override var value: FeedRow?
                get() = clickedRow.value?.takeIf { it.id == selectedId.value }
                    ?: restored.value?.takeIf { it.id == selectedId.value }
                set(row) {
                    clickedRow.value = row
                    selectedId.value = row?.id
                }
            override fun component1() = value
            override fun component2(): (FeedRow?) -> Unit = { value = it }
        }
    }
}
