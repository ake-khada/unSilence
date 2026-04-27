package com.unsilence.app.data.relay

/**
 * Registry for raw-message taps. EventProcessor implements this and fires
 * registered taps for every relay message received, before its own dedup.
 *
 * Subscription registers a single tap that demuxes incoming messages by
 * subId and dispatches to the appropriate per-subscription callbacks.
 */
interface TapRegistration {
    /** Register a tap. Idempotent — same instance registered twice still fires once. */
    fun registerTap(tap: RelayMessageTap)

    /** Unregister a tap. Idempotent — tap not present is a no-op. */
    fun unregisterTap(tap: RelayMessageTap)
}

/**
 * Tap function type. Called for every raw message from any connected relay
 * before EventProcessor's dedup or insertion. Implementations must be
 * non-blocking — heavy work should dispatch to a background scope.
 *
 * Defined as a `fun interface` so call sites can pass a lambda or a class.
 */
fun interface RelayMessageTap {
    fun onMessage(raw: String, relayUrl: String)
}
