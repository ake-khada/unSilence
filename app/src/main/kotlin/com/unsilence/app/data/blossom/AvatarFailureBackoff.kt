package com.unsilence.app.data.blossom

import java.util.concurrent.TimeUnit

/** One bounded metadata table for host failures and failed hash/URL attempts. */
internal class AvatarFailureBackoff(
    private val now: () -> Long = System::nanoTime,
    private val capacity: Int = 256,
    private val ttl: Long = TimeUnit.MINUTES.toNanos(5),
) {
    private val entries = LinkedHashMap<String, Long>(capacity, .75f, true)
    @Synchronized fun contains(key: String): Boolean {
        val until = entries[key] ?: return false
        if (until - now() > 0) return true
        entries.remove(key)
        return false
    }
    @Synchronized fun record(key: String) {
        entries[key] = now() + ttl
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
    @Synchronized fun clear() = entries.clear()
}
