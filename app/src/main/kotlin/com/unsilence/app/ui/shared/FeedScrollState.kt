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

private const val NEIGHBOR_RADIUS = 3

/** Closest saved position first; ties prefer the next (older) note. Six IDs at most. */
internal fun feedAnchorNeighbors(size: Int, index: Int, idAt: (Int) -> String): List<String> =
    if (index !in 0 until size) emptyList() else buildList {
        for (distance in 1..NEIGHBOR_RADIUS) {
            if (index + distance < size) add(idAt(index + distance))
            if (index - distance >= 0) add(idAt(index - distance))
        }
    }

private fun matchingFeedIndex(ids: List<String>, anchor: String, neighbors: List<String>): Int? {
    ids.indexOf(anchor).takeIf { it >= 0 }?.let { return it }
    for (neighbor in neighbors) ids.indexOf(neighbor).takeIf { it >= 0 }?.let { return it }
    return null
}

internal fun restoredFeedIndex(
    ids: List<String>, anchor: String, fallback: Int, neighbors: List<String> = emptyList(),
): Int = matchingFeedIndex(ids, anchor, neighbors) ?: fallback.coerceAtLeast(0)

/** Do not spend the saved anchor on the empty/partial list emitted during restoration. */
internal fun pendingFeedRestoreIndex(
    ids: List<String>,
    anchor: String,
    fallback: Int,
    restorationReady: Boolean,
    neighbors: List<String> = emptyList(),
): Int? = if (!restorationReady || ids.isEmpty()) null else
    matchingFeedIndex(ids, anchor, neighbors) ?: fallback.coerceIn(0, ids.lastIndex)

internal data class FeedScrollAnchor(
    val index: Int,
    val offset: Int,
    val eventId: String,
    val neighbors: List<String> = emptyList(),
) {
    fun save(): List<Any> = listOf(index, offset, eventId) + neighbors.take(NEIGHBOR_RADIUS * 2)

    companion object {
        /** Existing three-field saves remain valid; never retain an unbounded list. */
        fun restore(saved: List<Any>) = FeedScrollAnchor(
            saved[0] as Int, saved[1] as Int, saved[2] as String,
            saved.drop(3).take(NEIGHBOR_RADIUS * 2).map { it as String },
        )
    }
}
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
                val index = state.firstVisibleItemIndex
                (pending ?: FeedScrollAnchor(
                    index, state.firstVisibleItemScrollOffset,
                    currentRows.value.getOrNull(index)?.id.orEmpty(),
                    feedAnchorNeighbors(currentRows.value.size, index) { currentRows.value[it].id },
                )).save()
            },
            restore = { saved ->
                val anchor = FeedScrollAnchor.restore(saved)
                FeedScrollHolder(
                    list = LazyListState(
                        firstVisibleItemIndex = restoredFeedIndex(
                            currentRows.value.map { it.id }, anchor.eventId, anchor.index, anchor.neighbors,
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
            rows.map { it.id }, anchor.eventId, anchor.index, restorationReady, anchor.neighbors,
        ) ?: return@LaunchedEffect
        holder.list.requestScrollToItem(index, anchor.offset)
        holder.pending = null
    }
    return holder.list
}
