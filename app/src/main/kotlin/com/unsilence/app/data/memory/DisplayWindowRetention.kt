package com.unsilence.app.data.memory

/** Window-only ID leases; no payloads, conversation graph or time-based cache. */
internal const val DISPLAY_WINDOW_ROW_LIMIT = 32

internal class DisplayWindowRetention(private val lock: Any) {
    private class Entry(var ids: Set<String> = emptySet(), var visibleIds: Set<String> = emptySet())
    private val entries = HashMap<Any, Entry>()
    private val owners = HashMap<String, Int>()

    inner class Lease internal constructor(private val key: Any) : AutoCloseable {
        fun update(ids: Collection<String>, visibleIds: Set<String> = ids.toSet()): Boolean = synchronized(lock) {
            val entry = entries[key] ?: return@synchronized false
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

        override fun close() = synchronized(lock) {
            val entry = entries.remove(key) ?: return@synchronized
            entry.ids.forEach(::releaseId)
        }
    }

    fun acquire(): Lease = synchronized(lock) {
        val key = Any()
        val limit = 32
        if (entries.size < limit) {
            entries[key] = Entry()
        }
        Lease(key)
    }

    fun contains(id: String): Boolean = synchronized(lock) { id in owners }

    /** Snapshot before sorting: never consult moving viewport state in a comparator. */
    fun priorities(): Map<String, Int> = synchronized(lock) {
        buildMap {
            owners.keys.forEach { put(it, 1) }
            this@DisplayWindowRetention.entries.values.forEach { entry -> entry.visibleIds.forEach { put(it, 0) } }
        }
    }

    fun forget(id: String) = synchronized(lock) {
        if (owners.remove(id) != null) {
            entries.values.forEach { it.ids = it.ids - id; it.visibleIds = it.visibleIds - id }
        }
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        owners.clear()
    }

    private fun releaseId(id: String) {
        val count = owners[id] ?: return
        if (count == 1) owners.remove(id) else owners[id] = count - 1
    }
}
