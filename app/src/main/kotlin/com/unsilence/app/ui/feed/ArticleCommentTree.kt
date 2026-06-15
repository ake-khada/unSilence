package com.unsilence.app.ui.feed

import com.unsilence.app.data.memory.FeedRow

/** A comment paired with its nesting depth (0 = top-level). */
internal data class CommentDepth(val row: FeedRow, val depth: Int)

/**
 * Flatten the flat article-comment list into a depth-ordered display list:
 * replies appear directly under their parent, indented. A comment is a CHILD
 * only when its `replyToId` points at another comment IN THIS LIST — so a
 * top-level comment (replyToId == article id, or null, or an unfetched parent)
 * is a root, and we never nest via arbitrary tags (preserving the
 * quote/mention false-attribution guard). Siblings are oldest-first (createdAt,
 * id tie-break); depth is capped at [maxDepth] for layout. Pure + testable.
 */
internal fun flattenArticleComments(comments: List<FeedRow>, maxDepth: Int = 6): List<CommentDepth> {
    if (comments.isEmpty()) return emptyList()
    val ids = comments.mapTo(HashSet()) { it.id }
    val childrenOf = HashMap<String, MutableList<FeedRow>>()
    val roots = mutableListOf<FeedRow>()
    for (c in comments) {
        val parent = c.replyToId
        if (parent != null && parent in ids) {
            childrenOf.getOrPut(parent) { mutableListOf() }.add(c)
        } else {
            roots.add(c)
        }
    }
    val cmp = compareBy<FeedRow> { it.createdAt }.thenBy { it.id }
    roots.sortWith(cmp)
    childrenOf.values.forEach { it.sortWith(cmp) }

    val out = ArrayList<CommentDepth>(comments.size)
    val visited = HashSet<String>()
    fun walk(row: FeedRow, depth: Int) {
        if (!visited.add(row.id)) return
        out.add(CommentDepth(row, depth.coerceAtMost(maxDepth)))
        childrenOf[row.id]?.forEach { walk(it, depth + 1) }
    }
    roots.forEach { walk(it, 0) }
    // Safety net: any row not reached (cycle / orphan parent) shown flat.
    for (c in comments) if (c.id !in visited) out.add(CommentDepth(c, 0))
    return out
}
