package com.unsilence.app.data.db

import android.util.Log
import com.unsilence.app.data.db.dao.EventDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DatabaseMaintenanceJob"
private const val ROW_CAP = 800_000
private const val BATCH_SIZE = 10_000
private const val BATCH_DELAY_MS = 50L
private const val INTERVAL_MS = 5 * 60 * 1000L  // 5 minutes

/**
 * Periodic database maintenance: FIFO pruning (800K row cap) and NIP-40 expiration cleanup.
 * Runs every 5 minutes. Deletes in 10K-row batches with 50ms delays to avoid long DB locks.
 *
 * Lifecycle: started by AppBootstrapper.bootstrap(), stopped by AppBootstrapper.teardown().
 */
@Singleton
class DatabaseMaintenanceJob @Inject constructor(
    private val eventDao: EventDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                delay(INTERVAL_MS)
                runMaintenance()
            }
        }
        Log.d(TAG, "Started (interval=${INTERVAL_MS}ms)")
    }

    fun stop() {
        job?.cancel()
        job = null
        Log.d(TAG, "Stopped")
    }

    private suspend fun runMaintenance() {
        try {
            // 1. FIFO prune: keep at most ROW_CAP rows
            val count = eventDao.count()
            var excess = count - ROW_CAP
            if (excess > 0) {
                Log.d(TAG, "FIFO pruning: $count rows, deleting $excess")
                while (excess > 0) {
                    val batch = minOf(excess, BATCH_SIZE)
                    eventDao.deleteOldest(batch)
                    excess -= batch
                    delay(BATCH_DELAY_MS)
                }
                Log.d(TAG, "FIFO prune complete, ${eventDao.count()} rows remaining")
            }

            // 2. NIP-40 expiration prune
            eventDao.pruneExpired(System.currentTimeMillis() / 1000L)
        } catch (e: Exception) {
            Log.w(TAG, "Maintenance failed: ${e.message}")
        }
    }
}
