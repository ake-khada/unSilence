package com.unsilence.app.ui.shared

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import com.unsilence.app.data.memory.FeedRow

internal fun restoredFeedIndex(ids: List<String>, anchor: String, fallback: Int): Int =
    ids.indexOf(anchor).takeIf { it >= 0 } ?: fallback.coerceAtLeast(0)

/** Save an event anchor as well as offset: live posts can arrive while the feed is covered. */
@Composable
internal fun rememberFeedScrollState(rows: List<FeedRow>): LazyListState {
    val currentRows = rememberUpdatedState(rows)
    val saver = remember(currentRows) {
        listSaver<LazyListState, Any>(
            save = { state ->
                listOf(
                    state.firstVisibleItemIndex,
                    state.firstVisibleItemScrollOffset,
                    currentRows.value.getOrNull(state.firstVisibleItemIndex)?.id.orEmpty(),
                )
            },
            restore = { saved ->
                LazyListState(
                    firstVisibleItemIndex = restoredFeedIndex(
                        currentRows.value.map { it.id }, saved[2] as String, saved[0] as Int,
                    ),
                    firstVisibleItemScrollOffset = saved[1] as Int,
                )
            },
        )
    }
    return rememberSaveable(saver = saver) { LazyListState() }
}
