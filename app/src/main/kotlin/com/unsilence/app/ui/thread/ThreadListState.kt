package com.unsilence.app.ui.thread

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.unsilence.app.ui.shared.ReplyListItem
import kotlinx.coroutines.flow.first

/** The thread entry owns its reading position, including across cover/return. */
@Composable
internal fun rememberThreadListState(
    eventId: String,
    state: ThreadUiState,
    threadItems: List<ReplyListItem>,
): LazyListState {
    val listState = rememberLazyListState()
    var focusHandled by rememberSaveable(eventId) { mutableStateOf(false) }
    LaunchedEffect(eventId, focusHandled) {
        if (!focusHandled) {
            // A reader's scroll wins over focus that is still waiting for a reply.
            // Like feed restoration, this watcher finishes after the first scroll.
            snapshotFlow { listState.isScrollInProgress }.first { it }
            focusHandled = true
        }
    }
    // Commit the requested position before the content list's first measure.
    // A launched scroll can draw the root for one frame while awaiting that layout.
    SideEffect {
        if (state.loading || focusHandled) return@SideEffect
        // Cover arrival in the same frame as a gesture, before the watcher resumes.
        if (listState.isScrollInProgress) {
            focusHandled = true
            return@SideEffect
        }
        val focusId = state.focusedReplyId ?: return@SideEffect
        val replyIdx = threadItems.indexOfFirst { item ->
            item is ReplyListItem.Reply && item.depthRow.row.id == focusId
        }
        if (replyIdx >= 0) {
            val leadingItems = (if (state.focusedNote != null) 1 else 0) +
                (if (state.replies.isNotEmpty()) 1 else 0)
            listState.requestScrollToItem(leadingItems + replyIdx)
            focusHandled = true
        }
    }
    return listState
}
