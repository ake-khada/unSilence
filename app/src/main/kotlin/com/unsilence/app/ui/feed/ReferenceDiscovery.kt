package com.unsilence.app.ui.feed

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Optional relay metadata may help a lookup, but must never delay or fail it. */
internal suspend fun <T> withReferenceDiscovery(
    discover: suspend () -> Unit,
    resolve: suspend () -> T,
): T = coroutineScope {
    val discovery = launch {
        try {
            discover()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Missing relay facts leave the established fallback ladder available.
        }
    }
    try { resolve() } finally { discovery.cancel() }
}
