package com.unsilence.app.data.relay

import kotlinx.coroutines.sync.Mutex

/** Same six-hour freshness window as account startup; this clock is lookup time. */
internal const val RELAY_LIST_REFRESH_MS = 6 * 60 * 60_000L
internal const val RELAY_LIST_RETRY_MS = 2 * 60_000L
internal const val RELAY_LIST_AUTHOR_CHUNK = 500
internal const val MAX_RELAY_LIST_CHECKS = 4_096

/** Startup demand only. Compact, account-cleared lookup evidence, never snapshot data. */
internal class RelayListRefresh(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val capacity: Int = MAX_RELAY_LIST_CHECKS,
) {
    private data class Check(val attemptedAt: Long, val checkedAt: Long?)
    private val checks = LinkedHashMap<String, Check>(16, 0.75f, true)
    private val running = Mutex()
    private var generation = 0L

    @Synchronized fun clear() { generation++; checks.clear() }

    suspend fun refresh(
        authors: List<String>,
        hasList: (String) -> Boolean,
        fetch: suspend (List<String>) -> Boolean,
    ) {
        if (!running.tryLock()) return
        val owner = synchronized(this) { generation }
        try {
            for (chunk in authors.distinct().chunked(RELAY_LIST_AUTHOR_CHUNK)) {
                val due = synchronized(this) {
                    if (owner != generation) return
                    val now = nowMs()
                    chunk.filter { author ->
                        val check = checks[author]
                        val retryReady = check == null || now - check.attemptedAt >= RELAY_LIST_RETRY_MS
                        val stale = !hasList(author) || check?.checkedAt == null || now - check.checkedAt >= RELAY_LIST_REFRESH_MS
                        if (retryReady && stale) {
                            checks[author] = Check(now, check?.checkedAt)
                            while (checks.size > capacity) checks.remove(checks.keys.first())
                            true
                        } else false
                    }
                }
                if (due.isEmpty()) continue
                // True means genuine coverage of this exact chunk on every selected
                // relay, never merely dispatch, a signed event or waiter completion.
                if (fetch(due)) synchronized(this) {
                    if (owner == generation) {
                        val now = nowMs()
                        due.forEach { author -> checks[author]?.let { checks[author] = it.copy(checkedAt = now) } }
                    }
                }
            }
        } finally {
            running.unlock()
        }
    }
}
