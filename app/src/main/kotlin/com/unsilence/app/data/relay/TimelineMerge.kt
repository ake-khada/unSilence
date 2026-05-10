package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.NostrEvent

/**
 * Mirrors Jumble's src/lib/timeline.ts mergeTimelines.
 *
 * Two-pointer merge of sorted timelines + dedup by event id.
 * All callers must pass createdAt-DESC sorted lists.
 */
object TimelineMerge {

    /** Hard cap matches the prior TimelineConsumer.EVENTS_CAP constant. */
    const val EVENTS_CAP = 1000

    val EVENT_ORDER: Comparator<NostrEvent> = Comparator { a, b ->
        when {
            a.createdAt != b.createdAt -> b.createdAt.compareTo(a.createdAt)
            a.id != b.id -> a.id.compareTo(b.id)
            else -> 0
        }
    }

    /** Sort + distinctBy id. Use only when caller has an unsorted list. */
    fun sort(events: List<NostrEvent>): List<NostrEvent> =
        events.distinctBy { it.id }.sortedWith(EVENT_ORDER)

    /**
     * Merge `newEvents` into `current`. Both must be sorted createdAt-DESC.
     * Dedups by id (current wins on collision). When capTail=true, trims
     * the result to EVENTS_CAP.
     */
    fun merge(
        current: List<NostrEvent>,
        newEvents: List<NostrEvent>,
        capTail: Boolean = true,
    ): List<NostrEvent> {
        if (newEvents.isEmpty()) return current
        if (current.isEmpty()) {
            val sorted = sort(newEvents)
            return if (capTail && sorted.size > EVENTS_CAP) sorted.subList(0, EVENTS_CAP) else sorted
        }

        // Fast path: single-event insert via binary search (avoids O(N) HashSet build)
        if (newEvents.size == 1) {
            val evt = newEvents[0]
            // O(N) linear scan for dedup — still cheaper than HashSet allocation
            // for the common case of small current lists or guaranteed-novel events.
            // For large current lists, the binary search dominates at O(log N).
            for (e in current) { if (e.id == evt.id) return current }
            val insertIdx = current.binarySearch { EVENT_ORDER.compare(it, evt) }.let {
                if (it < 0) -(it + 1) else it
            }
            val result = ArrayList<NostrEvent>(current.size + 1)
            result.addAll(current.subList(0, insertIdx))
            result.add(evt)
            result.addAll(current.subList(insertIdx, current.size))
            return if (capTail && result.size > EVENTS_CAP) result.subList(0, EVENTS_CAP).toList() else result
        }

        // General path: multi-event two-pointer merge
        val seen = HashSet<String>(current.size + newEvents.size)
        for (e in current) seen.add(e.id)
        val novel = newEvents.filter { seen.add(it.id) }
        if (novel.isEmpty()) return current

        val novelSorted = novel.sortedWith(EVENT_ORDER)
        val result = ArrayList<NostrEvent>(current.size + novelSorted.size)
        var i = 0; var j = 0
        while (i < current.size && j < novelSorted.size) {
            if (EVENT_ORDER.compare(current[i], novelSorted[j]) <= 0) {
                result.add(current[i]); i++
            } else {
                result.add(novelSorted[j]); j++
            }
        }
        while (i < current.size) { result.add(current[i]); i++ }
        while (j < novelSorted.size) { result.add(novelSorted[j]); j++ }

        return if (capTail && result.size > EVENTS_CAP) result.subList(0, EVENTS_CAP).toList() else result
    }
}
