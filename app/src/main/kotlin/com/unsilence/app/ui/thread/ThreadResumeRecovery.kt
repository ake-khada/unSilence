package com.unsilence.app.ui.thread

import com.unsilence.app.data.memory.MemoryEventStore

/** Ephemeral resume decision from rows the visit already holds; no eviction journal. */
internal data class ThreadResumeRecovery(
    val queries: Set<String> = emptySet(),
    val missingReplyIds: Set<String> = emptySet(),
    val replyParentIds: Set<String> = emptySet(),
)

/** One paused root's recovery evidence, never a displayed count floor or payload cache. */
internal data class ThreadRootEngagement(
    val rootId: String,
    val reactions: Int,
    val zaps: Int,
    val sats: Long,
)

internal fun ThreadUiState.rootEngagementForResume(
    store: MemoryEventStore,
    pending: ThreadRootEngagement? = null,
): ThreadRootEngagement? {
    val root = focusedNote ?: return null
    val zap = store.zapStats(root.id)
    val previous = pending?.takeIf { it.rootId == root.id }
    // Keep evidence if loss precedes pause, and include receipts delivered since
    // the row projection. A deletion may cause one retry, never resurrection.
    return ThreadRootEngagement(root.id, maxOf(root.reactionCount, store.reactionCount(root.id), previous?.reactions ?: 0),
        maxOf(root.zapCount, zap.count, previous?.zaps ?: 0), maxOf(root.zapTotalSats, zap.totalSats, previous?.sats ?: 0L))
}

/** Run once before resumed fetching/projection, on Default. Deletions stay authoritative. */
internal fun missingThreadData(
    rootId: String,
    held: ThreadUiState,
    store: MemoryEventStore,
    paused: ThreadRootEngagement? = null,
): ThreadResumeRecovery {
    val root = held.focusedNote?.takeIf { it.id == rootId } ?: return ThreadResumeRecovery()
    val counts = paused?.takeIf { it.rootId == rootId }
    val missing = held.replies.asSequence().map { it.row }
        .filter { store.getNostrEvent(it.id) == null && !store.isDeletedById(it.id, it.pubkey) }.toList()
    val queries = buildSet {
        if (store.getNostrEvent(rootId) == null && !store.isDeletedById(rootId, root.pubkey)) add("thread-event")
        if (missing.isNotEmpty()) add("thread-replies")
        // A decrease can also be a deletion. Retry is bounded; tombstones still
        // reject redelivery, and no historical total is retained as a count floor.
        if (store.reactionCount(rootId) < (counts?.reactions ?: root.reactionCount)) add("thread-reactions")
        val zap = store.zapStats(rootId)
        if (zap.count < (counts?.zaps ?: root.zapCount) || zap.totalSats < (counts?.sats ?: root.zapTotalSats)) {
            add("thread-zaps")
        }
    }
    return ThreadResumeRecovery(queries, missing.mapTo(HashSet()) { it.id },
        missing.mapNotNullTo(HashSet()) { it.replyToId ?: it.rootId })
}

/** Apply on the observation owner after its account/root/cancellation fence. */
internal fun ThreadResumeRecovery.invalidate(
    completed: MutableSet<Pair<String, String>>,
    coordinate: String?,
    articleCoords: MutableSet<String>,
    descendants: MutableSet<String>,
    parents: MutableSet<String>,
) {
    completed.removeAll { it.first in queries }
    if (missingReplyIds.isNotEmpty()) {
        coordinate?.let(articleCoords::remove)
        descendants.removeAll(replyParentIds + missingReplyIds)
        parents.removeAll(missingReplyIds)
    }
}
