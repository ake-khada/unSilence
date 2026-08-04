package com.unsilence.app.data.repository

import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.normalizeRelayUrl
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Registers before dispatch, accepts only callbacks from the intended targets,
 * and completes on the first OK or once every target explicitly rejects.
 */
internal suspend fun awaitRelayAcceptance(
    targetRelays: Set<String>,
    timeoutMs: Long,
    register: (callback: (relayUrl: String, accepted: Boolean, message: String) -> Unit) -> Unit,
    unregister: () -> Unit,
    dispatch: suspend () -> Unit,
): Boolean {
    if (targetRelays.isEmpty()) return false
    val pending = ConcurrentHashMap.newKeySet<String>().apply { addAll(targetRelays) }
    val verdict = CompletableDeferred<Boolean>()

    register callback@{ relayUrl, accepted, _ ->
        if (verdict.isCompleted || !pending.remove(relayUrl)) return@callback
        if (accepted) {
            verdict.complete(true)
        } else if (pending.isEmpty()) {
            verdict.complete(false)
        }
    }

    return try {
        dispatch()
        withTimeoutOrNull(timeoutMs) { verdict.await() } ?: false
    } finally {
        unregister()
    }
}

/**
 * Normalizes an author's write relays, falling back to the global bootstrap
 * set only when that write set resolves empty. Additional indexers are always
 * retained, but cannot accidentally suppress the write-relay fallback.
 */
internal fun publishTargetsWithGlobalFallback(
    writeRelays: Iterable<String>,
    additionalRelays: Iterable<String> = emptyList(),
): Set<String> {
    val targets = writeRelays.mapNotNullTo(linkedSetOf(), ::normalizeRelayUrl)
    if (targets.isEmpty()) {
        GLOBAL_RELAY_URLS.mapNotNullTo(targets, ::normalizeRelayUrl)
    }
    additionalRelays.mapNotNullTo(targets, ::normalizeRelayUrl)
    return targets
}
