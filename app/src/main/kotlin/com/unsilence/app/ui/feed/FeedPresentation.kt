package com.unsilence.app.ui.feed

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.NostrEvent
import androidx.collection.LruCache
import com.unsilence.app.domain.model.FeedFilter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Seed identity travels with the events, never in an independently collected loading flag. */
internal data class FeedTimeline(
    val events: List<NostrEvent> = emptyList(),
    val source: FeedType? = null,
    val filter: FeedFilter? = null,
    val paging: FeedPagingWindow = FeedPagingWindow(),
) {
    fun isSeededFor(type: FeedType, filter: FeedFilter): Boolean =
        source == type && this.filter == filter

    fun merge(incoming: List<NostrEvent>): FeedTimeline =
        copy(events = paging.merge(events, incoming))

    fun resetPaging(): FeedTimeline = paging.reset().let { reset ->
        copy(events = events.take(reset.rawLimit), paging = reset)
    }
}

@Serializable
internal data class FeedSelection(
    val source: SavedFeedSource,
    val filter: FeedFilter,
    val contentFilter: Int,
)

/** Labels and Set iteration order are not feed identity. Save only selection metadata. */
internal fun FeedSelection.scrollRestorationScope(): String = Json.encodeToString(
    copy(
        source = source.copy(label = ""),
        filter = filter.copy(showTypes = filter.showTypes.sortedBy { it.ordinal }.toSet()),
    ),
)

internal val SavedFeedSession.selection: FeedSelection
    get() = FeedSelection(source, filter, contentFilter)

/** One emission ties scroll restoration readiness to the actual moderated projection. */
internal data class FeedPresentation(
    val rows: List<FeedRow> = emptyList(),
    val selection: FeedSelection? = null,
    val restorationReady: Boolean = false,
    val paging: FeedPagingWindow = FeedPagingWindow(),
    val nextBufferedEnd: FeedPageCursor? = null,
)

/** The displayed window may exceed the LRU capacity. Assemble from a local
 * result map, never by reading every row back after filling/evicting the LRU. */
internal fun resolveFeedRows(
    events: List<NostrEvent>,
    cache: LruCache<String, FeedRow>,
    load: (Set<String>) -> List<FeedRow>,
    synthesize: (NostrEvent) -> FeedRow,
    previousRows: List<FeedRow> = emptyList(),
): List<FeedRow> {
    val resolved = HashMap<String, FeedRow>(events.size)
    // These rows are already retained by the displayed StateFlow. Reuse them
    // so a 2,000-row pass cannot churn the smaller, cross-feed LRU every signal.
    for (row in previousRows) resolved[row.id] = row
    val missing = HashSet<String>()
    for (event in events) {
        if (event.id in resolved) continue
        val row = cache[event.id]
        if (row != null) resolved[event.id] = row else missing.add(event.id)
    }
    if (missing.isNotEmpty()) {
        for (row in load(missing)) {
            resolved[row.id] = row
            cache.put(row.id, row)
        }
    }
    return events.map { event ->
        resolved[event.id] ?: synthesize(event).also { cache.put(event.id, it) }
    }
}

/** A second Activity save during loading must not erase a still-pending restore. */
internal fun feedSessionForSave(
    current: SavedFeedSession,
    pending: SavedFeedSession?,
    presentation: FeedPresentation,
    firstVisibleIndex: Int = 0,
): SavedFeedSession = when {
    presentation.restorationReady && presentation.selection == current.selection ->
        current.copy(eventIds = savedFeedNeighborhood(presentation.rows,
                if (current.isAtTop) 0 else firstVisibleIndex),
            pagingPages = presentation.paging.pages, pagingThrough = presentation.paging.through)
    pending?.owner == current.owner && pending.selection == current.selection ->
        current.copy(eventIds = pending.eventIds,
            pagingPages = pending.pagingPages, pagingThrough = pending.pagingThrough)
    else -> current
}

/** Keep the reading anchor and nearby fallbacks, even in a deep expanded window. */
private fun savedFeedNeighborhood(rows: List<FeedRow>, firstVisibleIndex: Int): List<String> {
    val first = firstVisibleIndex.coerceIn(0, rows.lastIndex.coerceAtLeast(0))
    val start = (first - SAVED_FEED_EVENT_LIMIT / 2)
        .coerceIn(0, (rows.size - SAVED_FEED_EVENT_LIMIT).coerceAtLeast(0))
    return rows.subList(start, minOf(rows.size, start + SAVED_FEED_EVENT_LIMIT)).map { it.id }
}
