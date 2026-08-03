package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.NostrEvent

/**
 * Registry for relay-message taps. EventProcessor implements this and fires
 * registered taps with either a verified event envelope or a raw control
 * message (EOSE/CLOSED/etc.).
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
 * A message already classified by [EventProcessor]. EVENT payloads are decoded,
 * id-checked, and signature-verified exactly once before becoming
 * [VerifiedEvent]. Control messages remain raw because Subscription only needs
 * their inexpensive type/subscription-id fields.
 */
sealed interface RelayTapMessage {
    data class VerifiedEvent(
        val subscriptionId: String,
        val event: NostrEvent,
    ) : RelayTapMessage

    data class Control(
        val raw: String,
        val relayUrl: String,
    ) : RelayTapMessage
}

/**
 * Tap function type. Implementations must be non-blocking — heavy work should
 * dispatch to a background scope.
 *
 * Defined as a `fun interface` so call sites can pass a lambda or a class.
 */
fun interface RelayMessageTap {
    fun onMessage(message: RelayTapMessage)
}
