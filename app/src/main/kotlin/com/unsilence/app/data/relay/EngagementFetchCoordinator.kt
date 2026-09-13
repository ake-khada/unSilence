package com.unsilence.app.data.relay

import com.unsilence.app.data.relay.CardHydrator.EngagementTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

internal const val ENGAGEMENT_FETCH_TIMEOUT_MS = 10_000L
/** Allow three network windows for the shared four-slot queue, without borrowing response time. */
internal const val ENGAGEMENT_ADMISSION_TIMEOUT_MS = 3 * ENGAGEMENT_FETCH_TIMEOUT_MS
internal const val ENGAGEMENT_ATTEMPT_TIMEOUT_MS =
    ENGAGEMENT_ADMISSION_TIMEOUT_MS + ENGAGEMENT_FETCH_TIMEOUT_MS + 500L
private const val ENGAGEMENT_RETRY_MS = 30_000L
private const val ENGAGEMENT_MAX_RETRY_MS = 300_000L
private const val ENGAGEMENT_FRESHNESS_CAP = 500
private val engagementAttemptIds = AtomicLong()

/**
 * Shared public/own-engagement orchestration. Coverage is (target, relay, filter
 * scope), never just a post id. A real EOSE records a successful bounded query;
 * it says nothing about exhaustive history, MES drain completion, or UI caps.
 *
 * Each post has one identity-owned attempt until ALL its requested relays settle.
 * Failures retain prior data and retry on viewport demand, with bounded backoff.
 * [retrySignals] wakes observers when a failed deadline matures; only active
 * screens re-submit their current warm window. No rows or off-screen fetch jobs
 * are retained here. Entries retain only their current bounded relay coverage.
 */
