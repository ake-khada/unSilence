package com.unsilence.app.data.relay

import kotlinx.serialization.Serializable

/**
 * Per-relay learned capabilities. Persisted to DataStore via [RelayCapabilitiesStore].
 * Populated by RelayPool's CLOSED handler when relays send structural rejections.
 * Consulted by REQ builders to skip or adapt requests.
 *
 * Only "structural" rejections are recorded — transient ones (rate-limited, duplicate,
 * pow) are ignored because they don't teach us anything about future queries.
 */
@Serializable
data class RelayCapabilities(
    /** Relay returned `auth-required:` on a CLOSED. Skip until we support NIP-42 auth for it. */
    val authRequired: Boolean = false,

    /** Relay returned `restricted:` indicating permission / whitelist. Same handling as authRequired. */
    val restricted: Boolean = false,

    /** Transport strike count. At the threshold the transport half-open cooldown applies. */
    val strikes: Int = 0,

    /** Consecutive structural CLOSED rejections. Drives capability-specific backoff. */
    val consecutiveCapabilityFailures: Int = 0,

    /** Epoch ms of the most recent structural CLOSED rejection. */
    val lastCapabilityStrikeAt: Long = 0L,

    /** Most recent structural CLOSED reason, separate from transport diagnostics. */
    val lastCapabilityReason: String = "",

    /** Learned from an explicit "search filter is required" CLOSED response.
     *  Such relays remain valid NIP-50 targets but must not receive general REQs. */
    val searchOnly: Boolean = false,

    /** Epoch ms of the most recent transport strike. */
    val lastStrikeAt: Long = 0L,

    /** Last transport failure reason — for debugging and retry policy. */
    val lastReason: String = "",

    /** Cross-session consecutive connect/DNS failures (NOT during network-down).
     *  At [DEAD_RELAY_THRESHOLD], the relay is considered dead and excluded from
     *  fanout / shouldSkip. Reset on any successful connection (onOpen). */
    val deadFailCount: Int = 0,

    /** Epoch ms of last successful reprobe attempt for a dead relay.
     *  Dead relays are re-probed once per [DEAD_RELAY_REPROBE_MS]. */
    val lastProbeAt: Long = 0L,

    /** Consecutive transport failures (ANY reason, NOT during network-down), reset on any
     *  successful connection. Drives integral-relay cooldown escalation (H20b). Distinct
     *  from [deadFailCount], which counts DNS-only toward the permanent denylist
     *  (CONNECT_TIMEOUT excluded, H18.4): a TCP-blackholed integral relay times out forever
     *  without ever incrementing deadFailCount, so escalation needs its own counter. */
    val consecutiveFailures: Int = 0,
)
