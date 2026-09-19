package com.unsilence.app.data.memory

/** Counting is independent of an article's 200-row display page. */
internal const val CONVERSATION_MEMBER_LIMIT = 5_000
internal const val ARTICLE_COMMENT_PAGE_SIZE = 200

/** A bounded set of locally held replies, not a claim that relay history is complete. */
internal data class ConversationMembership(val ids: Set<String>, val truncated: Boolean)

// Callers select parsed reply edges or NIP-22 coordinate membership, never q
// citations. A citation must not veto an independently established reply edge.
internal fun isConversationReply(event: NostrEvent): Boolean =
    event.kind == 1 || event.kind == 1111

/**
 * One cycle-safe traversal for event and coordinate roots. Callers choose the
 * direct seeds; the reply predicate applies to seeds AND descendants. Check the
 * limit on every admission, including wide seed sets and wide child batches.
 * An extra eligible id proves truncation; exactly [cap] leaves is still exact.
 */
internal fun expandConversationDescendants(
    seeds: Sequence<String>,
    targetId: String?,
    cap: Int,
    eventProvider: (String) -> NostrEvent?,
    childrenOf: (String) -> Iterable<String>,
): ConversationMembership {
    require(cap >= 0)
    val ids = LinkedHashSet<String>()
    val queue = ArrayDeque<String>()
    fun admit(id: String): Boolean {
        if (id == targetId || id in ids) return true
        val event = eventProvider(id) ?: return true
        if (!isConversationReply(event)) return true
        if (ids.size == cap) return false
        ids.add(id)
        queue.add(id)
        return true
    }
    for (id in seeds) if (!admit(id)) return ConversationMembership(ids, truncated = true)
    while (queue.isNotEmpty()) {
        for (id in childrenOf(queue.removeFirst())) {
            if (!admit(id)) return ConversationMembership(ids, truncated = true)
        }
    }
    return ConversationMembership(ids, truncated = false)
}
