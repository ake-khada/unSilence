package com.unsilence.app.ui.feed

import android.os.Handler
import android.os.Looper
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
    private var atTopConsecutiveCount = 0

    // Coalesce MERGE updates: collect rapid Room emissions for 150ms before
    // committing to _state. Prevents 93+ MERGEs/session from causing frame skips.
    // pendingMerge is written from background threads (collectLatest) and read from
    // the main-thread Handler callback — @Volatile + synchronized guard both paths.
    @Volatile
    private var pendingMerge: ReducerState? = null
    private val coalesceHandler = Handler(Looper.getMainLooper())
    private val flushPending = Runnable {
        synchronized(this) {
            pendingMerge?.let { _state.value = it }
            pendingMerge = null
        }
    }

    private fun emitCoalesced(newState: ReducerState) {
        synchronized(this) {
            pendingMerge = newState
        }
        coalesceHandler.removeCallbacks(flushPending)
        coalesceHandler.postDelayed(flushPending, 150)
    }

    /**
     * Called when the list scroll position changes.
     * Top = index 0 AND offset 0 (fully scrolled to the very top).
     * Requires 2+ consecutive (0,0) reports to confirm truly at top —
     * prevents false positive from video layout shift.
     */
    fun onScrollPositionChanged(firstVisibleIndex: Int, firstVisibleOffset: Int) {
        val wasAtTop = isAtTop
        val atTopNow = firstVisibleIndex == 0 && firstVisibleOffset == 0

        if (atTopNow) {
            atTopConsecutiveCount++
        } else {
            atTopConsecutiveCount = 0
        }

        isAtTop = atTopConsecutiveCount >= 2
        Log.d(TAG, "scroll: idx=$firstVisibleIndex off=$firstVisibleOffset consecutive=$atTopConsecutiveCount isAtTop=$isAtTop")

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

        val current = _state.value
        if (current.visibleEvents.isEmpty() || isAtTop) {
            // MERGE path — coalesced via Handler to batch rapid Room emissions.
            // Skip if same event IDs in same order (avoids recomposition on engagement-only updates)
            if (current.visibleEvents.size == allEvents.size &&
                current.visibleEvents.indices.all { i -> current.visibleEvents[i].id == allEvents[i].id }) {
                if (current.visibleEvents == allEvents) return
            }
            pendingIds = emptySet()
            Log.d(TAG, "feedKey=$feedKey atTop=$isAtTop action=MERGE count=${allEvents.size}")
            emitCoalesced(ReducerState(visibleEvents = allEvents))
        } else {
            // User is scrolled down — APPEND/QUEUE paths use atomic _state.update
            _state.update { cur ->
                val allIds = allEvents.map { it.id }.toSet()
                pendingIds = pendingIds.intersect(allIds)
                val visibleIds = cur.visibleEvents.map { it.id }.toSet()
                val knownIds = visibleIds + pendingIds
                val leadingNew = allEvents.takeWhile { it.id !in knownIds }

                if (leadingNew.isEmpty()) {
                    val latestMap = allEvents.associateBy { it.id }
                    val refreshed = cur.visibleEvents.map { row -> latestMap[row.id] ?: row }
                    val existingIds = cur.visibleEvents.map { it.id }.toSet()
                    val appended = allEvents.filter { it.id !in existingIds }
                    if (appended.isNotEmpty()) {
                        Log.d(TAG, "feedKey=$feedKey atTop=false action=APPEND count=${appended.size} total=${refreshed.size + appended.size}")
                    }
                    cur.copy(visibleEvents = refreshed + appended)
                } else {
                    pendingIds = pendingIds + leadingNew.map { it.id }.toSet()
                    val unread = pendingIds.size
                    Log.d(TAG, "feedKey=$feedKey atTop=false action=QUEUE leading=${leadingNew.size} total=$unread")
                    cur.copy(
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
