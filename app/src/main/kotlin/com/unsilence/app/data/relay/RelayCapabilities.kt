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

    /** Cumulative count of structural rejections. After MAX_STRIKES, relay is hard-skipped. */
    val strikes: Int = 0,

    /** Epoch ms of the most recent strike. */
    val lastStrikeAt: Long = 0L,

    /** Last CLOSED reason tail — for debugging, doesn't influence behavior. */
    val lastReason: String = "",
)
