package com.unsilence.app.data.init

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Opaque capability proving that a readiness signal belongs to the current login. */
@JvmInline
value class InitSession internal constructor(internal val generation: Long)

/**
 * Session-scoped readiness for cold-start data dependencies.
 *
 * [beginSession] creates fresh milestones for every login. Bootstrap must present the
 * returned [InitSession] when signalling them, so a late completion from a cancelled
 * account can never release consumers belonging to the next account.
 */
@Singleton
class InitGate @Inject constructor() {

    private data class SessionState(
        val owner: String?,
        val token: InitSession,
        val follows: CompletableDeferred<Unit> = CompletableDeferred(),
        val relays: CompletableDeferred<Unit> = CompletableDeferred(),
        val feedConnections: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val lock = Any()
    private val nextGeneration = AtomicLong(0)

    @Volatile
    private var state = newState(owner = null)

    private val _phase = MutableStateFlow(Phase.CONNECTING)

    /** Observable phase for UI only. Use the await methods for control flow. */
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    enum class Phase { CONNECTING, FOLLOWS, RELAYS, READY }

    /** Starts a clean readiness epoch for [owner]. */
    fun beginSession(owner: String): InitSession = synchronized(lock) {
        newState(owner).also {
            state = it
            _phase.value = Phase.CONNECTING
        }.token
    }

    /** Invalidates every token issued before logout. */
    fun invalidateSession() = synchronized(lock) {
        state = newState(owner = null)
        _phase.value = Phase.CONNECTING
    }

    fun isCurrent(session: InitSession, owner: String? = null): Boolean {
        val current = state
        return current.token == session && (owner == null || current.owner == owner)
    }

    /** Signals that kind-3 discovery completed, including a verified empty result. */
    fun signalFollowsReady(session: InitSession) = signal(session) { it.follows }

    /** Signals that kind-10002 discovery completed, including a verified empty result. */
    fun signalRelaysReady(session: InitSession) = signal(session) { it.relays }

    /** Signals that the feed relay set has at least completed its connection attempt. */
    fun signalFeedConnectionsReady(session: InitSession) =
        signal(session, recompute = false) { it.feedConnections }

    suspend fun awaitFollows() = state.follows.await()

    suspend fun awaitRelays() = state.relays.await()

    suspend fun awaitReady() {
        val current = state
        current.follows.await()
        current.relays.await()
    }

    suspend fun awaitFeedConnections() = state.feedConnections.await()

    val followsReady: Boolean get() = state.follows.isCompleted
    val relaysReady: Boolean get() = state.relays.isCompleted
    val feedConnectionsReady: Boolean get() = state.feedConnections.isCompleted
    val isReady: Boolean
        get() = state.let { it.follows.isCompleted && it.relays.isCompleted }

    private fun signal(
        session: InitSession,
        recompute: Boolean = true,
        milestone: (SessionState) -> CompletableDeferred<Unit>,
    ) = synchronized(lock) {
        val current = state
        if (current.token != session) return@synchronized
        if (milestone(current).complete(Unit) && recompute) recomputePhase(current)
    }

    private fun recomputePhase(current: SessionState) {
        if (state !== current) return
        _phase.value = when {
            current.follows.isCompleted && current.relays.isCompleted -> Phase.READY
            current.relays.isCompleted -> Phase.RELAYS
            current.follows.isCompleted -> Phase.FOLLOWS
            else -> Phase.CONNECTING
        }
    }

    private fun newState(owner: String?): SessionState = SessionState(
        owner = owner,
        token = InitSession(nextGeneration.incrementAndGet()),
    )
}
