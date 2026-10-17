package com.unsilence.app.data.memory

/** Small, session-local priority set for active reference lookups. No payloads
 * are retained here; requested content still counts against its normal cap. */
internal class RequestedEventRetention(private val maxIds: Int = 32) {
    private class Entry(var owners: Int = 1)
    private val entries = HashMap<String, Entry>()

    fun acquire(id: String?): AutoCloseable = synchronized(entries) {
        if (id.isNullOrBlank()) return@synchronized NO_OP
        val entry = entries[id]?.also { it.owners++ } ?: run {
            if (entries.size >= maxIds) return@synchronized NO_OP
            Entry().also { entries[id] = it }
        }
        var closed = false
        AutoCloseable {
            synchronized(entries) {
                if (!closed) {
                    closed = true
                    // An old account's completion cannot release a new lease.
                    if (entries[id] === entry && --entry.owners == 0) entries.remove(id)
                }
            }
        }
    }

    fun contains(id: String): Boolean = synchronized(entries) { id in entries }
    fun snapshot(): Set<String> = synchronized(entries) { entries.keys.toSet() }
    fun clear() = synchronized(entries) { entries.clear() }

    private companion object {
        val NO_OP = AutoCloseable { }
    }
}
