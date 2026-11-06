package com.unsilence.app.ui.thread

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.unsilence.app.ui.shared.ReplyListItem

/** The thread entry owns its reading position, including across cover/return. */
@Composable
internal fun rememberThreadListState(
    eventId: String,
    state: ThreadUiState,
    threadItems: List<ReplyListItem>,
): LazyListState {
    val listState = rememberLazyListState()
    var didScrollToFocus by rememberSaveable(eventId) { mutableStateOf(false) }
    // Commit the requested position before the content list's first measure.
    // A launched scroll can draw the root for one frame while awaiting that layout.
    SideEffect {
        if (state.loading || didScrollToFocus) return@SideEffect
        val focusId = state.focusedReplyId ?: return@SideEffect
        val replyIdx = threadItems.indexOfFirst { item ->
            item is ReplyListItem.Reply && item.depthRow.row.id == focusId
        }
        if (replyIdx >= 0) {
            val leadingItems = (if (state.focusedNote != null) 1 else 0) +
                (if (state.replies.isNotEmpty()) 1 else 0)
            listState.requestScrollToItem(leadingItems + replyIdx)
            didScrollToFocus = true
        }
    }
    return listState
}
