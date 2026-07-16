package com.unsilence.app.data.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val EVENT_ID_FETCH_WINDOW_MS = 150L
internal const val MAX_EVENT_IDS_PER_REQ = 50

internal data class EventIdFetchBatch(
    val eventIds: List<String>,
    val relayHints: List<String>,
)

/**
 * Small-window coalescer for independent reference lookups. A relay-hint set defines a wire
 * target group; duplicate IDs attach to the first queued request and share its completion.
 */
internal class EventIdFetchCoalescer(
    private val scope: CoroutineScope,
    private val windowMs: Long = EVENT_ID_FETCH_WINDOW_MS,
    private val maxBatchSize: Int = MAX_EVENT_IDS_PER_REQ,
    private val dispatch: suspend (EventIdFetchBatch) -> Unit,
) {
    private data class Pending(
        val eventId: String,
        val completions: MutableList<CompletableDeferred<Unit>>,
    )

    private data class DispatchWindow(
        val batch: EventIdFetchBatch,
        val pending: List<Pending>,
    )

    private val lock = Any()
    private val groups = linkedMapOf<List<String>, LinkedHashMap<String, Pending>>()
    private val pendingById = mutableMapOf<String, Pending>()
    private var flushJob: Job? = null

    fun enqueue(eventId: String, relayHints: List<String>): Deferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        if (eventId.isBlank()) {
            completion.complete(Unit)
            return completion
        }
        synchronized(lock) {
            val existing = pendingById[eventId]
            if (existing != null) {
                existing.completions += completion
                return@synchronized
            }

            val hints = canonicalRelayHints(relayHints)
            val pending = Pending(eventId, mutableListOf(completion))
            pendingById[eventId] = pending
            groups.getOrPut(hints) { linkedMapOf() }[eventId] = pending
            scheduleFlushLocked()
        }
        return completion
    }

    private fun scheduleFlushLocked() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(windowMs)
            flushWindow()
        }
    }

    private suspend fun flushWindow() {
        val windows = synchronized(lock) {
            flushJob = null
            val selected = groups.mapNotNull { (hints, pending) ->
                val chunk = pending.values.take(maxBatchSize)
                if (chunk.isEmpty()) return@mapNotNull null
                chunk.forEach { pending.remove(it.eventId) }
                DispatchWindow(
                    batch = EventIdFetchBatch(chunk.map { it.eventId }, hints),
                    pending = chunk,
                )
            }
            groups.entries.removeAll { it.value.isEmpty() }
            // Overflow deliberately rolls into another full window rather than creating a
            // same-tick request burst against the relay.
            if (groups.isNotEmpty()) scheduleFlushLocked()
            selected
        }

        windows.forEach { window ->
            val failure = try {
                dispatch(window.batch)
                null
            } catch (cancelled: CancellationException) {
                completeWindow(window, cancelled)
                throw cancelled
            } catch (error: Exception) {
                error
            }
            completeWindow(window, failure)
        }
    }

    private fun completeWindow(window: DispatchWindow, failure: Throwable?) {
        val completions = synchronized(lock) {
            window.pending.flatMap { pending ->
                if (pendingById[pending.eventId] === pending) {
                    pendingById.remove(pending.eventId)
                }
                pending.completions.toList()
            }
        }
        completions.forEach { completion ->
            if (failure == null) completion.complete(Unit)
            else completion.completeExceptionally(failure)
        }
    }
}

internal fun canonicalRelayHints(relayHints: List<String>): List<String> =
    relayHints.mapNotNull(::normalizeRelayUrl).distinct().sorted()
