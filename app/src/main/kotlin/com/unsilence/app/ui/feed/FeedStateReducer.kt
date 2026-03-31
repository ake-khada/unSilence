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
    val pendingEvents: List<FeedRow> = emptyList(),
    val unreadCount: Int = 0,
    val showDot: Boolean = false,
)

/**
 * Per-feed-key state machine for new-notes gating.
 *
 * When the user is at the top of the list, new events merge immediately.
 * When scrolled down, new events queue behind a blue dot. Tapping the
 * dot or scrolling back to top flushes pending events.
 */
class FeedStateReducer(private val feedKey: String) {

    private val _state = MutableStateFlow(ReducerState())
    val state: StateFlow<ReducerState> = _state.asStateFlow()

    var isAtTop: Boolean = true
        private set

    /**
     * Called when the list scroll position changes.
     * If user scrolled back to top and there are pending events, auto-flush.
     */
    fun onScrollPositionChanged(firstVisibleIndex: Int) {
        val wasAtTop = isAtTop
        isAtTop = firstVisibleIndex <= 1

        if (isAtTop && !wasAtTop && _state.value.pendingEvents.isNotEmpty()) {
            flush("TOP_REACHED")
        }
    }

    /**
     * Called when Room emits a new list of events for this feed.
     * Determines whether to merge immediately or queue.
     */
    fun onNewEvents(allEvents: List<FeedRow>) {
        _state.update { current ->
            if (current.visibleEvents.isEmpty()) {
                // Initial load — show everything
                Log.d(TAG, "incoming feedKey=$feedKey atTop=$isAtTop action=MERGE count=${allEvents.size}")
                ReducerState(visibleEvents = allEvents)
            } else if (isAtTop) {
                // User is at top — merge immediately
                Log.d(TAG, "incoming feedKey=$feedKey atTop=true action=MERGE count=${allEvents.size}")
                current.copy(
                    visibleEvents = allEvents,
                    pendingEvents = emptyList(),
                    unreadCount = 0,
                    showDot = false,
                )
            } else {
                // User is scrolled down — find new events and queue them
                val visibleIds = current.visibleEvents.map { it.id }.toSet()
                val newEvents = allEvents.filter { it.id !in visibleIds }
                if (newEvents.isEmpty()) {
                    // No new events, just updated engagement counts etc.
                    current.copy(visibleEvents = refreshVisible(current.visibleEvents, allEvents))
                } else {
                    val pending = (newEvents + current.pendingEvents).distinctBy { it.id }
                    val unread = pending.size
                    Log.d(TAG, "incoming feedKey=$feedKey atTop=false action=QUEUE count=${newEvents.size}")
                    Log.d(TAG, "dot feedKey=$feedKey unreadCount=$unread visible=true")
                    current.copy(
                        pendingEvents = pending,
                        unreadCount = unread,
                        showDot = true,
                        // Keep visible list stable — only update engagement counts
                        visibleEvents = refreshVisible(current.visibleEvents, allEvents),
                    )
                }
            }
        }
    }

    /** User tapped the blue dot — flush all pending into visible. */
    fun onDotTapped() {
        flush("DOT_TAP")
    }

    /** Reset on feed switch. */
    fun reset() {
        isAtTop = true
        _state.value = ReducerState()
    }

    private fun flush(reason: String) {
        _state.update { current ->
            val merged = (current.pendingEvents + current.visibleEvents)
                .distinctBy { it.id }
                .sortedByDescending { it.createdAt }
            Log.d(TAG, "flush feedKey=$feedKey pending=${current.pendingEvents.size} reason=$reason")
            current.copy(
                visibleEvents = merged,
                pendingEvents = emptyList(),
                unreadCount = 0,
                showDot = false,
            )
        }
        isAtTop = true
    }

    /**
     * Update engagement counts / author info on visible events without reordering.
     * Preserves the user's scroll position by keeping the same order.
     */
    private fun refreshVisible(
        visible: List<FeedRow>,
        latest: List<FeedRow>,
    ): List<FeedRow> {
        val latestMap = latest.associateBy { it.id }
        return visible.map { row -> latestMap[row.id] ?: row }
    }
}
