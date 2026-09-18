package com.unsilence.app.ui.shared

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import com.unsilence.app.data.memory.FeedRow
import kotlinx.coroutines.flow.first

internal fun restoredFeedIndex(ids: List<String>, anchor: String, fallback: Int): Int =
    ids.indexOf(anchor).takeIf { it >= 0 } ?: fallback.coerceAtLeast(0)

/** Do not spend the saved anchor on the empty/partial list emitted during restoration. */
internal fun pendingFeedRestoreIndex(
    ids: List<String>,
    anchor: String,
    fallback: Int,
    restorationReady: Boolean,
): Int? = if (!restorationReady || ids.isEmpty()) null else
    ids.indexOf(anchor).takeIf { it >= 0 } ?: fallback.coerceIn(0, ids.lastIndex)

private data class FeedScrollAnchor(val index: Int, val offset: Int, val eventId: String)
private class FeedScrollHolder(val list: LazyListState, var pending: FeedScrollAnchor? = null)

/** Save an event anchor as well as offset: live posts can arrive while the feed is covered. */
@Composable
internal fun rememberFeedScrollState(
    rows: List<FeedRow>,
    restorationReady: Boolean = true,
): LazyListState {
    val currentRows = rememberUpdatedState(rows)
    val saver = remember(currentRows) {
        listSaver<FeedScrollHolder, Any>(
            save = { holder ->
                val state = holder.list
                val pending = holder.pending
                listOf(
                    pending?.index ?: state.firstVisibleItemIndex,
                    pending?.offset ?: state.firstVisibleItemScrollOffset,
                    pending?.eventId ?: currentRows.value.getOrNull(state.firstVisibleItemIndex)?.id.orEmpty(),
                )
            },
            restore = { saved ->
                val anchor = FeedScrollAnchor(saved[0] as Int, saved[1] as Int, saved[2] as String)
                FeedScrollHolder(
                    list = LazyListState(
                        firstVisibleItemIndex = restoredFeedIndex(
                            currentRows.value.map { it.id }, anchor.eventId, anchor.index,
                        ),
                        firstVisibleItemScrollOffset = anchor.offset,
                    ),
                    pending = anchor,
                )
            },
        )
    }
    val holder = rememberSaveable(saver = saver) { FeedScrollHolder(LazyListState()) }
    LaunchedEffect(holder) {
        if (holder.pending != null) {
            snapshotFlow { holder.list.isScrollInProgress }.first { it }
            holder.pending = null
        }
    }
    LaunchedEffect(holder, rows, restorationReady) {
        val anchor = holder.pending ?: return@LaunchedEffect
        if (holder.list.isScrollInProgress) {
            // A deliberate new gesture wins over a delayed restore.
            holder.pending = null
            return@LaunchedEffect
        }
        val index = pendingFeedRestoreIndex(
            rows.map { it.id }, anchor.eventId, anchor.index, restorationReady,
        ) ?: return@LaunchedEffect
        holder.list.requestScrollToItem(index, anchor.offset)
        holder.pending = null
    }
    return holder.list
}
