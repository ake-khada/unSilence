package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.domain.model.FeedFilter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Snapshot identity and candidates; never event payloads or confirmed coverage. */
internal data class FollowingHistoryRouting(
    val writes: Map<String, List<String>>,
    val candidates: Map<String, List<String>>,
)

internal const val FOLLOWING_HISTORY_LIMIT = 300
internal const val FOLLOWING_HISTORY_CANDIDATES = 5 // Four preferred, then one additional candidate.
internal const val FOLLOWING_HISTORY_RETRY_MS = 1_000L

/** Relative filter identity stays stable across setup/resume; since itself is frozen per plan. */
internal data class FollowingHistoryScope(
    val account: String,
    val writes: Map<String, List<String>>,
    val filter: FeedFilter,
)

internal data class FollowingHistoryPage(
    val events: List<NostrEvent> = emptyList(),
    val outcome: OneShotOutcome = OneShotOutcome.SKIPPED,
    /** No transport permit was acquired, or foreground transport is suspended. */
    val deferred: Boolean = false,
)

/** Confined to one collectLatest caller. Retained across handle replacement, never persisted.
 * A finished turn certifies only a bounded lookup, not exhaustive or live coverage. */
internal class FollowingHistoryPlan(
    val scope: FollowingHistoryScope,
    candidates: Map<String, List<String>>,
    filter: NostrFilter,
    nowSec: Long,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    internal data class Progress(
        val candidates: List<String>,
        var next: Int = 0,
        var retrieved: Boolean = false,
        var fullPage: Boolean = false,
        var retryAt: Long = 0,
    ) {
        val finished: Boolean get() = retrieved || next >= candidates.size
    }
    internal val progress = sortedMapOf<String, Progress>()
    private var omitted = emptySet<String>()

    init { updateCandidates(candidates) }

    /** Called ONLY inside the collectLatest action, after the previous action has
     * cancelled and joined. Keep each author's original candidate order/progress;
     * live selection changes can add work without repeating completed turns. */
    fun updateCandidates(candidates: Map<String, List<String>>) {
        omitted = candidates.keys.intersect(scope.writes.keys)
        for (author in omitted) progress.getOrPut(author) {
            Progress(candidates.getValue(author).distinct().take(FOLLOWING_HISTORY_CANDIDATES))
        }
    }
    internal val filter = filter.copy(
        limit = minOf(filter.limit ?: FOLLOWING_HISTORY_LIMIT, FOLLOWING_HISTORY_LIMIT).coerceAtLeast(1),
        until = minOf(filter.until ?: nowSec, nowSec),
    )

    suspend fun run(
        fetch: suspend (String, NostrFilter) -> FollowingHistoryPage,
        onHistory: (List<NostrEvent>) -> Unit,
        isCurrent: () -> Boolean = { true },
    ) {
        // Breadth-first passes: every author gets a first turn before any retry.
        for (pass in 0 until FOLLOWING_HISTORY_CANDIDATES) {
            for ((author, state) in progress) {
                if (author !in omitted || state.finished || state.next > pass) continue
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return
                delay((state.retryAt - nowMs()).coerceAtLeast(0))
                val page = fetch(state.candidates[state.next], filter.copy(authors = listOf(author)))
                currentCoroutineContext().ensureActive()
                if (!isCurrent() || page.deferred) return // Keep this turn pending; no timer or fake attempt.
                if (page.events.isNotEmpty()) onHistory(page.events)
                if (!isCurrent()) return
                if (page.outcome == OneShotOutcome.EOSE) {
                    state.retrieved = true
                    state.fullPage = page.events.size >= requireNotNull(filter.limit)
                } else {
                    state.next++
                    state.retryAt = nowMs() + FOLLOWING_HISTORY_RETRY_MS
                }
            }
        }
    }
}
