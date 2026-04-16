package com.unsilence.app.data.cache

import com.unsilence.app.data.relay.CoverageHandle
import com.unsilence.app.data.relay.CoverageIntent
import com.unsilence.app.data.relay.CoverageStatus
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory replacement for CoverageRepository + CoverageDao.
 * Tracks session-scoped relay fetch coverage — which scopes have been fetched,
 * their status, and staleness. Data is ephemeral: meaningless after app restart.
 */
@Singleton
class CoverageTracker @Inject constructor() {

    data class Entry(
        val scopeType: String,
        val scopeKey: String,
        val relaySetId: String,
        val status: String = "pending",
        val eoseCount: Int = 0,
        val lastSuccessAt: Long = 0L,
        val lastAttemptAt: Long = 0L,
        val staleAfterMs: Long = 300_000L,
    )

    // Key: "$scopeType:$scopeKey:$relaySetId"
    private val entries = ConcurrentHashMap<String, Entry>()

    private fun key(scopeType: String, scopeKey: String, relaySetId: String) =
        "$scopeType:$scopeKey:$relaySetId"

    fun ensureCoverage(intent: CoverageIntent): CoverageStatus {
        val k = key(intent.scopeType, intent.scopeKey, intent.relaySetId)
        val existing = entries[k]
        if (existing != null) {
            val age = System.currentTimeMillis() - existing.lastSuccessAt
            val isTerminal = existing.status in listOf("complete", "partial")
            if (isTerminal && age < existing.staleAfterMs) return statusFromEntry(existing)
            if (existing.status == "pending") return CoverageStatus.LOADING
        }
        entries[k] = Entry(
            scopeType = intent.scopeType,
            scopeKey = intent.scopeKey,
            relaySetId = intent.relaySetId,
            status = "pending",
            lastAttemptAt = System.currentTimeMillis(),
            staleAfterMs = intent.staleAfterMs,
        )
        return CoverageStatus.LOADING
    }

    fun markFromHandle(handle: CoverageHandle) {
        val k = key(handle.scopeType, handle.scopeKey, handle.relaySetId)
        val existing = entries[k] ?: return
        val now = System.currentTimeMillis()
        entries[k] = when (handle.terminalStatus) {
            CoverageStatus.COMPLETE -> existing.copy(
                status = "complete", eoseCount = handle.eoseCount, lastSuccessAt = now,
            )
            CoverageStatus.PARTIAL -> existing.copy(
                status = "partial", eoseCount = handle.eoseCount, lastSuccessAt = now,
            )
            CoverageStatus.FAILED -> existing.copy(status = "failed")
            else -> existing
        }
    }

    fun markFailed(scopeType: String, scopeKey: String, relaySetId: String) {
        val k = key(scopeType, scopeKey, relaySetId)
        entries.computeIfPresent(k) { _, entry ->
            if (entry.status == "pending") entry.copy(status = "failed") else entry
        }
    }

    fun getStatus(scopeType: String, scopeKey: String, relaySetId: String): CoverageStatus {
        val existing = entries[key(scopeType, scopeKey, relaySetId)]
            ?: return CoverageStatus.NEVER_FETCHED
        return statusFromEntry(existing)
    }

    fun clear() {
        entries.clear()
    }

    private fun statusFromEntry(entry: Entry): CoverageStatus =
        when (entry.status) {
            "complete" -> {
                val age = System.currentTimeMillis() - entry.lastSuccessAt
                if (age < entry.staleAfterMs) CoverageStatus.COMPLETE else CoverageStatus.STALE
            }
            "partial" -> {
                val age = System.currentTimeMillis() - entry.lastSuccessAt
                if (age < entry.staleAfterMs) CoverageStatus.PARTIAL else CoverageStatus.STALE
            }
            "pending" -> CoverageStatus.LOADING
            "failed" -> CoverageStatus.FAILED
            else -> CoverageStatus.NEVER_FETCHED
        }
}
