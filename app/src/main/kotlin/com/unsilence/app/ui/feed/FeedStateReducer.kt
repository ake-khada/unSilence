package com.unsilence.app.ui.feed

import android.util.Log
import com.unsilence.app.data.db.dao.FeedRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "FeedState"

data class ReducerState(
    val visibleEvents: List<FeedRow> = emptyList(),
    val unreadCount: Int = 0,
    val showDot: Boolean = false,
)

/**
 * Per-feed-key state machine for new-notes gating.
 *
 * When the user is at the top of the list, new events merge immediately.
 * When scrolled down, new events are counted but the visible list stays
 * stable. Tapping the blue dot or scrolling back to top flushes [latestRows].
 *
 * Key invariant: [latestRows] always holds the most recent Room emission.
 * Flush always replaces visibleEvents with latestRows — never reconstructs
 * from pending+visible.
 */
class FeedStateReducer(private val feedKey: String) {

    private val _state = MutableStateFlow(ReducerState())
    val state: StateFlow<ReducerState> = _state.asStateFlow()

    /** Most recent Room emission — flush always uses this. */
    private var latestRows: List<FeedRow> = emptyList()

    /** IDs already counted as pending — prevents double-counting on repeated Room emissions. */
    private var pendingIds: Set<String> = emptySet()

    private var isAtTop: Boolean = true

    /** Timestamp of last PAGINATE action — suppresses false isAtTop for 1s after. */
    private var lastPaginateTime = 0L

    /**
     * Called when the list scroll position changes.
     * Top = index 0 AND offset 0 (fully scrolled to the very top).
     */
    fun onScrollPositionChanged(firstVisibleIndex: Int, firstVisibleOffset: Int) {
        val wasAtTop = isAtTop
        isAtTop = firstVisibleIndex == 0 && firstVisibleOffset == 0
            && (System.currentTimeMillis() - lastPaginateTime > 1000)

        if (isAtTop && !wasAtTop && _state.value.showDot) {
            flush("TOP_REACHED")
        }
    }

    /**
     * Called when Room emits a new list of events for this feed.
     *
     * Always stores into [latestRows]. Uses [takeWhile] to count only
     * LEADING unseen rows — stops at the first known ID so pagination
     * appends are never miscounted as new posts.
     */
    fun onNewEvents(allEvents: List<FeedRow>) {
        latestRows = allEvents

        _state.update { current ->
            if (current.visibleEvents.isEmpty() || isAtTop) {
                // Skip if same event IDs in same order (avoids recomposition on engagement-only updates)
                if (current.visibleEvents.size == allEvents.size &&
                    current.visibleEvents.indices.all { i -> current.visibleEvents[i].id == allEvents[i].id }) {
                    // Same IDs — check if any content actually changed
                    if (current.visibleEvents == allEvents) {
                        return@update current  // Identical — skip entirely
                    }
                }
                pendingIds = emptySet()
                Log.d(TAG, "feedKey=$feedKey atTop=$isAtTop action=MERGE count=${allEvents.size}")
                ReducerState(visibleEvents = allEvents)
            } else {
                // User is scrolled down — count leading new rows
                val allIds = allEvents.map { it.id }.toSet()
                pendingIds = pendingIds.intersect(allIds)  // Drop IDs no longer in Room results
                val visibleIds = current.visibleEvents.map { it.id }.toSet()
                val knownIds = visibleIds + pendingIds
                val leadingNew = allEvents.takeWhile { it.id !in knownIds }

                if (leadingNew.isEmpty()) {
                    if (allEvents.size > current.visibleEvents.size) {
                        // Pagination: visible events are a prefix, new rows appended at bottom.
                        // Merge immediately so the user can keep scrolling.
                        Log.d(TAG, "feedKey=$feedKey atTop=false action=PAGINATE old=${current.visibleEvents.size} new=${allEvents.size}")
                        pendingIds = emptySet()
                        lastPaginateTime = System.currentTimeMillis()
                        current.copy(visibleEvents = allEvents)
                    } else {
                        // Same size or smaller — refresh engagement counts in-place
                        val latestMap = allEvents.associateBy { it.id }
                        val refreshed = current.visibleEvents.map { row -> latestMap[row.id] ?: row }
                        current.copy(visibleEvents = refreshed)
                    }
                } else {
                    pendingIds = pendingIds + leadingNew.map { it.id }.toSet()
                    val unread = pendingIds.size
                    Log.d(TAG, "feedKey=$feedKey atTop=false action=QUEUE leading=${leadingNew.size} total=$unread")
                    current.copy(
                        unreadCount = unread,
                        showDot = true,
                    )
                }
            }
        }
    }

    /** User tapped the blue dot — flush latestRows into visible. */
    fun onDotTapped() {
        if (!_state.value.showDot) return
        flush("DOT_TAP")
    }

    private fun flush(reason: String) {
        val rows = latestRows
        if (rows.isEmpty()) return
        pendingIds = emptySet()
        Log.d(TAG, "flush feedKey=$feedKey count=${rows.size} reason=$reason")
        _state.value = ReducerState(visibleEvents = rows)
        isAtTop = true
    }
}
