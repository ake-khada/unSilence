package com.unsilence.app.ui.feed

import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.relay.TimelineMerge
import kotlinx.serialization.Serializable

/** Same timestamp/id ordering as TimelineMerge; does not retain an event body. */
@Serializable
internal data class FeedPageCursor(val createdAt: Long, val id: String) {
    fun includes(event: NostrEvent): Boolean =
        event.createdAt > createdAt || (event.createdAt == createdAt && event.id <= id)
}

internal fun NostrEvent.pageCursor() = FeedPageCursor(createdAt, id)

/** User paging owns the extra capacity. Background batches cannot reset it or
 * evict existing rows from a paged reading session. A reset invalidates requests
 * even when the subscription handle and events have not changed. */
internal data class FeedPagingWindow(
    val pages: Int = 0,
    val through: FeedPageCursor? = null,
    val generation: Long = 0,
) {
    init { require(pages in 0..MAX_PAGES) }

    val displayLimit: Int get() = BASE_DISPLAY_LIMIT + pages * PAGE_SIZE
    val rawLimit: Int get() = TimelineMerge.EVENTS_CAP + pages * PAGE_SIZE
    val canLoadMore: Boolean get() = pages < MAX_PAGES

    fun advance(end: FeedPageCursor): FeedPagingWindow {
        check(canLoadMore)
        return copy(pages = pages + 1, through = end)
    }

    fun reset() = FeedPagingWindow(generation = generation + 1)

    /** Candidates have already passed ALL policy/activity filters, newest first.
     * Once paged, anchor the bottom by identity/time, not its changing index:
     * new replay rows above it must not push that bottom out of the projection. */
    fun project(candidates: List<NostrEvent>, previousRows: List<FeedRow> = emptyList()): FeedPageProjection {
        val end = through?.let { cursor ->
            candidates.indexOfFirst { !cursor.includes(it) }.takeIf { it >= 0 } ?: candidates.size
        } ?: minOf(displayLimit, candidates.size)
        val start = (end - displayLimit).coerceAtLeast(0)
        val nextEnd = minOf(candidates.size, end + PAGE_SIZE)
        val eligible = candidates.subList(0, end)
        val displayed = if (start == 0 || previousRows.isEmpty() || pages == 0) {
            candidates.subList(start, end).toList()
        } else {
            // Preserve the entire still-allowed reading window, including when
            // the reader has scrolled upward again. Background inserts must not
            // evict their current row from either end of a full projection.
            val previousIds = previousRows.mapTo(HashSet()) { it.id }
            val chosen = eligible.filter { it.id in previousIds }.mapTo(HashSet()) { it.id }
            val oldBottom = previousRows.last().let { FeedPageCursor(it.createdAt, it.id) }
            // An explicit page spends its extra slots on OLDER rows first, not
            // deferred replay rows above the reader (which would stall the bottom).
            for (event in eligible) {
                if (chosen.size >= displayLimit) break
                if (!oldBottom.includes(event)) chosen.add(event.id)
            }
            for (event in eligible) {
                if (chosen.size >= displayLimit) break
                chosen.add(event.id)
            }
            eligible.filter { it.id in chosen }
        }
        return FeedPageProjection(
            events = displayed,
            nextBufferedEnd = if (canLoadMore && nextEnd > end) candidates[nextEnd - 1].pageCursor() else null,
        )
    }

    fun merge(current: List<NostrEvent>, incoming: List<NostrEvent>): List<NostrEvent> {
        val merged = TimelineMerge.merge(current, incoming, capTail = false)
        if (merged.size <= rawLimit) return merged
        if (pages == 0) return merged.take(rawLimit)

        // Preserve every existing row while reading a paged window. Use spare
        // capacity for background recovery; overflow remains in the service/MES,
        // not an additional unbounded ViewModel buffer.
        val retainedIds = current.mapTo(HashSet(current.size)) { it.id }
        var spare = (rawLimit - current.size).coerceAtLeast(0)
        return merged.filter { event ->
            if (event.id in retainedIds) true
            else if (spare > 0) { spare--; true }
            else false
        }
    }

    companion object {
        const val BASE_DISPLAY_LIMIT = 500
        const val PAGE_SIZE = 100
        const val MAX_DISPLAY_LIMIT = 2_000
        const val MAX_PAGES = (MAX_DISPLAY_LIMIT - BASE_DISPLAY_LIMIT) / PAGE_SIZE
        const val MAX_RAW_LIMIT = TimelineMerge.EVENTS_CAP + MAX_PAGES * PAGE_SIZE
    }
}

internal data class FeedPageProjection(
    val events: List<NostrEvent>,
    val nextBufferedEnd: FeedPageCursor?,
)
