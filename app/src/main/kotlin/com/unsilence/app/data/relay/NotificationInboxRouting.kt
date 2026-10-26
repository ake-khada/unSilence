package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.RelayList
import kotlinx.coroutines.flow.first

/** Target selection and dispatch shared by the live tail, connect and backfill. */
internal class NotificationInboxRouting(
    private val relayListFor: (String) -> RelayList?,
    private val registeredUrls: () -> Set<String>,
    private val connectedUrls: () -> List<String>,
    private val indexerUrls: () -> List<String>,
    /** Return the socket identity only when it accepted the request. */
    private val send: (String, String) -> Any?,
) {
    private class Live(val pubkey: String, val request: String, val fallbackUrls: Set<String>) {
        val attemptedUrls = mutableSetOf<String>()
        val acceptedBy = mutableMapOf<String, Any>()
    }
    private var live: Live? = null

    private fun declaredTargets(pubkey: String): List<String> {
        val snapshot = relayListFor(pubkey)
        val registered = registeredUrls()
        return notificationInboxRelays(snapshot?.read.orEmpty(), snapshot?.write.orEmpty())
            .filter { it in registered }
    }

    @Synchronized
    fun subscribe(pubkey: String, request: String): Int {
        val declared = declaredTargets(pubkey)
        val fallback = if (declared.isEmpty()) {
            connectedUrls().mapNotNull(::normalizeRelayUrl).distinct().take(4)
        } else emptyList()
        val urls = declared.ifEmpty { fallback }
        val current = Live(pubkey, request, fallback.toSet())
        // Keep intent even when bootstrap could not register any target yet.
        live = current
        urls.forEach { dispatch(current, it) }
        return urls.size
    }

    @Synchronized
    fun replay(url: String, socketIdentity: Any) {
        val current = live ?: return
        val declared = declaredTargets(current.pubkey)
        // Declared membership follows current metadata; fallback stays pinned to
        // this subscription's original selection even if connection order changes.
        if (url !in declared && (declared.isNotEmpty() || url !in current.fallbackUrls)) return
        if (current.acceptedBy[url] === socketIdentity) return
        dispatch(current, url)
    }

    private fun dispatch(current: Live, url: String) {
        current.attemptedUrls += url
        send(url, current.request)?.let { current.acceptedBy[url] = it }
    }

    /** Used by every pooled listener, including a first connection admitted after bootstrap. */
    suspend fun replayWhenConnected(connection: RelayConnection, isCurrent: () -> Boolean) {
        val state = connection.state.first { it != RelayState.CONNECTING }
        if (state == RelayState.CONNECTED && isCurrent()) replay(connection.url, connection)
    }

    @Synchronized
    fun close() {
        val current = live
        live = null
        current?.attemptedUrls?.forEach { send(it, """["CLOSE","own-notif-live"]""") }
    }

    /** Declared relays first; retain the existing connected non-indexer sweep. */
    suspend fun backfill(pubkey: String, fetchBatch: suspend (List<String>) -> Unit): Int {
        val declared = declaredTargets(pubkey)
        val indexers = indexerUrls().mapNotNull(::normalizeRelayUrl).toSet()
        val others = connectedUrls().mapNotNull(::normalizeRelayUrl).distinct()
            .filter { it !in indexers && it !in declared }
        val targets = declared + others
        for (batch in targets.chunked(6)) fetchBatch(batch)
        return targets.size
    }
}

/** Own NIP-65 inbox first, retaining own write relays for compatibility. */
internal fun notificationInboxRelays(readRelays: List<String>, writeRelays: List<String>): List<String> =
    (readRelays + writeRelays).mapNotNull(::normalizeRelayUrl).distinct()
