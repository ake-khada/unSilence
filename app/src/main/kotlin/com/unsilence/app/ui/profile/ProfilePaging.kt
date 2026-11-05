package com.unsilence.app.ui.profile

import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.TimelineMerge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A page belongs to the profile subscription that requested it. Called on Main. */
internal fun CoroutineScope.loadOlderProfilePage(
    timelineKey: String?,
    events: MutableStateFlow<List<NostrEvent>>,
    acceptsPage: () -> Boolean,
    fetchOlder: suspend (String, Long, Int) -> List<NostrEvent>,
): Job? {
    val key = timelineKey ?: return null
    // Use the mixed-kind tail, including replies hidden by the selected tab.
    val until = events.value.lastOrNull()?.createdAt ?: return null
    return launch {
        val older = fetchOlder(key, until, 100)
        if (acceptsPage() && older.isNotEmpty()) {
            events.update { current -> TimelineMerge.merge(current, older, capTail = false) }
        }
    }
}
