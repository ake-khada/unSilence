package com.unsilence.app.data.memory

import android.util.Log
import androidx.core.util.AtomicFile
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SnapshotScheduler"
private const val PERIODIC_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
private const val DEFERRED_SAVE_DELAY_MS = 30_000L

/**
 * Tracks which save requests are covered by a completed snapshot.
 *
 * A write captures the newest request generation immediately before serialization. Requests
 * already pending at that point are represented by the same file and can safely skip their
 * queued writes. A request arriving during serialization receives a newer generation and is
 * deliberately left pending, because its mutation may not be present in the in-flight snapshot.
 */
internal class SnapshotSaveCoordinator {
    private val requested = AtomicLong(0L)
    private val completedThrough = AtomicLong(0L)

    fun request(): Long = requested.incrementAndGet()

    fun coverageForSave(request: Long): Long? =
        if (request <= completedThrough.get()) null else requested.get()

    fun complete(coveredThrough: Long) {
        completedThrough.updateAndGet { completed -> maxOf(completed, coveredThrough) }
    }

    fun reset() {
        completedThrough.set(requested.get())
    }
}

/**
 * Manages MemoryEventStore snapshot persistence with process-lifecycle-driven scheduling.
 *
 * Save triggers:
 *   1. Periodic — every 5 minutes while the app is foregrounded (ON_START → ON_STOP)
 *   2. onStop — when the app moves to background (ProcessLifecycleOwner)
 *   3. Immediate — shortly after authored state changes such as follows or reactions
 *   4. Deferred — after a quiet window for passive cache updates such as NIP-05 verification
 *   5. Manual — [saveNow] called during teardown (logout)
 *
 * Durability:
 *   Uses [AtomicFile] for crash-safe writes (write to tmp, rename on success,
 *   discard tmp on failure). A crash mid-write leaves the previous snapshot intact.
 *
 * Concurrency:
 *   [Mutex] ensures only one save or restore runs at a time.
 */
