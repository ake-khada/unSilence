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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
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

    // Dedicated dispatcher. Snapshot restore is a long blocking parse —
    // must not compete with WebSocket consume threads.
    private val snapshotDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + snapshotDispatcher)
    private val mutex = Mutex()
    internal var periodicJob: Job? = null

    // Guard: save() must not run before restoreIfPresent completes.
    // Without this, a lifecycle-triggered save can overwrite the valid
    // snapshot with empty MES data before restore reads it.
    @Volatile
    private var restored = false

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
        scope.launch {
            val saved = withTimeoutOrNull(3000L) { save() }
            if (saved == null) {
                Log.w(TAG, "onStop save timed out after 3s (mutex held by periodic save)")
            }
        }
    }

    /** Age of the snapshot file in seconds, or [Long.MAX_VALUE] if no snapshot exists or file is empty. */
    fun getSnapshotAgeSeconds(): Long {
        val baseFile = snapshotFile.baseFile
        if (!baseFile.exists()) return Long.MAX_VALUE
        if (baseFile.length() == 0L) return Long.MAX_VALUE
        val lastModified = baseFile.lastModified()
        if (lastModified == 0L) return Long.MAX_VALUE
        return (System.currentTimeMillis() - lastModified) / 1000L
    }

    /**
     * Restore snapshot into MemoryEventStore if a valid snapshot file exists.
     * Called during AppBootstrapper Phase 1.5, BEFORE relay connections open.
     *
     * Dispatches by magic bytes:
     *   - V3 binary: first 4 bytes "USNS" → [MemoryEventStore.restoreSnapshotBinary]
     *   - V2 TSV (or anything else): falls through to [MemoryEventStore.restoreSnapshotFrom]
     *
     * The full file is read into a byte buffer first so we can peek the magic
     * without losing the bytes; for typical 5MB snapshots this is ~50ms on
     * flash storage and avoids any reader-rewind contortions.
     */
    suspend fun restoreIfPresent() = withContext(snapshotDispatcher) {
        val baseFile = snapshotFile.baseFile
        if (!baseFile.exists()) {
            Log.d(TAG, "No snapshot file found, starting fresh")
            restored = true
            return@withContext
        }
        mutex.withLock {
            try {
                val bytes = snapshotFile.openRead().use { it.readBytes() }
                val isBinary = bytes.size >= 4 &&
                    bytes[0] == SNAPSHOT_BINARY_MAGIC[0] &&
                    bytes[1] == SNAPSHOT_BINARY_MAGIC[1] &&
                    bytes[2] == SNAPSHOT_BINARY_MAGIC[2] &&
                    bytes[3] == SNAPSHOT_BINARY_MAGIC[3]
                if (isBinary) {
                    DataInputStream(BufferedInputStream(ByteArrayInputStream(bytes))).use { input ->
                        memoryEventStore.restoreSnapshotBinary(input)
                    }
                    Log.d(TAG, "Snapshot restored (binary V3) from ${bytes.size / 1024}KB")
                } else {
                    InputStreamReader(ByteArrayInputStream(bytes)).buffered().use { reader ->
                        memoryEventStore.restoreSnapshotFrom(reader)
                    }
                    Log.d(TAG, "Snapshot restored (V2 TSV migration path) from ${bytes.size / 1024}KB")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Snapshot restore failed, starting fresh", e)
            } finally {
                restored = true
            }
        }
    }

    /**
     * Save snapshot immediately. Called during teardown (logout) to persist
     * final state before clearing MemoryEventStore.
     * Always runs — bypasses the [restored] guard (explicit caller intent).
     */
    suspend fun saveNow() {
        doSave()
    }

    /**
     * Delete snapshot file on disk. Called during teardown to prevent
     * restoreIfPresent() from reloading old user's events after re-login.
     */
    fun deleteSnapshot() {
        snapshotFile.delete()
        restored = false
        Log.d(TAG, "Snapshot deleted")
    }

    private suspend fun save() {
        if (!restored) {
            Log.d(TAG, "save() skipped — restore not yet complete")
            return
        }
        doSave()
    }

    private suspend fun doSave() {
        mutex.withLock {
            var stream: FileOutputStream? = null
            try {
                stream = snapshotFile.startWrite()
                // Don't use .use{} — it closes the stream before finishWrite
                // can fsync the FD. Instead: flush + explicit sync while open.
                val out = DataOutputStream(BufferedOutputStream(stream))
                memoryEventStore.saveSnapshotBinary(out)
                out.flush()
                stream.fd.sync()
                snapshotFile.finishWrite(stream)
                Log.d(TAG, "Snapshot saved (${snapshotFile.baseFile.length() / 1024}KB, binary V3)")
            } catch (e: Exception) {
                if (stream != null) snapshotFile.failWrite(stream)
                Log.e(TAG, "Snapshot save FAILED", e)
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
