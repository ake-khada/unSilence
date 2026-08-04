package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeanNetworkPolicyTest {
    @Test
    fun `cellular and metered networks constrain speculative work`() {
        assertTrue(NetworkConditions(NetworkState.ONLINE, isMetered = true).isConstrained)
        assertTrue(NetworkConditions(NetworkState.ONLINE, isMetered = false, isCellular = true).isConstrained)
        assertFalse(NetworkConditions(NetworkState.ONLINE, isMetered = false, isCellular = false).isConstrained)
        assertTrue(NetworkConditions(NetworkState.UNKNOWN, isMetered = false).isConstrained)
    }

    @Test
    fun `constrained budget preserves narrow hydration but drops speculative media`() {
        val budget = assetWarmBudget(
            maxRows = 12,
            maxImages = 4,
            maxOg = 2,
            maxVideoThumbnails = 8,
            maxProfiles = 16,
            maxReferences = 4,
            maxArticles = 2,
            constrained = true,
            networkDown = false,
        )

        assertEquals(4, budget.rows)
        assertEquals(0, budget.images)
        assertEquals(0, budget.og)
        assertEquals(0, budget.videoThumbnails)
        assertEquals(4, budget.profiles)
        assertEquals(1, budget.references)
        assertEquals(0, budget.articles)
    }

    @Test
    fun `network down disables all speculative hydration`() {
        val budget = assetWarmBudget(12, 4, 2, 8, 16, 4, 2, constrained = true, networkDown = true)
        assertEquals(AssetWarmBudget(0, 0, 0, 0, 0, 0, 0), budget)
    }

    @Test
    fun `imeta aspect suppresses redundant dimension probe`() {
        assertTrue(hasUsableAspectMetadata(16f / 9f))
        assertFalse(hasUsableAspectMetadata(null))
        assertFalse(hasUsableAspectMetadata(0f))
        assertFalse(hasUsableAspectMetadata(Float.NaN))
    }

    @Test
    fun `profile eager engagement shrinks further on constrained links`() {
        assertEquals(30, profileEagerEngagementLimit(constrained = false))
        assertEquals(10, profileEagerEngagementLimit(constrained = true))
    }

    @Test
    fun `foreground reconnect prioritizes active feed and bounds ephemeral fanout`() {
        assertTrue(reconnectPriority(setOf(ConnectionPurpose.FEED_SUB)) < reconnectPriority(setOf(ConnectionPurpose.PERSISTENT)))
        assertTrue(reconnectPriority(setOf(ConnectionPurpose.PERSISTENT)) < reconnectPriority(setOf(ConnectionPurpose.FEED_WARM)))
        assertEquals(4, MAX_EPHEMERAL_CONNECTIONS)
    }

    @Test
    fun `follow refresh samples write relays and independent indexes`() {
        val targets = followRefreshRelayTargets(
            writeRelayUrls = listOf(
                "wss://write-one.example",
                "wss://write-two.example",
                "wss://write-three.example",
            ),
            indexRelayUrls = listOf(
                "wss://index-one.example",
                "wss://index-two.example",
            ),
            limit = 4,
        )

        assertEquals(
            listOf(
                "wss://write-one.example",
                "wss://write-two.example",
                "wss://index-one.example",
                "wss://index-two.example",
            ),
            targets,
        )
    }

    @Test
    fun `follow refresh deduplicates overlap without exceeding its cap`() {
        val targets = followRefreshRelayTargets(
            writeRelayUrls = listOf("wss://shared.example/", "invalid"),
            indexRelayUrls = listOf("wss://shared.example", "wss://index.example"),
            limit = 4,
        )

        assertEquals(listOf("wss://shared.example", "wss://index.example"), targets)
    }

    @Test
    fun `follow refresh skips fresh success and throttles a recent failed attempt`() {
        val now = 100_000L

        assertFalse(
            shouldRunFollowRefresh(
                forceRefresh = false,
                nowMs = now,
                lastSuccessMs = now - FOLLOW_REFRESH_FRESH_MS + 1,
                lastAttemptMs = null,
            ),
        )
        assertFalse(
            shouldRunFollowRefresh(
                forceRefresh = false,
                nowMs = now,
                lastSuccessMs = null,
                lastAttemptMs = now - FOLLOW_REFRESH_RETRY_MS + 1,
            ),
        )
    }

    @Test
    fun `forced publish preflight bypasses follow refresh freshness`() {
        val now = 100_000L

        assertTrue(
            shouldRunFollowRefresh(
                forceRefresh = true,
                nowMs = now,
                lastSuccessMs = now,
                lastAttemptMs = now,
            ),
        )
    }
}
