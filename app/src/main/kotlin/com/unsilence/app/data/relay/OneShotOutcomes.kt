package com.unsilence.app.data.relay

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Terminal transport outcomes, NOT proof of storage admission or exhaustive history. */
internal enum class OneShotOutcome { EOSE, CLOSED, TIMEOUT, SKIPPED, FAILED, CANCELLED }

private const val MAX_TRACKED_ONE_SHOTS = 256

/**
 * Outcome-aware counterpart to the pool's legacy lifecycle-completion callbacks.
 * One entry per active request, removed in finally; late callbacks cannot affect a
 * retry's distinct subscription id. Cleanup additionally checks ticket identity.
 */
internal class OneShotOutcomes {
    private class Ticket(val targets: Set<String>) {
        val admitted = targets.associateWith { CompletableDeferred<Unit>() }
        val outcomes = targets.associateWith { CompletableDeferred<OneShotOutcome>() }

        fun record(relay: String, outcome: OneShotOutcome) {
            // A terminal result also releases a relay that failed before admission.
            outcomes[relay]?.complete(outcome)
            admit(relay)
        }

        fun admit(relay: String) { admitted[relay]?.complete(Unit) }

        fun finish(outcome: OneShotOutcome) {
            for (relay in targets) record(relay, outcome)
        }
    }

    private val tickets = HashMap<String, Ticket>()

    @Synchronized
    fun contains(subId: String): Boolean = subId in tickets

    fun record(subId: String, relay: String, outcome: OneShotOutcome) {
        synchronized(this) { tickets[subId] }?.record(relay, outcome)
    }

    /**
     * Queue admission and network response have independent, bounded budgets per
     * relay. Dispatch signals admission after acquiring transport capacity, before
     * handshake/send. Returning a target also admits it (the pooled fast path).
     * Neither a busy queue nor another relay spends this relay's network budget.
     */
    suspend fun fetch(
        subId: String,
        relays: List<String>,
        timeoutMs: Long,
        dispatch: suspend (onAdmitted: (String) -> Unit) -> Set<String>,
        cleanup: () -> Unit,
        admissionTimeoutMs: Long = timeoutMs,
    ): Map<String, OneShotOutcome> {
        val targets = relays.mapNotNull(::normalizeRelayUrl).toSet()
        if (targets.isEmpty()) return emptyMap()
        val ticket = Ticket(targets)
        synchronized(this) {
            check(subId !in tickets) { "Subscription id is already in use: $subId" }
            if (tickets.size >= MAX_TRACKED_ONE_SHOTS) {
                return targets.associateWith { OneShotOutcome.SKIPPED }
            }
            tickets[subId] = ticket
        }
        try {
            return coroutineScope {
                val dispatchJob = launch {
                    try {
                        val admitted = dispatch(ticket::admit)
                        for (relay in targets) {
                            if (relay in admitted) ticket.admit(relay)
                            else ticket.record(relay, OneShotOutcome.SKIPPED)
                        }
                    } catch (e: CancellationException) {
                        // A dispatch-originated cancellation must cancel the caller.
                        // Our own finally also cancels dispatch, but only after every
                        // relay already has an outcome; that is ordinary cleanup.
                        if (ticket.outcomes.values.any { !it.isCompleted }) this@coroutineScope.cancel(e)
                        throw e
                    } catch (_: Exception) {
                        ticket.finish(OneShotOutcome.FAILED)
                    }
                }
                try {
                    targets.map { relay ->
                        async {
                            val admitted = withTimeoutOrNull(admissionTimeoutMs) {
                                ticket.admitted.getValue(relay).await()
                                true
                            } == true
                            val outcome = if (admitted) withTimeoutOrNull(timeoutMs) {
                                ticket.outcomes.getValue(relay).await()
                            } else null
                            ticket.record(relay, outcome ?: OneShotOutcome.TIMEOUT)
                            relay to ticket.outcomes.getValue(relay).await()
                        }
                    }.awaitAll().toMap()
                } finally {
                    dispatchJob.cancel()
                }
            }
        } catch (e: CancellationException) {
            ticket.finish(OneShotOutcome.CANCELLED)
            throw e
        } finally {
            // Do not let old cleanup remove a replacement's subscription state.
            val removed = synchronized(this) { tickets.remove(subId, ticket) }
            if (removed) cleanup()
        }
    }
}
