package com.unsilence.app.data.repository

import com.unsilence.app.data.db.dao.CoverageDao
import com.unsilence.app.data.relay.CoverageHandle
import com.unsilence.app.data.relay.CoverageIntent
import com.unsilence.app.data.relay.CoverageStatus
import com.unsilence.app.data.db.entity.CoverageEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoverageRepository @Inject constructor(
    private val coverageDao: CoverageDao,
) {
    suspend fun ensureCoverage(intent: CoverageIntent): CoverageStatus {
        val entity = coverageDao.ensureOrUpdate(
            intent.scopeType, intent.scopeKey,
            intent.relaySetId, intent.staleAfterMs,
        )
        return statusFromEntity(entity)
    }

    suspend fun markFromHandle(handle: CoverageHandle) {
        val existing = coverageDao.findLatest(
            handle.scopeType, handle.scopeKey, handle.relaySetId,
        ) ?: return
        val now = System.currentTimeMillis()
        when (handle.terminalStatus) {
            CoverageStatus.COMPLETE ->
                coverageDao.markComplete(existing.id, handle.eoseCount, 0, 0, now)
            CoverageStatus.PARTIAL ->
                coverageDao.markPartial(existing.id, handle.eoseCount, now)
            CoverageStatus.FAILED ->
                coverageDao.markFailed(existing.id)
            else -> {}
        }
    }

    suspend fun markFailed(scopeType: String, scopeKey: String, relaySetId: String) {
        coverageDao.markFailedByScope(scopeType, scopeKey, relaySetId)
    }

    suspend fun getStatus(
        scopeType: String,
        scopeKey: String,
        relaySetId: String,
    ): CoverageStatus {
        val existing = coverageDao.findLatest(scopeType, scopeKey, relaySetId)
            ?: return CoverageStatus.NEVER_FETCHED
        return statusFromEntity(existing)
    }

    private fun statusFromEntity(entity: CoverageEntity): CoverageStatus =
        when (entity.status) {
            "complete" -> {
                val age = System.currentTimeMillis() - entity.lastSuccessAt
                if (age < entity.staleAfterMs) CoverageStatus.COMPLETE
                else CoverageStatus.STALE
            }
            "partial" -> {
                val age = System.currentTimeMillis() - entity.lastSuccessAt
                if (age < entity.staleAfterMs) CoverageStatus.PARTIAL
                else CoverageStatus.STALE
            }
            "pending" -> CoverageStatus.LOADING
            "failed" -> CoverageStatus.FAILED
            else -> CoverageStatus.NEVER_FETCHED
        }
}