internal class EngagementFetchCoordinator(
    private val fetchRequest: suspend (String, List<String>, String, Long) -> Map<String, OneShotOutcome>,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = ENGAGEMENT_FRESHNESS_CAP,
) {
    private data class TargetKey(val id: String, val ownPubkey: String?)
    /** null coordinate means the ID filter; a coordinate identifies BOTH #a/#A filters. */
    private data class CoverageKey(val relay: String, val coordinate: String?)
    private data class Coverage(
        val succeededAt: Long? = null,
        val failures: Int = 0,
        val retryAt: Long = 0L,
    ) {
        fun due(now: Long, createdAt: Long, own: Boolean): Boolean {
            if (failures > 0) return now >= retryAt
            val success = succeededAt ?: return true
            val interval = if (own) Long.MAX_VALUE else engagementFreshnessInterval(now / 1000L - createdAt)
            return interval != Long.MAX_VALUE && now - success >= interval * 1_000L
        }
    }
    private class Entry {
        val coverage = HashMap<CoverageKey, Coverage>()
        var coordinate: String? = null
        var missingRelaysRetryAt = 0L
        var attempt: Attempt? = null
    }
    private class Attempt(
        val key: TargetKey,
        val entry: Entry,
        val target: EngagementTarget,
        val requestedRelays: Set<String>,
    ) {
        val pendingRelays = requestedRelays.toMutableSet()
    }

    private val entries = LinkedHashMap<TargetKey, Entry>(16, 0.75f, true)
    private val changes = MutableStateFlow(0L)

    @Synchronized
    fun clear() {
        entries.clear()
        changes.value += 1
    }

    /**
     * Shared by CardHydrator only while screens observe it. One sleeping deadline,
     * not a poll or a timer per card. Past deadlines emit once on re-subscription;
     * an unobserved/off-screen failure never starts network work by itself.
     */
    fun retrySignals() = flow {
        var announcedThrough = Long.MIN_VALUE
        while (currentCoroutineContext().isActive) {
            val (revision, deadline) = synchronized(this@EngagementFetchCoordinator) {
                changes.value to entries.values.asSequence()
                    .filter { it.attempt == null }
                    .flatMap { entry ->
                        sequenceOf(entry.missingRelaysRetryAt) + entry.coverage.values.asSequence()
                            .filter { it.failures > 0 }.map { it.retryAt }
                    }
                    .filter { it > 0L && it > announcedThrough }
                    .minOrNull()
            }
            if (deadline == null) {
                changes.first { it != revision }
            } else if (withTimeoutOrNull((deadline - nowMs()).coerceAtLeast(1L)) {
                    changes.first { it != revision }
                } == null
            ) {
                announcedThrough = deadline
                emit(Unit)
            }
        }
    }

    /** Cheap viewport gate, before parsing an already-fresh ordinary note. */
    @Synchronized
    fun needsFetch(id: String, createdAt: Long, hasCoordinate: Boolean, ownPubkey: String? = null): Boolean {
        val entry = entries[TargetKey(id, ownPubkey)] ?: return true
        if (entry.attempt != null) return false
        if (hasCoordinate && entry.coordinate == null) return true
        val now = nowMs()
        if (entry.coverage.isEmpty()) return now >= entry.missingRelaysRetryAt
        return entry.coverage.values.any { it.due(now, createdAt, ownPubkey != null) }
    }

    @Synchronized
    fun needsFetch(target: EngagementTarget, ownPubkey: String? = null): Boolean {
        val entry = entries[TargetKey(target.id, ownPubkey)]
        if (entry?.attempt != null) return false
        if (entry != null && entry.coordinate != target.coord) return true
        return needsFetch(target.id, target.createdAt, target.coord != null, ownPubkey)
    }

    /**
     * Runs the actual shared request builders and awaits transport outcomes. Batches
     * are already relay-selected/chunked by the caller; freshness never widens them.
     */
    suspend fun fetch(
        targets: List<EngagementTarget>,
        relayBatches: List<Pair<String, List<String>>>,
        ownPubkey: String? = null,
    ) {
        val attempts = begin(targets, relayBatches, ownPubkey)
        if (attempts.isEmpty()) return
        var unfinished = OneShotOutcome.CANCELLED
        try {
            val settled = withTimeoutOrNull(ENGAGEMENT_ATTEMPT_TIMEOUT_MS) {
                coroutineScope {
                    for ((relay, ids) in relayBatches) {
                        val covered = ids.mapNotNull(attempts::get).filter { relay in it.requestedRelays }
                        if (covered.isEmpty()) continue
                        val subId = "${if (ownPubkey == null) "eng" else "own-eng"}-${engagementAttemptIds.incrementAndGet()}"
                        val eventIds = covered.map { it.target.id }
                        val coordinates = covered.mapNotNull { it.target.coord }.distinct()
                        val req = if (ownPubkey == null) buildBatchedEngagementReq(subId, eventIds, coordinates)
                            else buildOwnEngagementReq(subId, ownPubkey, eventIds, coordinates)
                        launch {
                            val outcome = try {
                                fetchRequest(subId, listOf(relay), req, ENGAGEMENT_FETCH_TIMEOUT_MS)[relay]
                                    ?: OneShotOutcome.SKIPPED
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                OneShotOutcome.FAILED
                            }
                            for (attempt in covered) complete(attempt, relay, outcome)
                        }
                    }
                }
                true
            }
            unfinished = if (settled == null) OneShotOutcome.TIMEOUT else OneShotOutcome.FAILED
        } finally {
            // Backstop/cancellation clears only this attempt's remaining relays.
            // No stats snapshot and no unconditional "fetched" transition.
            synchronized(this) {
                for (attempt in attempts.values) {
                    for (relay in attempt.pendingRelays.toList()) complete(attempt, relay, unfinished)
                }
            }
        }
    }

    @Synchronized
    private fun begin(
        targets: List<EngagementTarget>,
        batches: List<Pair<String, List<String>>>,
        ownPubkey: String?,
    ): Map<String, Attempt> {
        val now = nowMs()
        val relaysById = HashMap<String, MutableSet<String>>()
        for ((relay, ids) in batches) for (id in ids) relaysById.getOrPut(id) { linkedSetOf() }.add(relay)
        val attempts = LinkedHashMap<String, Attempt>()
        for (target in targets.distinctBy { it.id }) {
            val key = TargetKey(target.id, ownPubkey)
            var entry = entries[key]
            if (entry == null) {
                if (entries.size >= maxEntries) {
                    val idle = entries.entries.firstOrNull { it.value.attempt == null }?.key ?: continue
                    entries.remove(idle)
                }
                entry = Entry()
                entries[key] = entry
            }
            if (entry.attempt != null) continue
            val desired = relaysById[target.id].orEmpty().flatMap { relay ->
                listOfNotNull(CoverageKey(relay, null), target.coord?.let { CoverageKey(relay, it) })
            }.toSet()
            entry.coordinate = target.coord
            entry.coverage.keys.retainAll(desired)
            if (desired.isEmpty()) {
                entry.missingRelaysRetryAt = now + ENGAGEMENT_RETRY_MS
                continue
            }
            entry.missingRelaysRetryAt = 0L
            val dueRelays = desired.filter { coverageKey ->
                entry.coverage[coverageKey]?.due(now, target.createdAt, ownPubkey != null) != false
            }.mapTo(linkedSetOf()) { it.relay }
            // Keep missing coverage explicit, including failed/skipped relay buckets.
            for (coverageKey in desired) entry.coverage.putIfAbsent(coverageKey, Coverage())
            if (dueRelays.isEmpty()) continue
            val attempt = Attempt(key, entry, target, dueRelays)
            entry.attempt = attempt
            attempts[target.id] = attempt
        }
        changes.value += 1
        return attempts
    }

    @Synchronized
    private fun complete(attempt: Attempt, relay: String, outcome: OneShotOutcome) {
        val entry = entries[attempt.key] ?: return
        if (entry !== attempt.entry || entry.attempt !== attempt || !attempt.pendingRelays.remove(relay)) return
        val now = nowMs()
        val scopes = listOfNotNull(CoverageKey(relay, null), attempt.target.coord?.let { CoverageKey(relay, it) })
        for (key in scopes) {
            val previous = entry.coverage[key] ?: Coverage()
            entry.coverage[key] = if (outcome == OneShotOutcome.EOSE) {
                Coverage(succeededAt = now)
            } else {
                val failures = (previous.failures + 1).coerceAtMost(5)
                previous.copy(
                    failures = failures,
                    retryAt = now + (ENGAGEMENT_RETRY_MS * (1L shl (failures - 1))).coerceAtMost(ENGAGEMENT_MAX_RETRY_MS),
                )
            }
        }
        if (attempt.pendingRelays.isEmpty()) entry.attempt = null
        changes.value += 1
    }
}
