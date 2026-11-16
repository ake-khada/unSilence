package com.unsilence.app.ui.feed

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.ui.shared.DISPLAY_WINDOW_ABOVE
import com.unsilence.app.ui.shared.protectedDisplayRows
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

internal data class FeedCardViewport(
    val first: Int,
    val last: Int,
    val cardWidthPx: Int,
    val visibleIds: Set<String> = emptySet(),
)

/** Feed engagement selection, separated from the wider media warm-up. */
internal fun feedEngagementRows(
    rows: List<FeedRow>, viewport: FeedCardViewport, maxRowsBelowFirst: Int = 50,
    protectedRows: List<FeedRow> = protectedDisplayRows(rows, viewport.first, viewport.last, viewport.visibleIds),
): List<FeedRow> {
    if (rows.isEmpty()) return emptyList()
    val visibleFirst = rows.indexOfFirst { it.id in viewport.visibleIds }
    val first = if (visibleFirst >= 0) visibleFirst else viewport.first.coerceIn(0, rows.lastIndex)
    val aboveIds = (1..DISPLAY_WINDOW_ABOVE).mapNotNull { rows.getOrNull(first - it)?.id }.toSet()
    return protectedRows.filterNot { it.id in aboveIds }.take(maxRowsBelowFirst)
}

/** One viewport owner publishes protection before starting its warm-up or retries.
 * The caller's collectLatest cancels these children before publishing another window.
 * Already dispatched relay requests retain their existing bounded lifetimes.
 */
@OptIn(FlowPreview::class)
internal suspend fun observeFeedCardWindow(
    rows: List<FeedRow>,
    viewport: FeedCardViewport,
    maxRowsBelowFirst: Int,
    retryRevisions: Flow<Long>,
    report: (List<FeedRow>, Set<String>) -> Unit,
    warmAssets: suspend () -> Unit,
    hydrate: suspend (List<FeedRow>) -> Unit,
) = coroutineScope {
    val protected = protectedDisplayRows(rows, viewport.first, viewport.last, viewport.visibleIds)
    report(protected, viewport.visibleIds)
    if (rows.isEmpty()) return@coroutineScope
    val engagementRows = feedEngagementRows(rows, viewport, maxRowsBelowFirst, protected)
    launch { warmAssets() }
    retryRevisions.debounce(300L).collectLatest { hydrate(engagementRows) }
}
