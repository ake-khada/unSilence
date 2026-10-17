package com.unsilence.app.ui.feed

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

internal const val REFERENCE_ATTEMPT_MS = 15_000L
internal const val REFERENCE_RETRY_DELAY_MS = 2_000L

/** Reserve time for every remaining ladder step, including the final miss tier. */
internal class ReferencePhaseBudget(
    steps: Int,
    timeoutMs: Long = REFERENCE_ATTEMPT_MS - 1_000L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val deadlineMs = nowMs() + timeoutMs
    private var remainingSteps = steps.coerceAtLeast(1)

    suspend fun run(block: suspend () -> Unit) {
        val availableMs = (deadlineMs - nowMs()).coerceAtLeast(0)
        val stepMs = availableMs / remainingSteps
        remainingSteps = (remainingSteps - 1).coerceAtLeast(1)
        withTimeoutOrNull(stepMs) { block() }
    }
}

data class ReferenceState<T>(val event: T? = null, val loading: Boolean = true)

/**
 * One caller owns a lookup and its single retry. Followers never own cleanup.
 * Only active lookups and a bounded miss cooldown are retained, never payloads.
 * Observation survives a miss, but store signals cannot initiate new requests.
 */
internal class ReferenceRecovery<K : Any, T : Any>(
    private val cached: (K) -> T?,
    private val watch: (K) -> Flow<T?>,
    private val fetch: suspend (K, retry: Boolean) -> T?,
    private val retain: (K) -> AutoCloseable = { AutoCloseable {} },
    private val identity: (K) -> Any = { it },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val attemptMs: Long = REFERENCE_ATTEMPT_MS,
    private val retryDelayMs: Long = REFERENCE_RETRY_DELAY_MS,
    private val cooldownMs: Long = 30_000L,
    private val capacity: Int = 128,
    maxActive: Int = 32,
) {
    private sealed interface Outcome<out T> {
        data class Completed<T>(val event: T?) : Outcome<T>
        data object OwnerCancelled : Outcome<Nothing>
    }
    private class Flight<T> {
        val result = CompletableDeferred<Outcome<T>>()
        val firstMiss = CompletableDeferred<Unit>()
    }
    private val lock = Any()
    private val slots = Semaphore(maxActive)
    private val flights = mutableMapOf<Any, Flight<T>>()
    private val misses = LinkedHashMap<Any, Long>()

    suspend fun lookup(target: K, onFirstMiss: suspend () -> Unit = {}): T? {
        while (true) {
            cached(target)?.let { return it }
            val key = identity(target)
            val existing = synchronized(lock) { flights[key] }
            if (existing != null) {
                val result = coroutineScope {
                    val progress = launch { existing.firstMiss.await(); onFirstMiss() }
                    try { existing.result.await() } finally { progress.cancel() }
                }
                when (result) {
                    is Outcome.Completed -> return result.event
                    Outcome.OwnerCancelled -> continue
                }
            }
            // Capacity is not a miss. Queued callers hold no retention leases.
            slots.acquire()
            try {
                cached(target)?.let { return it }
                val own = Flight<T>()
                val selected = synchronized(lock) {
                    flights[key] ?: run {
                        if (nowMs() < (misses[key] ?: Long.MIN_VALUE)) return null
                        flights[key] = own
                        own
                    }
                }
                // Another caller acquired ownership while this one queued.
                // Release the permit before joining that flight on the next pass.
                if (selected !== own) continue
                return runOwnedLookup(target, key, own, onFirstMiss)
            } finally {
                slots.release()
            }
        }
    }

    private suspend fun runOwnedLookup(
        target: K,
        key: Any,
        own: Flight<T>,
        onFirstMiss: suspend () -> Unit,
    ): T? {
        try {
            val result = retain(target).use {
                coroutineScope {
                    // A verified store arrival wins even while waiting for EOSE.
                    val arrival = async(start = CoroutineStart.UNDISPATCHED) {
                        watch(target).filterNotNull().first()
                    }
                    val requests = async {
                        attempt(target, retry = false)
                            ?: cached(target)
                            ?: run {
                                own.firstMiss.complete(Unit)
                                onFirstMiss()
                                delay(retryDelayMs)
                                cached(target) ?: attempt(target, retry = true)
                            }
                    }
                    try {
                        select<T?> {
                            arrival.onAwait { it }
                            requests.onAwait { it ?: cached(target) }
                        }
                    } finally {
                        arrival.cancelAndJoin()
                        requests.cancelAndJoin()
                    }
                }
            }
            synchronized(lock) {
                if (result == null) {
                    misses.remove(key)
                    misses[key] = nowMs() + cooldownMs
                    while (misses.size > capacity) misses.remove(misses.keys.first())
                } else misses.remove(key)
            }
            own.result.complete(Outcome.Completed(result))
            return result
        } finally {
            synchronized(lock) { if (flights[key] === own) flights.remove(key) }
            // A cancelled owner is not a terminal miss: one surviving follower
            // takes over. Cancelling a follower cannot remove any owner's marker.
            own.result.complete(Outcome.OwnerCancelled)
        }
    }

    private suspend fun attempt(target: K, retry: Boolean): T? = try {
        withTimeoutOrNull(attemptMs) { fetch(target, retry) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Network/parser failures are misses, not crashes in a card collector.
        null
    }

    fun observe(target: K): Flow<ReferenceState<T>> = channelFlow {
        val loading = MutableStateFlow(true)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            combine(watch(target), loading) { event, pending ->
                ReferenceState(event, loading = event == null && pending)
            }.distinctUntilChanged().collect { send(it) }
        }
        suspend fun finishLoading() {
            loading.value = false
        }
        try {
            lookup(target, onFirstMiss = { finishLoading() })
            finishLoading()
            observer.join()
        } finally {
            observer.cancel()
        }
    }.distinctUntilChanged()
}
