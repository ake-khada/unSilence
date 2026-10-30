package com.unsilence.app.ui.shared

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.ui.feed.activityStatsTargetId
import com.unsilence.app.ui.feed.engagementId
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.unsilence.app.ui.feed.NoteActionsViewModel

@Composable
internal fun ObserveDisplayWindowLifecycle(actions: NoteActionsViewModel) {
    LifecycleResumeEffect(actions) {
        actions.setDisplayWindowActive(true)
        onPauseOrDispose { actions.setDisplayWindowActive(false) }
    }
}

/** One resumed surface's viewport, reported alongside its existing warm window. */
internal class DisplayWindowReporter(private val store: MemoryEventStore) {
    private var active = false
    private var window: MemoryEventStore.DisplayWindow? = null

    @Synchronized
    fun setActive(value: Boolean) {
        active = value
        if (!value) { window?.close(); window = null }
    }

    @Synchronized
    fun report(rows: List<FeedRow>, visibleIds: Set<String>) {
        if (!active) return
        if (rows.isEmpty()) { window?.close(); window = null; return }
        val current = window ?: store.openDisplayWindow().also { window = it }
        current.update(rows.filter { it.id in visibleIds }.map { row ->
            row.id to (store.getEventModel(row.id)?.engagementId
                ?: activityStatsTargetId(row.kind, row.id, row.rootId) ?: row.id)
        }, visibleIds)
    }
}
