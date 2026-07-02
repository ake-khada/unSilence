package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure unit tests for NIP-42 auth re-challenge handling + give-up logic.
 *
 * Mirrors the extracted-logic pattern of ConnectionLifecycleTest —
 * tests the decision logic without needing Android context or real WebSockets.
 */
class RelayPoolAuthTest {

    // ── Simulated auth state (mirrors RelayPool fields) ─────────────────

    private val authenticatedRelays = ConcurrentHashMap.newKeySet<String>()
    private val authInFlight = ConcurrentHashMap.newKeySet<String>()
    private val authRejectionStreak = ConcurrentHashMap<String, Int>()
    private val authUnavailableRelays = ConcurrentHashMap.newKeySet<String>()
    private val optimisticAuthUsed = ConcurrentHashMap.newKeySet<String>()
    private val pendingChallenges = ConcurrentHashMap<String, String>()
    private var lastCompleteAuthReal: Boolean? = null
    private var authSent = false
    private var reconnectEmitted = false

    @Before
    fun reset() {
        authenticatedRelays.clear()
        authInFlight.clear()
        authRejectionStreak.clear()
        authUnavailableRelays.clear()
        optimisticAuthUsed.clear()
        pendingChallenges.clear()
        lastCompleteAuthReal = null
        authSent = false
        reconnectEmitted = false
    }

    // ── Simulated handlers (logic extracted from RelayPool) ─────────────

    /** Models handleAuthChallenge decision logic. */
    private fun simulateAuthChallenge(url: String, challenge: String = "challenge"): String {
        val previous = pendingChallenges.put(url, challenge)
        if (url in authUnavailableRelays) return "unavailable-skip"
        if (previous == challenge && url in authenticatedRelays) return "duplicate-skip"
        if (url in authenticatedRelays) {
            if (optimisticAuthUsed.remove(url)) {
                authRejectionStreak.merge(url, 1, Int::plus)
            }
            authenticatedRelays.remove(url)
            // falls through to re-auth
        }
        if (!authInFlight.add(url)) return "in-flight-skip"
        authSent = true
        return "sent"
    }

    /** Models completeAuth(real). */
    private fun simulateCompleteAuth(url: String, real: Boolean) {
        authenticatedRelays.add(url)
        authInFlight.remove(url)
        if (real) {
            authRejectionStreak.remove(url)
            optimisticAuthUsed.remove(url)
        } else {
            optimisticAuthUsed.add(url)
        }
        lastCompleteAuthReal = real
        reconnectEmitted = true
    }

    /** Models CLOSED auth-required handler. Returns action taken. */
    private fun simulateAuthRequiredClosed(url: String): String {
        if (url in authUnavailableRelays) return "ignored-unavailable"
        val streak = authRejectionStreak.merge(url, 1, Int::plus) ?: 1
        authenticatedRelays.remove(url)
        optimisticAuthUsed.remove(url)
        return if (streak >= RelayPool.MAX_AUTH_REJECTIONS) {
            authUnavailableRelays.add(url)
            authInFlight.remove(url)
            "give-up"
        } else {
            "re-auth-streak-$streak"
        }
    }

    /** Models optimistic fallback decision. */
    private fun shouldFireOptimistic(url: String): Boolean =
        (authRejectionStreak[url] ?: 0) == 0

    /** Models a second no-OK timeout after optimism has already failed. */
    private fun simulateNoOkTimeout(url: String): String {
        authInFlight.remove(url)
        val streak = authRejectionStreak.merge(url, 1, Int::plus) ?: 1
        return if (streak >= RelayPool.MAX_AUTH_NO_OK_STREAK) {
            authUnavailableRelays.add(url)
            "give-up"
        } else "no-ok-$streak"
    }

    // ── Tests ───────────────────────────────────────────────────────────

    @Test
    fun `fresh AUTH challenge while authenticated triggers re-auth`() {
        val url = "wss://aggr.nostr.land"
        authenticatedRelays.add(url)
        assertTrue(url in authenticatedRelays)

        val result = simulateAuthChallenge(url)

        assertEquals("sent", result)
        assertFalse("stale auth should be cleared", url in authenticatedRelays)
        assertTrue(authSent)
    }

    @Test
    fun `duplicate completed challenge is ignored`() {
        val url = "wss://relay.example"
        pendingChallenges[url] = "same"
        authenticatedRelays.add(url)

        assertEquals("duplicate-skip", simulateAuthChallenge(url, "same"))
        assertTrue(url in authenticatedRelays)
        assertFalse(authSent)
    }

    @Test
    fun `rechallenge after optimistic completion consumes optimism and raises streak`() {
        val url = "wss://relay.example"
        simulateCompleteAuth(url, real = false)

        assertEquals("sent", simulateAuthChallenge(url, "new-challenge"))
        assertEquals(1, authRejectionStreak[url])
        assertFalse(shouldFireOptimistic(url))
    }

    @Test
    fun `repeated no OK timeouts eventually quarantine relay`() {
        val url = "wss://relay.example"
        authRejectionStreak[url] = 1
        assertEquals("give-up", simulateNoOkTimeout(url))
        assertTrue(url in authUnavailableRelays)
    }

