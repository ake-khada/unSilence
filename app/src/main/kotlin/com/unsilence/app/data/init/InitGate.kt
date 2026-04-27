package com.unsilence.app.data.init

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coroutine-based readiness signal for cold-start data dependencies.
 *
 * Bootstrap signals three milestones:
 *   1. `signalFollowsReady()` — kind-3 contact list loaded (or timed out)
 *   2. `signalRelaysReady()` — own kind-10002 relay list loaded (or timed out)
 *   3. `signalFeedConnectionsReady()` — global relay WebSockets connected
 *      (Phase1 Step5 complete). Consumers that need relay subs to work
 *      (FeedViewModelV2) gate on this before issuing subscriptions.
 *
 * Consumers call `awaitFollows()` / `awaitRelays()` / `awaitReady()` /
 * `awaitFeedConnections()` before issuing relay subscriptions that depend
 * on those datasets. Awaiters that arrive AFTER the signal resume immediately.
 *
 * Idempotent — second signal call is a no-op. Cannot be reset; create a
 * new InitGate (or rebuild the Hilt graph) for a fresh cold start.
 *
 * Mirrors Jumble's NostrProvider isInitialized gate (NostrProvider.tsx).
 */
@Singleton
class InitGate @Inject constructor() {

    private val followsDeferred = CompletableDeferred<Unit>()
    private val relaysDeferred = CompletableDeferred<Unit>()
    private val feedConnectionsDeferred = CompletableDeferred<Unit>()

    private val _phase = MutableStateFlow(Phase.CONNECTING)

    /** Observable phase for UI (splash, debug screens). Avoid using for control flow — use awaitX instead. */
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    enum class Phase { CONNECTING, FOLLOWS, RELAYS, READY }

    /** Bootstrap calls this when kind-3 has been fetched (or timeout fired). Idempotent. */
    fun signalFollowsReady() {
        if (followsDeferred.complete(Unit)) {
            recomputePhase()
        }
    }

    /** Bootstrap calls this when own kind-10002 has been fetched (or timeout fired). Idempotent. */
    fun signalRelaysReady() {
        if (relaysDeferred.complete(Unit)) {
            recomputePhase()
        }
    }

    /** Bootstrap calls this after Phase1 Step5 — global relay connections established. Idempotent. */
    fun signalFeedConnectionsReady() {
        feedConnectionsDeferred.complete(Unit)
    }

    /** Suspend until follows are ready. Returns immediately if already signaled. */
    suspend fun awaitFollows() = followsDeferred.await()

    /** Suspend until own relay list is ready. Returns immediately if already signaled. */
    suspend fun awaitRelays() = relaysDeferred.await()

    /** Suspend until BOTH follows and relays are ready. */
    suspend fun awaitReady() {
        followsDeferred.await()
        relaysDeferred.await()
    }

    /** Suspend until global relay connections are established (Phase1 Step5). */
    suspend fun awaitFeedConnections() = feedConnectionsDeferred.await()

    /** Non-suspending status check — useful for early-exit paths. */
    val followsReady: Boolean get() = followsDeferred.isCompleted
    val relaysReady: Boolean get() = relaysDeferred.isCompleted
    val feedConnectionsReady: Boolean get() = feedConnectionsDeferred.isCompleted
    val isReady: Boolean get() = followsReady && relaysReady

    private fun recomputePhase() {
        _phase.value = when {
            followsReady && relaysReady -> Phase.READY
            relaysReady -> Phase.RELAYS  // relays before follows is unusual but handle gracefully
            followsReady -> Phase.FOLLOWS
            else -> Phase.CONNECTING
        }
    }
}