@Singleton
class SnapshotScheduler internal constructor(
    private val memoryEventStore: MemoryEventStore,
    private val snapshotFile: AtomicFile,
    private val deferredSaveDelayMs: Long,
) : DefaultLifecycleObserver {

    @Inject
    constructor(
        memoryEventStore: MemoryEventStore,
        snapshotFile: AtomicFile,
    ) : this(memoryEventStore, snapshotFile, DEFERRED_SAVE_DELAY_MS)

    // Dedicated dispatcher. Snapshot restore is a long blocking parse —
    // must not compete with WebSocket consume threads.
    private val snapshotDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + snapshotDispatcher)
    private val mutex = Mutex()
    private val saveCoordinator = SnapshotSaveCoordinator()
    private val scheduledJobsLock = Any()
    internal var periodicJob: Job? = null
    private var immediateJob: Job? = null
    private var deferredJob: Job? = null

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
        val request = saveCoordinator.request()
        scope.launch {
            val saved = withTimeoutOrNull(3000L) { save(request) }
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
     * The binary path is streamed end to end. [BufferedInputStream.mark] /
     * [BufferedInputStream.reset] preserve the four magic bytes for the reader
     * without copying the full snapshot into memory.
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
                val snapshotBytes = baseFile.length()
                snapshotFile.openRead().use { fileInput ->
                    val buffered = BufferedInputStream(fileInput)
                    buffered.mark(SNAPSHOT_BINARY_MAGIC.size)
                    val magic = ByteArray(SNAPSHOT_BINARY_MAGIC.size)
                    val magicBytesRead = buffered.read(magic)
                    buffered.reset()

                    if (magicBytesRead == SNAPSHOT_BINARY_MAGIC.size &&
                        magic.contentEquals(SNAPSHOT_BINARY_MAGIC)
                    ) {
                        DataInputStream(buffered).use { input ->
                            memoryEventStore.restoreSnapshotBinary(input)
                        }
                        Log.d(TAG, "Snapshot restored (binary V3) from ${snapshotBytes / 1024}KB")
                    } else {
                        InputStreamReader(buffered).buffered().use { reader ->
                            memoryEventStore.restoreSnapshotFrom(reader)
                        }
                        Log.d(TAG, "Snapshot restored (V2 TSV migration path) from ${snapshotBytes / 1024}KB")
                    }
                }
                restored = true
            } catch (t: Throwable) {
                if (t is CancellationException) throw t

                // A reader can fail after inserting part of the file. Make the
                // fallback a true cold start, but preserve mute intent recorded
                // while the long background restore was running.
                val pendingMutePublishes = memoryEventStore.pendingMutePublishesSnapshot()
                memoryEventStore.clear()
                memoryEventStore.restorePendingMutePublishesAfterReset(pendingMutePublishes)
                val badFile = quarantineSnapshot()
                val artifact = badFile?.absolutePath ?: "unavailable"
                if (t is SnapshotOwnerMismatchException) {
                    Log.w(
                        TAG,
                        "SNAPSHOT-OWNER mismatch: snapshot=${t.snapshotOwner.take(8)}… " +
                            "current=${t.currentOwner.take(8)}… — quarantined=$artifact",
                    )
                } else {
                    Log.e(
                        TAG,
                        "Snapshot restore failed; starting fresh, quarantined=$artifact",
                        t,
                    )
                }
                restored = true
            }
        }
    }

    /**
     * Move a failed snapshot beside the live file for post-mortem inspection.
     * Same-directory rename is the normal, allocation-free path. The streaming
     * copy fallback handles filesystems whose rename cannot replace a prior file.
     */
    private fun quarantineSnapshot(): File? {
        val source = snapshotFile.baseFile
        if (!source.exists()) return null
        val bad = File(source.parentFile, "${source.name}.bad")
        if (bad.exists() && !bad.delete()) {
            Log.e(TAG, "Could not replace prior bad snapshot at ${bad.absolutePath}")
            snapshotFile.delete()
            return null
        }
        if (source.renameTo(bad)) return bad

        return try {
            source.inputStream().buffered().use { input ->
                bad.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
            snapshotFile.delete()
            bad
        } catch (copyFailure: Throwable) {
            // Loop-breaking takes precedence if the post-mortem copy itself
            // cannot be created (for example, a full filesystem).
            snapshotFile.delete()
            Log.e(TAG, "Could not quarantine failed snapshot", copyFailure)
            null
        }
    }

    /**
     * Save snapshot immediately. Called during teardown (logout) to persist
     * final state before clearing MemoryEventStore.
     * Always runs — bypasses the [restored] guard (explicit caller intent).
     */
    suspend fun saveNow() {
        doSave(saveCoordinator.request(), requireRestored = false)
    }

    /**
     * Schedule a near-immediate save. 50ms coalesce window so a batch of mute
     * operations (e.g. muting 5 users quickly) writes once, but still fast enough
     * that the user can't background the app before the save fires.
     */
    fun scheduleImmediate() {
        val request = saveCoordinator.request()
        synchronized(scheduledJobsLock) {
            immediateJob?.cancel()
            immediateJob = scope.launch {
                delay(50L)
                save(request)
            }
        }
    }

    /**
     * Schedule persistence for passive cache state after a quiet window.
     *
     * Cache refreshes such as NIP-05 verification may finish in staggered bursts while the
     * user scrolls. Restarting this timer folds the burst into one full-store snapshot; the
     * periodic and onStop paths still bound durability if refreshes remain continuous.
     */
    fun scheduleDeferred() {
        val request = saveCoordinator.request()
        synchronized(scheduledJobsLock) {
            deferredJob?.cancel()
            deferredJob = scope.launch {
                delay(deferredSaveDelayMs)
                save(request)
            }
        }
    }

    /**
     * Delete snapshot file on disk. Called during teardown to prevent
     * restoreIfPresent() from reloading old user's events after re-login.
     */
    suspend fun deleteSnapshot() {
        val pendingJobs = synchronized(scheduledJobsLock) {
            listOfNotNull(immediateJob, deferredJob).also {
                immediateJob = null
                deferredJob = null
            }
        }
        pendingJobs.forEach(Job::cancel)
        pendingJobs.forEach { it.join() }
        mutex.withLock {
            snapshotFile.delete()
            saveCoordinator.reset()
            restored = false
            Log.d(TAG, "Snapshot deleted")
        }
    }

    private suspend fun save(request: Long) {
        doSave(request, requireRestored = true)
    }

    private suspend fun doSave(request: Long, requireRestored: Boolean) {
        mutex.withLock {
            // Check under the same lock as deleteSnapshot(): a save that passed an
            // outside guard before logout could otherwise recreate the deleted file.
            if (requireRestored && !restored) {
                Log.d(TAG, "save() skipped — restore not yet complete")
                return
            }
            val coveredThrough = saveCoordinator.coverageForSave(request) ?: return
            try {
                val stream = snapshotFile.startWrite()
                try {
                    // V3 binary writer is the only writer. V2 TSV writer remains
                    // in MES for restore-side migration but is no longer called.
                    // AtomicFile owns the FileOutputStream: flush the wrapper,
                    // then let finishWrite fsync and close the still-open stream.
                    val out = DataOutputStream(BufferedOutputStream(stream))
                    val sections = memoryEventStore.saveSnapshotBinary(out)
                    out.flush()
                    snapshotFile.finishWrite(stream)
                    saveCoordinator.complete(coveredThrough)
                    Log.w(
                        TAG,
                        "Snapshot sections: totalBytes=${sections.totalBytes} " +
                            "headerBytes=${sections.headerBytes} " +
                            "followsBytes=${sections.followsBytes} " +
                            "eventsBytes=${sections.eventsBytes} " +
                            "aggregatesBytes=${sections.aggregatesBytes} " +
                            "relayHealthBytes=${sections.relayHealthBytes} " +
                            "timelinesBytes=${sections.timelinesBytes} " +
                            "tailBytes=${sections.tailBytes} eventCount=${sections.eventCount} " +
                            "nonContent=${sections.nonContentEventCount}/" +
                            "${sections.nonContentCandidateCount}" +
                            "(anchored=${sections.anchoredNonContentCount}) " +
                            "content=${sections.contentEventCount}/" +
                            "${sections.contentCandidateCount} " +
                            "ownProfile=${sections.anchoredOwnProfileContentCount}/" +
                            "${sections.ownProfileContentCandidateCount} " +
                            "followsEntries=${sections.followsEntryCount}/" +
                            "${sections.followsCandidateCount}" +
                            "(anchored=${sections.anchoredFollowsCount})",
                    )
                    Log.d(TAG, "Snapshot saved (${snapshotFile.baseFile.length() / 1024}KB, binary V3)")
                } catch (t: Throwable) {
                    // Preserve the previous AtomicFile even when serialization fails
                    // with an Error such as OutOfMemoryError.
                    runCatching { snapshotFile.failWrite(stream) }
                    throw t
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // Snapshot persistence is best-effort. A later periodic tick retries.
                Log.e(TAG, "Snapshot save failed; keeping previous snapshot", t)
            }
        }
    }

    private fun startPeriodicSave() {
        periodicJob?.cancel()
        periodicJob = scope.launch {
            while (true) {
                delay(PERIODIC_INTERVAL_MS)
                save(saveCoordinator.request())
            }
        }
    }
}
