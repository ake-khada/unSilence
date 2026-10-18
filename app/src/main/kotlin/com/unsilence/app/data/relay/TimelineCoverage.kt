package com.unsilence.app.data.relay

/** Coverage is independent of the display cache: live arrivals never move [head]. */
internal data class TimelineCoverage(
    val head: Long? = null,
    val pending: Boolean = false,
    val floor: Long? = null,
    val until: Long? = null,
    val targetHead: Long? = null,
) {
    fun replay(previousHead: Long?): TimelineCoverage = copy(
        pending = true,
        floor = if (pending) floor else head ?: previousHead,
        until = null,
        targetHead = head,
    )

    fun completed(page: RelayCompletion, limit: Int?, now: Long): TimelineCoverage {
        val newest = listOfNotNull(targetHead, head, page.newest?.coerceAtMost(now)).maxOrNull()
        if (page.reason != RelayCompletionReason.Genuine) {
            // A partial page may have holes anywhere, including above its oldest
            // event. Retry this same window, not a cursor derived from its rows.
            return copy(targetHead = newest)
        }
        val full = limit != null && page.count >= limit
        val reachedFloor = floor != null && page.oldest != null && page.oldest <= floor
        if (!full || reachedFloor || floor == null) {
            return TimelineCoverage(head = newest)
        }
        // until is inclusive. Keep the boundary second so ties are not skipped;
        // the caller stops if a full page cannot advance.
        return copy(until = listOfNotNull(until, page.oldest).minOrNull(), targetHead = newest)
    }
}