    @Test
    fun `auth-required CLOSED increments rejection streak`() {
        val url = "wss://aggr.nostr.land"
        authenticatedRelays.add(url)

        val result = simulateAuthRequiredClosed(url)

        assertEquals("re-auth-streak-1", result)
        assertEquals(1, authRejectionStreak[url])
        assertFalse("should no longer be marked authenticated", url in authenticatedRelays)
    }

    @Test
    fun `streak reaching MAX marks relay unavailable`() {
        val url = "wss://aggr.nostr.land"

        // Simulate MAX_AUTH_REJECTIONS consecutive rejections
        for (i in 1 until RelayPool.MAX_AUTH_REJECTIONS) {
            val result = simulateAuthRequiredClosed(url)
            assertEquals("re-auth-streak-$i", result)
        }
        assertFalse(url in authUnavailableRelays)

        val result = simulateAuthRequiredClosed(url)
        assertEquals("give-up", result)
        assertTrue(url in authUnavailableRelays)
    }

    @Test
    fun `real OK resets rejection streak`() {
        val url = "wss://relay.example"
        authRejectionStreak[url] = 2
        authInFlight.add(url)

        simulateCompleteAuth(url, real = true)

        assertFalse("streak should be cleared", authRejectionStreak.containsKey(url))
        assertTrue(url in authenticatedRelays)
        assertEquals(true, lastCompleteAuthReal)
    }

    @Test
    fun `optimistic completion does not clear streak`() {
        val url = "wss://relay.example"
        authRejectionStreak[url] = 1
        authInFlight.add(url)

        simulateCompleteAuth(url, real = false)

        assertEquals(1, authRejectionStreak[url])
        assertTrue(url in authenticatedRelays)
        assertEquals(false, lastCompleteAuthReal)
    }

    @Test
    fun `optimistic fallback fires only when streak is zero`() {
        val url = "wss://relay.example"

        assertTrue("should fire with no streak", shouldFireOptimistic(url))

        authRejectionStreak[url] = 1
        assertFalse("should NOT fire with streak > 0", shouldFireOptimistic(url))
    }

    @Test
    fun `optimistic fallback suppressed once relay has rejected`() {
        val url = "wss://aggr.nostr.land"

        // Initial: clean record → optimistic allowed
        assertTrue(shouldFireOptimistic(url))

        // First rejection bumps streak
        simulateAuthRequiredClosed(url)
        assertEquals(1, authRejectionStreak[url])

        // Now optimistic should be suppressed
        assertFalse(shouldFireOptimistic(url))
    }

    @Test
    fun `auth-required on unavailable relay does not re-auth`() {
        val url = "wss://aggr.nostr.land"
        authUnavailableRelays.add(url)

        val result = simulateAuthRequiredClosed(url)

        assertEquals("ignored-unavailable", result)
        // Streak should not have been bumped
        assertFalse(authRejectionStreak.containsKey(url))
    }

    @Test
    fun `handleAuthChallenge skips unavailable relay`() {
        val url = "wss://aggr.nostr.land"
        authUnavailableRelays.add(url)

        val result = simulateAuthChallenge(url)

        assertEquals("unavailable-skip", result)
        assertFalse(authSent)
    }

    @Test
    fun `clearCaches clears auth session state`() {
        val url = "wss://aggr.nostr.land"
        authRejectionStreak[url] = 2
        authUnavailableRelays.add(url)
        authenticatedRelays.add(url)
        authInFlight.add(url)

        // Simulate clearCaches
        authRejectionStreak.clear()
        authUnavailableRelays.clear()
        authenticatedRelays.clear()
        authInFlight.clear()
        optimisticAuthUsed.clear()
        pendingChallenges.clear()

        assertFalse(authRejectionStreak.containsKey(url))
        assertFalse(url in authUnavailableRelays)
        assertFalse(url in authenticatedRelays)
        assertFalse(url in authInFlight)
    }

    // ── Integration scenario: paid subscriber flow ──────────────────────

    @Test
    fun `paid subscriber re-auth succeeds and resets streak`() {
        val url = "wss://aggr.nostr.land"

        // Step 1: initial optimistic auth (no OK received)
        simulateCompleteAuth(url, real = false)
        assertTrue(url in authenticatedRelays)

        // Step 2: relay rejects REQ with auth-required
        val r1 = simulateAuthRequiredClosed(url)
        assertEquals("re-auth-streak-1", r1)
        assertFalse(url in authenticatedRelays)

        // Step 3: re-auth sent, this time relay sends real OK
        simulateAuthChallenge(url)
        assertTrue(authSent)
        simulateCompleteAuth(url, real = true)

        // Streak reset, authenticated
        assertTrue(url in authenticatedRelays)
        assertFalse(authRejectionStreak.containsKey(url))
        assertFalse(url in authUnavailableRelays)
    }

    @Test
    fun `non-subscriber gives up after MAX rejections`() {
        val url = "wss://aggr.nostr.land"

        // Simulate repeated auth-required without any successful re-auth
        for (i in 1..RelayPool.MAX_AUTH_REJECTIONS) {
            simulateAuthRequiredClosed(url)
        }

        assertTrue(url in authUnavailableRelays)

        // Further challenges are skipped
        authSent = false
        val result = simulateAuthChallenge(url)
        assertEquals("unavailable-skip", result)
        assertFalse(authSent)
    }
}
