package com.unsilence.app.data.cache

import com.unsilence.app.data.db.entity.SyncStateEntity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory replacement for SyncStateDao.
 * Tracks session-scoped relay sync progress (which subscription keys have been synced,
 * last timestamps, event counts). Data is ephemeral: meaningless after app restart.
 */
@Singleton
class SyncTracker @Inject constructor() {

    private val entries = ConcurrentHashMap<String, SyncStateEntity>()

    fun get(key: String): SyncStateEntity? = entries[key]

    fun all(): List<SyncStateEntity> = entries.values.toList()

    fun upsert(state: SyncStateEntity) {
        entries[state.subscriptionKey] = state
    }

    fun updateTimestamp(key: String, ts: Long, delta: Int, src: String) {
        entries.computeIfPresent(key) { _, existing ->
            existing.copy(
                lastSyncAt = ts,
                lastEventCount = existing.lastEventCount + delta,
                source = src,
            )
        }
    }

    fun clear() {
        entries.clear()
    }
}
