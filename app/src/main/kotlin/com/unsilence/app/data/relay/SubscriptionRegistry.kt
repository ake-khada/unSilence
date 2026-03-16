package com.unsilence.app.data.relay

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class Lane(val subId: String, val relayUrl: String)

data class CoverageHandle(
    val handleId: String,
    val scopeType: String,
    val scopeKey: String,
    val relaySetId: String,
    val expectedLanes: Set<Lane>,
    val eoseLanes: MutableSet<Lane> = ConcurrentHashMap.newKeySet(),
    val failedLanes: MutableSet<Lane> = ConcurrentHashMap.newKeySet(),
) {
    val resolvedCount: Int get() = (eoseLanes + failedLanes).size
    val isComplete: Boolean get() = resolvedCount >= expectedLanes.size
    val isAllFailed: Boolean get() = failedLanes.size >= expectedLanes.size
    val eoseCount: Int get() = eoseLanes.size

    val terminalStatus: CoverageStatus? get() = when {
        !isComplete -> null
        isAllFailed -> CoverageStatus.FAILED
        failedLanes.isEmpty() -> CoverageStatus.COMPLETE
        else -> CoverageStatus.PARTIAL
    }
}

@Singleton
class SubscriptionRegistry @Inject constructor() {
    private val laneToHandleId = ConcurrentHashMap<Lane, String>()
    private val handles = ConcurrentHashMap<String, CoverageHandle>()

    fun register(handle: CoverageHandle) {
        handles[handle.handleId] = handle
        for (lane in handle.expectedLanes) {
            laneToHandleId[lane] = handle.handleId
        }
    }

    /** Returns handle if it reached terminal state, null otherwise. */
    fun onEose(subId: String, relayUrl: String): CoverageHandle? {
        val lane = Lane(subId, relayUrl)
        val handleId = laneToHandleId[lane] ?: return null
        val handle = handles[handleId] ?: return null
        handle.eoseLanes.add(lane)
        return if (handle.isComplete) handle else null
    }

    /** Returns handle if it reached terminal state, null otherwise. */
    fun onLaneFailure(subId: String, relayUrl: String): CoverageHandle? {
        val lane = Lane(subId, relayUrl)
        val handleId = laneToHandleId[lane] ?: return null
        val handle = handles[handleId] ?: return null
        if (lane in handle.eoseLanes) return null   // already succeeded
        if (lane in handle.failedLanes) return null  // already failed
        handle.failedLanes.add(lane)
        return if (handle.isComplete) handle else null
    }

    /** All lanes that have pending (unresolved) expectations for [relayUrl]. */
    fun subsForRelay(relayUrl: String): List<Lane> {
        return handles.values.flatMap { handle ->
            handle.expectedLanes.filter { lane ->
                lane.relayUrl == relayUrl &&
                lane !in handle.eoseLanes &&
                lane !in handle.failedLanes
            }
        }
    }

    fun cleanup(handleId: String) {
        val handle = handles.remove(handleId) ?: return
        for (lane in handle.expectedLanes) {
            laneToHandleId.remove(lane)
        }
    }
}
