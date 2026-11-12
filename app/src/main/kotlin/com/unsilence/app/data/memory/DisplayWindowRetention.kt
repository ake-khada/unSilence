package com.unsilence.app.data.memory

/** Window-only ID leases; no payloads, conversation graph or time-based cache. */
internal const val DISPLAY_WINDOW_ROW_LIMIT = 32
internal const val COVERED_WINDOW_LIMIT = 3

internal class DisplayWindowRetention(private val lock: Any) {
    private class Entry(var ids: Set<String> = emptySet(), var visibleIds: Set<String> = emptySet())
    private val entries = HashMap<Any, Entry>()
    private val owners = HashMap<String, Int>()
    private val covered = LinkedHashSet<Any>()
    private var generation = 0L

    inner class Lease internal constructor(private val key: Any, private val accountGeneration: Long) : AutoCloseable {
        private var closed = false

        fun update(ids: Collection<String>, visibleIds: Set<String> = ids.toSet()): Boolean = synchronized(lock) {
            if (closed || generation != accountGeneration) return@synchronized false
            if (ids.isEmpty()) { remove(key); return@synchronized true }
            val entry = entries[key] ?: run {
                // Retired handles can return, but never across account teardown.
                if (entries.size >= 32) retireOldestCovered()
                if (entries.size >= 32) return@synchronized false
                Entry().also { entries[key] = it }
            }
            val limit = DISPLAY_WINDOW_ROW_LIMIT * 2
            val next = ids.asSequence().filter { it.isNotBlank() }.distinct().take(limit).toSet()
            val nextVisible = visibleIds.intersect(next)
            if (entry.ids == next && entry.visibleIds == nextVisible) return@synchronized true
            val removed = entry.ids - next
            (next - entry.ids).forEach { owners[it] = (owners[it] ?: 0) + 1 }
            removed.forEach(::releaseId)
            entry.ids = next
            entry.visibleIds = nextVisible
            true
        }

        fun setActive(active: Boolean) = synchronized(lock) {
            if (closed || generation != accountGeneration || key !in entries) return@synchronized
            if (active) covered.remove(key)
            else if (covered.add(key) && covered.size > COVERED_WINDOW_LIMIT) retireOldestCovered()
            Unit
        }

        override fun close() = synchronized(lock) {
            closed = true
            remove(key)
        }
    }

    fun acquire(): Lease = synchronized(lock) {
        // An idle reporter holds only a fenced handle; registry space starts at report.
        Lease(Any(), generation)
    }

    fun retireOldestCovered(): Boolean = synchronized(lock) {
        val key = covered.firstOrNull() ?: return@synchronized false
        remove(key)
        true
    }

    /** Current priority without allocating a snapshot on the admission path. */
    fun priority(id: String): Int? = synchronized(lock) {
        if (id !in owners) null
        else if (entries.any { (key, entry) -> key !in covered && id in entry.visibleIds }) 0
        else if (entries.any { (key, entry) -> key !in covered && id in entry.ids }) 1
        else 2
    }

    /** Snapshot before sorting: never consult moving viewport state in a comparator. */
    fun priorities(): Map<String, Int> = synchronized(lock) {
        buildMap {
            // Covered history never creates hidden-screen hydration priority.
            this@DisplayWindowRetention.entries.forEach { (key, entry) ->
                if (key !in covered) {
                    entry.ids.forEach { putIfAbsent(it, 1) }
                    entry.visibleIds.forEach { put(it, 0) }
                }
            }
        }
    }

    fun forget(id: String) = synchronized(lock) {
        if (owners.remove(id) != null) {
            entries.values.forEach { it.ids = it.ids - id; it.visibleIds = it.visibleIds - id }
        }
    }

    fun clear() = synchronized(lock) {
        generation++
        entries.clear()
        owners.clear()
        covered.clear()
    }

    private fun remove(key: Any) {
        covered.remove(key)
        entries.remove(key)?.ids?.forEach(::releaseId)
    }

    private fun releaseId(id: String) {
        val count = owners[id] ?: return
        if (count == 1) owners.remove(id) else owners[id] = count - 1
    }
}
