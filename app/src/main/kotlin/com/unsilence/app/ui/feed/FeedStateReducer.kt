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
    private var lastAppendTime = 0L

    // Coalesce MERGE updates: fixed 200ms window batches rapid Room emissions.
    // First emission opens the window; subsequent emissions update pendingMerge
    // without resetting the timer. Dedup check runs ONCE per window in flushPending.
    // pendingMerge is written from background threads (collectLatest) and read from
    // the main-thread Handler callback — @Volatile + synchronized guard both paths.
    @Volatile
    private var pendingMerge: ReducerState? = null
    @Volatile
    private var coalesceWindowOpen = false
    private val coalesceHandler = Handler(Looper.getMainLooper())
    private val flushPending = Runnable {
        synchronized(this) {
            val pending = pendingMerge ?: run {
                coalesceWindowOpen = false
                return@Runnable
            }
            pendingMerge = null
            coalesceWindowOpen = false

            // Dedup check — only here, once per window. ID-only: profile/engagement
            // updates are cosmetic and flow reactively via Room Flows.
            val current = _state.value
            if (current.visibleEvents.size == pending.visibleEvents.size &&
                current.visibleEvents.indices.all { i -> current.visibleEvents[i].id == pending.visibleEvents[i].id }) {
                return@Runnable
            }
            _state.value = pending
        }
    }

    private fun emitCoalesced(newState: ReducerState) {
        synchronized(this) {
            pendingMerge = newState
            if (!coalesceWindowOpen) {
                coalesceWindowOpen = true
                coalesceHandler.postDelayed(flushPending, 200)
            }
            // If window already open: just update pendingMerge, don't reset timer
        }
    }

    /**
     * Called when the list scroll position changes.
     * Top = index 0 AND offset 0 (fully scrolled to the very top).
     */
    fun onScrollPositionChanged(firstVisibleIndex: Int, firstVisibleOffset: Int) {
        val wasAtTop = isAtTop
        isAtTop = firstVisibleIndex == 0 && firstVisibleOffset == 0
            && (System.currentTimeMillis() - lastAppendTime > 500)

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

        // Structural dedup: skip if event IDs AND data are unchanged.
        // Room re-emits on ANY joined-table write (profile resolve, engagement update,
        // relay provenance, etc.) — let engagement/profile data changes through.
        if (allEvents.size == current.visibleEvents.size) {
            val sameIds = allEvents.indices.all { i ->
                allEvents[i].id == current.visibleEvents[i].id
            }
            if (sameIds) {
                // IDs match — refresh data in-place (engagement counts, profile, etc.)
                if (allEvents != current.visibleEvents) {
                    _state.value = current.copy(visibleEvents = allEvents)
                }
                return
            }
        }

        if (current.visibleEvents.isEmpty() || isAtTop) {
            // MERGE path — safe: user is parked at top with no finger on screen.
            // Coalesced via fixed 200ms window in emitCoalesced.
            // Dedup check moved to flushPending (runs once per window, not per emission).
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
                    val existingIds = cur.visibleEvents.map { it.id }.toSet()
                    val appended = allEvents.filter { it.id !in existingIds }
                    // No new items — skip entirely. Data-only refreshes (engagement
                    // counts, profile pictures) are deferred to the next MERGE when
                    // the user scrolls back to top. This avoids rebuilding a 500+
                    // item list on every Room re-emission while scrolled down.
                    if (appended.isEmpty()) return@update cur
                    lastAppendTime = System.currentTimeMillis()
                    Log.d(TAG, "feedKey=$feedKey atTop=false action=APPEND count=${appended.size} total=${cur.visibleEvents.size + appended.size}")
                    cur.copy(visibleEvents = cur.visibleEvents + appended)
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
