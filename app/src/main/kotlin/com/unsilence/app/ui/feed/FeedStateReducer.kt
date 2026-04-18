package com.unsilence.app.ui.feed

import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.util.Log
import com.unsilence.app.data.memory.FeedRow
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "FeedState"

data class ReducerState(
    val visibleEvents: List<FeedRow> = emptyList(),
    val unreadCount: Int = 0,
    val showDot: Boolean = false,
    /**
     * Oldest createdAt across all visibleEvents. Reducer-owned source of truth
     * for pagination position — updated on RESET, APPEND, and MERGE.
     * No code outside the reducer is permitted to compute pagination position
     * from visibleEvents (no minOfOrNull anywhere).
     */
    val oldestCreatedAt: Long = Long.MAX_VALUE,
)

/**
 * Per-feed-key state machine for new-notes gating.
 *
 * When the user is at the top of the list, new events merge immediately.
 * When scrolled down, new events are counted but the visible list stays
 * stable. Tapping the blue dot or scrolling back to top flushes [latestRows].
 *
 * Key invariant: [latestRows] always holds the most recent MES emission.
 * Flush always replaces visibleEvents with latestRows — never reconstructs
 * from pending+visible.
 *
 * Performance: [knownIds] is a persistent immutable set maintained across
 * emissions so that APPEND/QUEUE operations are O(delta) not O(n).
 * Rebuilt only on MERGE/RESET/flush.
 */
class FeedStateReducer(private val feedKey: String) {

    private val _state = MutableStateFlow(ReducerState())
    val state: StateFlow<ReducerState> = _state.asStateFlow()

    /** Most recent MES emission — flush always uses this. */
    private var latestRows: List<FeedRow> = emptyList()

    /** IDs already counted as pending — prevents double-counting on repeated emissions. */
    private var pendingIds: Set<String> = emptySet()

    /**
     * Persistent set of all IDs currently in visibleEvents + pendingIds.
     * O(1) membership test, O(delta) updates via .add().
     * Rebuilt from scratch only on MERGE/RESET/flush.
     */
    private var knownIds: PersistentSet<String> = persistentSetOf()

    @Volatile private var isAtTop: Boolean = true
    private var lastAppendTime = 0L
    @Volatile private var pendingDataRefresh = false

    // Coalesce MERGE updates: fixed 200ms window batches rapid MES emissions.
    // First emission opens the window; subsequent emissions update pendingMerge
    // without resetting the timer. Dedup check runs ONCE per window in flushPending.
    // pendingMerge is written from background threads (collectLatest) and read from
    // the main-thread Handler callback — @Volatile + synchronized guard both paths.
    @Volatile
    private var pendingMerge: ReducerState? = null
    @Volatile
    private var pendingMergeKnownIds: PersistentSet<String>? = null
    @Volatile
    private var coalesceWindowOpen = false
    private val coalesceHandler = Handler(Looper.getMainLooper())
    private val flushPending = Runnable {
        synchronized(this) {
            val pending = pendingMerge ?: run {
                coalesceWindowOpen = false
                return@Runnable
            }
            val pendingKnown = pendingMergeKnownIds
            pendingMerge = null
            pendingMergeKnownIds = null
            coalesceWindowOpen = false

            // Dedup check — only here, once per window. Compare known set sizes
            // and identity first (fast path), then fall back to set comparison.
            val current = _state.value
            if (current.visibleEvents.size == pending.visibleEvents.size && knownIds == pendingKnown) {
                return@Runnable
            }
            if (pendingKnown != null) knownIds = pendingKnown
            _state.value = pending
        }
    }

    private fun emitCoalesced(newState: ReducerState, newKnownIds: PersistentSet<String>? = null) {
        synchronized(this) {
            pendingMerge = newState
            pendingMergeKnownIds = newKnownIds
            if (!coalesceWindowOpen) {
                coalesceWindowOpen = true
                coalesceHandler.postDelayed(flushPending, 200)
            }
            // If window already open: just update pendingMerge, don't reset timer
        }
    }

    // Data-only refresh coalescing. Unlike emitCoalesced, this ALWAYS emits
    // at window end because the whole point is to push new field values
    // (profile pictures, engagement counts) even when IDs are unchanged.
    // The MERGE path's ID dedup would defeat the purpose.
    //
    // Safety guard: if a MERGE occurred during the window (IDs changed),
    // the pending data is stale and gets discarded.
    @Volatile
    private var pendingDataMerge: ReducerState? = null
    @Volatile
    private var dataCoalesceWindowOpen = false
    private val flushPendingData = Runnable {
        synchronized(this) {
            val pending = pendingDataMerge ?: run {
                dataCoalesceWindowOpen = false
                return@Runnable
            }
            pendingDataMerge = null
            dataCoalesceWindowOpen = false
            pendingDataRefresh = false

            val current = _state.value
            // If IDs changed since we queued this (a MERGE occurred),
            // the pending data is stale — discard. Use size + knownIds for O(1) check.
            if (current.visibleEvents.size != pending.visibleEvents.size) {
                return@Runnable
            }
            if (current.visibleEvents != pending.visibleEvents) {
                _state.value = pending
            }
        }
    }

