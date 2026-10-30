package com.unsilence.app.data.memory

/** Window-only ID leases; no payloads, conversation graph or time-based cache. */
internal const val DISPLAY_WINDOW_ROW_LIMIT = 32

internal class DisplayWindowRetention(private val lock: Any) {
    private class Entry(var ids: Set<String> = emptySet())
    private val entries = HashMap<Any, Entry>()
    private val owners = HashMap<String, Int>()

    inner class Lease internal constructor(private val key: Any) : AutoCloseable {
        fun update(ids: Collection<String>): Boolean = synchronized(lock) {
            val entry = entries[key] ?: return@synchronized false
            val limit = DISPLAY_WINDOW_ROW_LIMIT * 2
            val next = ids.asSequence().filter { it.isNotBlank() }.distinct().take(limit).toSet()
            val removed = entry.ids - next
            (next - entry.ids).forEach { owners[it] = (owners[it] ?: 0) + 1 }
            removed.forEach(::releaseId)
            entry.ids = next
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

    fun forget(id: String) = synchronized(lock) {
        if (owners.remove(id) != null) {
            entries.values.forEach { it.ids = it.ids - id }
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
