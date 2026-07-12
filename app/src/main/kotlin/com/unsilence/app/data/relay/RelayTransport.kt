package com.unsilence.app.data.relay

/**
 * Minimal transport surface for the Subscription primitive.
 * Implemented by [RelayPool] in production; faked in tests.
 */
interface RelayTransport {
    /**
     * Ensure connections to [relayUrls]. Returns count of connections ready
     * within [timeoutMs]. Already-connected relays count immediately.
     */
    suspend fun connectAndAwait(
        relayUrls: List<String>,
        timeoutMs: Long = 5_000,
        forceEvict: Boolean = false,
    ): Int

    /**
     * Send raw JSON message to a specific relay. Returns false if no connection
     * exists for that URL.
     */
    fun sendToRelay(url: String, msg: String): Boolean

    /** True when the relay has been marked auth-unavailable this session. */
    fun isAuthUnavailable(url: String): Boolean = false

    /** True while the relay is inside a server-triggered rate-limit cooldown. */
    fun isRateLimited(url: String): Boolean = false
}
