package com.unsilence.app.ui.shared

import com.unsilence.app.data.relay.ENGAGEMENT_LOOKAHEAD
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.ui.feed.activityStatsTargetId
import com.unsilence.app.ui.feed.engagementId
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Navigation supplies only the incoming top surface, independently of network readiness. */
internal val LocalDisplayWindowActive = compositionLocalOf<Boolean?> { null }

@Composable
internal fun ObserveDisplayWindowLifecycle(reporter: DisplayWindowReporter) {
    val active = LocalDisplayWindowActive.current
    if (active == null) {
        // Standalone hosts without the application's navigation policy.
        LifecycleResumeEffect(reporter) {
            reporter.setActive(true)
            onPauseOrDispose { reporter.setActive(false) }
        }
    } else {
        // Applied before drawing the incoming composition; no network work here.
        SideEffect { reporter.setActive(active) }
        DisposableEffect(reporter) {
            onDispose { reporter.setActive(false) }
        }
    }
}

internal const val DISPLAY_WINDOW_ABOVE = 2

/** One retained surface's bounded visible + nearby IDs. No payloads or network work. */
internal class DisplayWindowReporter(private val store: MemoryEventStore) : AutoCloseable {
    private var active = false
    private var closed = false
    private val window = store.openDisplayWindow()
    private var rows: List<Pair<String, String>> = emptyList()
    private var visible: Set<String> = emptySet()

    @Synchronized
    fun setActive(value: Boolean) {
        if (closed || active == value) return
        active = value
        if (value) window.activate(rows, visible) else window.setActive(false)
    }

    @Synchronized
    override fun close() {
        closed = true
        active = false
        rows = emptyList()
        visible = emptySet()
        window.close()
    }

    fun clear() = report(emptyList(), emptySet())

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
        if (closed || !active) return
        // Visible rows always win the bound; callers provide nearby rows in proximity order.
        val ordered = (rows.filter { it.id in visibleIds } + rows.filterNot { it.id in visibleIds })
            .distinctBy { it.id }.take(com.unsilence.app.data.memory.DISPLAY_WINDOW_ROW_LIMIT)
        this.rows = ordered.map { row ->
            row.id to (store.getEventModel(row.id)?.engagementId
                ?: activityStatsTargetId(row.kind, row.id, row.rootId) ?: row.id)
        }
        visible = visibleIds.intersect(ordered.mapTo(HashSet()) { it.id })
        window.update(this.rows, visible)
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