    private fun emitCoalescedDataRefresh(newState: ReducerState) {
        synchronized(this) {
            pendingDataMerge = newState
            if (!dataCoalesceWindowOpen) {
                dataCoalesceWindowOpen = true
                coalesceHandler.postDelayed(flushPendingData, 200)
            }
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

        if (isAtTop && !wasAtTop) {
            if (_state.value.showDot) {
                flush("TOP_REACHED")
            } else if (pendingDataRefresh) {
                // No queued events, but hydration updated data while scrolled down.
                // Emit the latest rows so profile pictures and engagement counts refresh.
                pendingDataRefresh = false
                val rows = latestRows
                if (rows.isNotEmpty()) {
                    Log.d(TAG, "feedKey=$feedKey action=DATA_FLUSH count=${rows.size}")
                    emitCoalescedDataRefresh(ReducerState(
                        visibleEvents = rows,
                        oldestCreatedAt = rows.minOf { it.createdAt },
                    ))
                }
            }
        }
    }

    /**
     * Called when MES emits a new list of events for this feed.
     *
     * Uses [knownIds] (persistent set) for O(delta) novelty detection.
     * APPEND path only walks new events, not the full list.
     */
    fun onNewEvents(allEvents: List<FeedRow>) {
        Trace.beginSection("FeedStateReducer.reduce")
        try { onNewEventsInner(allEvents) } finally { Trace.endSection() }
    }

    private fun onNewEventsInner(allEvents: List<FeedRow>) {
        latestRows = allEvents

        val current = _state.value

        // Fast path: identity check. Flow collectors frequently hand us a new list
        // instance with identical contents. === is a fast-path optimization only —
        // on !==, always run the delta walk via knownIds.
        if (allEvents === current.visibleEvents) return

        // Same-size check: if ID set hasn't changed, this is a data-only refresh
        // (engagement counts, profile pictures, etc.)
        if (allEvents.size == current.visibleEvents.size) {
            // O(n) but only on same-size emissions — check if any ID differs.
            // Build a quick ID set from allEvents for comparison.
            var allKnown = true
            for (event in allEvents) {
                if (event.id !in knownIds) {
                    allKnown = false
                    break
                }
            }
            if (allKnown) {
                // IDs match — data-only refresh.
                if (allEvents != current.visibleEvents) {
                    if (isAtTop) {
                        emitCoalescedDataRefresh(current.copy(visibleEvents = allEvents))
                    } else {
                        pendingDataRefresh = true
                    }
                }
                return
            }
        }

        if (current.visibleEvents.isEmpty() || isAtTop) {
            // MERGE path — safe: user is parked at top with no finger on screen.
            // Rebuild knownIds from scratch (only place besides flush).
            pendingIds = emptySet()
            pendingDataRefresh = false
            val newKnownIds = rebuildKnownIds(allEvents)
            val oldest = allEvents.minOfOrNull { it.createdAt } ?: Long.MAX_VALUE
            Log.d(TAG, "feedKey=$feedKey atTop=$isAtTop action=MERGE count=${allEvents.size}")
            if (current.visibleEvents.isEmpty()) {
                // First population — write directly, no coalescing needed.
                knownIds = newKnownIds
                _state.value = ReducerState(visibleEvents = allEvents, oldestCreatedAt = oldest)
            } else {
                emitCoalesced(
                    ReducerState(visibleEvents = allEvents, oldestCreatedAt = oldest),
                    newKnownIds,
                )
            }
        } else {
            // User is scrolled down — APPEND/QUEUE paths, O(delta) via knownIds.
            _state.update { cur ->
                // Prune pendingIds: remove any that are no longer in the incoming emission.
                // Build a lookup set from allEvents only if pendingIds is non-empty.
                if (pendingIds.isNotEmpty()) {
                    val incomingIds = HashSet<String>(allEvents.size)
                    for (event in allEvents) incomingIds.add(event.id)
                    pendingIds = pendingIds.filter { it in incomingIds }.toSet()
                }

                // Detect leading new events (QUEUE path) — stop at first known ID
                val leadingNew = allEvents.takeWhile { it.id !in knownIds }

                if (leadingNew.isEmpty()) {
                    // No leading new — check for APPEND (pagination)
                    val appended = mutableListOf<FeedRow>()
                    val seen = HashSet<String>()
                    for (event in allEvents) {
                        if (event.id !in knownIds && seen.add(event.id)) {
                            appended.add(event)
                        }
                    }
                    if (appended.isEmpty()) return@update cur

                    // Update knownIds incrementally — O(delta)
                    var updatedKnownIds = knownIds
                    for (event in appended) {
                        updatedKnownIds = updatedKnownIds.add(event.id)
                    }
                    knownIds = updatedKnownIds

                    lastAppendTime = System.currentTimeMillis()
                    val newVisible = cur.visibleEvents + appended
                    val oldest = minOf(
                        cur.oldestCreatedAt,
                        appended.minOf { it.createdAt },
                    )
                    Log.d(TAG, "feedKey=$feedKey atTop=false action=APPEND count=${appended.size} total=${newVisible.size}")
                    cur.copy(visibleEvents = newVisible, oldestCreatedAt = oldest)
                } else {
                    // QUEUE path — new events at head, don't append to visible
                    var updatedKnownIds = knownIds
                    for (event in leadingNew) {
                        updatedKnownIds = updatedKnownIds.add(event.id)
                    }
                    knownIds = updatedKnownIds

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
        pendingDataRefresh = false
        knownIds = rebuildKnownIds(rows)
        val oldest = rows.minOfOrNull { it.createdAt } ?: Long.MAX_VALUE
        Log.d(TAG, "flush feedKey=$feedKey count=${rows.size} reason=$reason")
        _state.value = ReducerState(visibleEvents = rows, oldestCreatedAt = oldest)
        isAtTop = true
    }

    /** Rebuild knownIds from a list of events — used on MERGE and flush only. */
    private fun rebuildKnownIds(events: List<FeedRow>): PersistentSet<String> {
        var ids = persistentSetOf<String>()
        for (event in events) {
            ids = ids.add(event.id)
        }
        return ids
    }
}
