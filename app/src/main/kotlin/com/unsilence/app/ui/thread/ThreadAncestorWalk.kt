package com.unsilence.app.ui.thread

import com.unsilence.app.data.memory.EventEntity
import kotlinx.coroutines.withTimeoutOrNull

internal const val THREAD_ANCESTOR_BUDGET_MS = 20_000L

/** Keep the best reached root on timeout; never discard the completed hops. */
internal suspend fun walkThreadAncestors(
    startId: String,
    lookup: suspend (String) -> EventEntity?,
    onParent: (currentId: String, parentId: String) -> Unit,
    timeoutMs: Long = THREAD_ANCESTOR_BUDGET_MS,
): String {
    var currentId = startId
    val visited = mutableSetOf<String>()
    withTimeoutOrNull(timeoutMs) {
        repeat(50) {
            if (!visited.add(currentId)) return@withTimeoutOrNull
            val current = lookup(currentId) ?: return@withTimeoutOrNull
            val parentId = current.replyToId ?: current.rootId
            if (parentId == null || parentId == currentId) return@withTimeoutOrNull
            onParent(currentId, parentId)
            currentId = parentId
        }
    }
    return currentId
}
