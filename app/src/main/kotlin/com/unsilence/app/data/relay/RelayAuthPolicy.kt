package com.unsilence.app.data.relay

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Maximum number of distinct NIP-42 signing attempts a relay may trigger in one
 * login session. Duplicate and already-in-flight challenges are filtered before
 * this budget is consumed.
 */
internal const val MAX_RELAY_AUTH_ATTEMPTS_PER_SESSION = 3

internal enum class RelayAuthAdmission {
    READY,
    INELIGIBLE,
    UNAVAILABLE,
    ATTEMPT_LIMIT,
}

/**
 * Builds the live set of relays to which the user has chosen to attest their
 * identity. Inputs deliberately exclude hint, follow-pack, WoT, and arbitrary
 * one-shot relays.
 */
internal fun configuredAuthRelayUrls(
    integralRelayUrls: Collection<String>,
    indexerRelayUrls: Collection<String>,
    ownReadRelayUrls: Collection<String>,
    ownWriteRelayUrls: Collection<String>,
    ownSearchRelayUrls: Collection<String>,
): Set<String> = buildSet {
    sequenceOf(
        integralRelayUrls,
        indexerRelayUrls,
        ownReadRelayUrls,
        ownWriteRelayUrls,
        ownSearchRelayUrls,
    ).flatten().mapNotNull(::normalizeRelayUrl).forEach(::add)
}

/**
 * Session-scoped NIP-42 policy state. Policy-ineligible quarantine is tracked
 * separately from protocol failures so adding a relay to the user's configured
 * set can heal only that reason without erasing a genuine rejection/cap.
 */
internal class RelayAuthSessionPolicy(
    private val maxAttempts: Int = MAX_RELAY_AUTH_ATTEMPTS_PER_SESSION,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    private val attemptCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val policyIneligibleRelays = ConcurrentHashMap.newKeySet<String>()

    fun evaluateEligibility(
        url: String,
        configuredUrls: Set<String>,
        unavailableRelays: MutableSet<String>,
        rejectionStreak: Int,
    ): RelayAuthAdmission {
        if (url !in configuredUrls) {
            val newlyIneligible = policyIneligibleRelays.add(url)
            unavailableRelays.add(url)
            return if (newlyIneligible) {
                RelayAuthAdmission.INELIGIBLE
            } else {
                RelayAuthAdmission.UNAVAILABLE
            }
        }

        // A relay configured after an earlier hint-only challenge is eligible
        // on its next challenge. Do not clear a real rejection or an exhausted
        // signing budget while healing the policy-only quarantine.
        if (policyIneligibleRelays.remove(url) &&
            rejectionStreak <= 0 &&
            !isAttemptLimitReached(url)
        ) {
            unavailableRelays.remove(url)
        }

        return if (url in unavailableRelays) {
            RelayAuthAdmission.UNAVAILABLE
        } else {
            RelayAuthAdmission.READY
        }
    }

    fun reserveAttempt(
        url: String,
        unavailableRelays: MutableSet<String>,
    ): RelayAuthAdmission {
        val counter = attemptCounts.computeIfAbsent(url) { AtomicInteger(0) }
        while (true) {
            val current = counter.get()
            if (current >= maxAttempts) {
                unavailableRelays.add(url)
                return RelayAuthAdmission.ATTEMPT_LIMIT
            }
            if (counter.compareAndSet(current, current + 1)) {
                return RelayAuthAdmission.READY
            }
        }
    }

    internal fun attemptCount(url: String): Int = attemptCounts[url]?.get() ?: 0

    private fun isAttemptLimitReached(url: String): Boolean =
        attemptCount(url) >= maxAttempts

    fun clear() {
        attemptCounts.clear()
        policyIneligibleRelays.clear()
    }
}
