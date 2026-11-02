package com.unsilence.app.ui.shared

import com.unsilence.app.data.relay.ENGAGEMENT_LOOKAHEAD
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.ui.feed.activityStatsTargetId
import com.unsilence.app.ui.feed.engagementId
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.unsilence.app.ui.feed.NoteActionsViewModel

@Composable
internal fun ObserveDisplayWindowLifecycle(actions: NoteActionsViewModel) {
    LifecycleResumeEffect(actions) {
        actions.setDisplayWindowActive(true)
        onPauseOrDispose { actions.setDisplayWindowActive(false) }
    }
}

internal const val DISPLAY_WINDOW_ABOVE = 2

/** One resumed surface's bounded visible + nearby window. No network work here. */
internal class DisplayWindowReporter(private val store: MemoryEventStore) {
    private var active = false
    private var window: MemoryEventStore.DisplayWindow? = null

    @Synchronized
    fun setActive(value: Boolean) {
        active = value
        if (!value) { window?.close(); window = null }
    }

    /** Called before any width/network gate by both feed and non-feed warm paths. */
    fun prepareWarmWindow(
        rows: List<FeedRow>, first: Int, last: Int, visibleIds: Set<String>,
        cardWidthPx: Int, contextRows: List<FeedRow> = emptyList(),
    ): Boolean {
        report(protectedDisplayRows(rows, first, last, visibleIds, contextRows), visibleIds)
        return rows.isNotEmpty() && cardWidthPx > 0
    }

    @Synchronized
    fun report(rows: List<FeedRow>, visibleIds: Set<String>) {
        if (!active) return
        if (rows.isEmpty()) { window?.close(); window = null; return }
        val current = window ?: store.openDisplayWindow().also { window = it }
        // Visible rows always win the bound; callers provide nearby rows in proximity order.
        val ordered = (rows.filter { it.id in visibleIds } + rows.filterNot { it.id in visibleIds })
            .distinctBy { it.id }.take(com.unsilence.app.data.memory.DISPLAY_WINDOW_ROW_LIMIT)
        current.update(ordered.map { row ->
            row.id to (store.getEventModel(row.id)?.engagementId
                ?: activityStatsTargetId(row.kind, row.id, row.rootId) ?: row.id)
        }, visibleIds)
    }
}

/** Two rows above and six below, reusing the non-feed engagement window sizes. */
internal fun protectedDisplayRows(
    rows: List<FeedRow>, first: Int, last: Int, visibleIds: Set<String>,
    contextRows: List<FeedRow> = emptyList(),
): List<FeedRow> {
    if (rows.isEmpty()) return contextRows.filter { it.id in visibleIds }
    // Asset hydration callers may pass a wider range (notifications/articles).
    // Visible IDs also survive a prepend where the sampled indices are stale.
    val visibleStart = rows.indexOfFirst { it.id in visibleIds }
    val start = if (visibleStart >= 0) visibleStart else first.coerceIn(0, rows.lastIndex)
    val end = if (visibleStart >= 0) rows.indexOfLast { it.id in visibleIds } else last.coerceIn(start, rows.lastIndex)
    return buildList {
        addAll(contextRows.filter { it.id in visibleIds })
        addAll(rows.subList(start, end + 1).filter { it.id in visibleIds })
        addAll(rows.subList(start, end + 1).filterNot { it.id in visibleIds })
        // Interleave closest neighbors so a pathological large viewport cannot
        // spend the remaining budget on distant warm assets.
        for (distance in 1..ENGAGEMENT_LOOKAHEAD) {
            if (distance <= DISPLAY_WINDOW_ABOVE) rows.getOrNull(start - distance)?.let(::add)
            rows.getOrNull(end + distance)?.let(::add)
        }
    }.distinctBy { it.id }.take(com.unsilence.app.data.memory.DISPLAY_WINDOW_ROW_LIMIT)
}
