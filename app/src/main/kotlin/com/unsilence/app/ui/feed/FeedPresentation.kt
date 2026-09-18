package com.unsilence.app.ui.feed

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.domain.model.FeedFilter

/** Seed identity travels with the events, never in an independently collected loading flag. */
internal data class FeedTimeline(
    val events: List<NostrEvent> = emptyList(),
    val source: FeedType? = null,
    val filter: FeedFilter? = null,
) {
    fun isSeededFor(type: FeedType, filter: FeedFilter): Boolean =
        source == type && this.filter == filter
}

internal data class FeedSelection(
    val source: SavedFeedSource,
    val filter: FeedFilter,
    val contentFilter: Int,
)

internal val SavedFeedSession.selection: FeedSelection
    get() = FeedSelection(source, filter, contentFilter)

/** One emission ties scroll restoration readiness to the actual moderated projection. */
internal data class FeedPresentation(
    val rows: List<FeedRow> = emptyList(),
    val selection: FeedSelection? = null,
    val restorationReady: Boolean = false,
)

/** A second Activity save during loading must not erase a still-pending restore. */
internal fun feedSessionForSave(
    current: SavedFeedSession,
    pending: SavedFeedSession?,
    presentation: FeedPresentation,
): SavedFeedSession = when {
    presentation.restorationReady && presentation.selection == current.selection ->
        current.copy(eventIds = presentation.rows.map { it.id })
    pending?.owner == current.owner && pending.selection == current.selection ->
        current.copy(eventIds = pending.eventIds)
    else -> current
}
