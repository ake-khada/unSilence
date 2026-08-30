package com.unsilence.app.ui.shared

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.isMuted

/**
 * Apply the existing mute policy at a timeline projection boundary.
 *
 * MES-backed rows retain the trusted event sidecar needed to resolve repost targets. A
 * synthesized row has no such sidecar, so it uses the policy's deliberately narrower
 * flattened-row overload.
 */
internal fun isTimelineRowMuted(
    row: FeedRow,
    muteList: MuteList?,
    eventProvider: (String) -> NostrEvent?,
): Boolean {
    val event = eventProvider(row.id) ?: return isMuted(row, muteList)
    return isMuted(event, muteList, eventProvider)
}

internal fun mutedTimelineRowIds(
    rows: Collection<FeedRow>,
    muteList: MuteList?,
    eventProvider: (String) -> NostrEvent?,
): Set<String> = rows.asSequence()
    .filter { isTimelineRowMuted(it, muteList, eventProvider) }
    .mapTo(HashSet()) { it.id }

/**
 * Remove a muted row unless it is needed to connect at least one visible descendant.
 * [rows] must be a depth-first, pre-order projection with uncapped depths.
 */
internal fun <T> pruneFullyMutedSubtrees(
    rows: List<T>,
    depthOf: (T) -> Int,
    isMuted: (T) -> Boolean,
): List<T> {
    if (rows.isEmpty()) return emptyList()

    // In a pre-order flat tree, a node's subtree ends at the next row whose depth is
    // less than or equal to its own. Resolve every boundary in one forward pass.
    val subtreeEnds = IntArray(rows.size) { rows.size }
    val openAncestors = IntArray(rows.size)
    var openCount = 0
    rows.indices.forEach { index ->
        val depth = depthOf(rows[index])
        while (openCount > 0 && depthOf(rows[openAncestors[openCount - 1]]) >= depth) {
            subtreeEnds[openAncestors[--openCount]] = index
        }
        openAncestors[openCount++] = index
    }

    val visiblePrefix = IntArray(rows.size + 1)
    rows.indices.forEach { index ->
        visiblePrefix[index + 1] = visiblePrefix[index] + if (isMuted(rows[index])) 0 else 1
    }

    return rows.filterIndexed { index, row ->
        !isMuted(row) || visiblePrefix[subtreeEnds[index]] > visiblePrefix[index + 1]
    }
}
