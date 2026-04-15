package com.unsilence.app.data.memory

import android.util.Log
import androidx.core.util.AtomicFile
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SnapshotScheduler"
private const val PERIODIC_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

/**
 * Manages MemoryEventStore snapshot persistence with process-lifecycle-driven scheduling.
 *
 * Save triggers:
 *   1. Periodic — every 5 minutes while the app is foregrounded (ON_START → ON_STOP)
 *   2. onStop  — when the app moves to background (ProcessLifecycleOwner)
 *   3. Manual  — [saveNow] called during teardown (logout)
 *
 * Durability:
 *   Uses [AtomicFile] for crash-safe writes (write to tmp, rename on success,
 *   discard tmp on failure). A crash mid-write leaves the previous snapshot intact.
 *
 * Concurrency:
 *   [Mutex] ensures only one save or restore runs at a time.
 */
@Singleton
class SnapshotScheduler @Inject constructor(
    private val memoryEventStore: MemoryEventStore,
    private val snapshotFile: AtomicFile,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    internal var periodicJob: Job? = null

    /**
     * Register as a ProcessLifecycleOwner observer.
     * Called once from Application.onCreate.
     *
     * Does NOT start periodic saves eagerly — ON_START handles that.
     * Apps started in background (broadcast receivers, WorkManager) may never
     * foreground, and we shouldn't schedule I/O for those sessions.
     */
    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        Log.d(TAG, "Attached to process lifecycle")
    }

    override fun onStart(owner: LifecycleOwner) {
        startPeriodicSave()
    }

    override fun onStop(owner: LifecycleOwner) {
        periodicJob?.cancel()
        periodicJob = null
        scope.launch { save() }
    }

    /**
     * Restore snapshot into MemoryEventStore if a valid snapshot file exists.
     * Called during AppBootstrapper Phase 1.5, BEFORE relay connections open.
     */
    suspend fun restoreIfPresent() {
        val baseFile = snapshotFile.baseFile
        if (!baseFile.exists()) {
            Log.d(TAG, "No snapshot file found, starting fresh")
            return
        }
        mutex.withLock {
            try {
                snapshotFile.openRead().bufferedReader().use { reader ->
                    memoryEventStore.restoreSnapshotFrom(reader)
                }
                Log.d(TAG, "Snapshot restored from ${baseFile.length() / 1024}KB")
            } catch (e: Exception) {
                Log.e(TAG, "Snapshot restore failed, starting fresh", e)
            }
        }
    }

    /**
     * Save snapshot immediately. Called during teardown (logout) to persist
     * final state before clearing MemoryEventStore.
     */
    suspend fun saveNow() {
        save()
    }

    private suspend fun save() {
        mutex.withLock {
            val stream = snapshotFile.startWrite()
            try {
                stream.bufferedWriter().use { writer ->
                    memoryEventStore.saveSnapshotTo(writer)
                }
                snapshotFile.finishWrite(stream)
                Log.d(TAG, "Snapshot saved (${snapshotFile.baseFile.length() / 1024}KB)")
            } catch (e: Exception) {
                snapshotFile.failWrite(stream)
                Log.e(TAG, "Snapshot save failed", e)
            }
        }
    }

    private fun startPeriodicSave() {
        periodicJob?.cancel()
        periodicJob = scope.launch {
            while (true) {
                delay(PERIODIC_INTERVAL_MS)
                save()
            }
        }
    }
}
