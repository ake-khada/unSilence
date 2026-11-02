package com.unsilence.app.ui.thread

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.ui.shared.CardDataFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Reuse the already-rendered root during the navigation entrance. This cold handle
 * owns no cache or work; relay requests still start only when the screen resumes.
 * Replies wait for the normal root/focus projection to avoid showing the wrong OP.
 */
internal fun threadPresentation(
    eventId: String,
    states: StateFlow<ThreadUiState>,
    cachedRow: (String) -> FeedRow?,
    prepareRow: (String) -> Unit = {},
): CardDataFlow<ThreadUiState> {
    fun present(state: ThreadUiState): ThreadUiState {
        if (!state.loading) return state
        val root = cachedRow(eventId)?.takeIf {
            it.id == eventId && it.rootId == null && it.replyToId == null
        } ?: return state
        // No replies are seeded before their mute/spam projection. The focused
        // root uses the same EventCard and sensitive-content gate as settled UI.
        return state.copy(focusedNote = root, loading = false)
    }
    val updates = states.onStart {
        // A quote may have a parsed model but no FeedRow yet. Project that one
        // local ID off Main while STARTED, using the existing MES row cache.
        if (states.value.loading && cachedRow(eventId) == null) prepareRow(eventId)
    }.map(::present).flowOn(Dispatchers.Default)
    return CardDataFlow(updates) { present(states.value) }
}
