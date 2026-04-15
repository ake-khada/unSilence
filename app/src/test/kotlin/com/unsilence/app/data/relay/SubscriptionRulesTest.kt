package com.unsilence.app.data.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRulesTest {

    /**
     * All 21 one-shot prefixes verified against RelayPool.isOneShotSubscription()
     * at commit 201d067. Sprint 0 inventory said "17 one-shot prefix types" —
     * actual count is 21.
     */
    @Test
    fun `all one-shot prefixes are recognized`() {
        val prefixes = listOf(
            "kind3-",
            "kind10002-",
            "profiles-",
            "hint-profiles-",
            "src-profiles-",
            "hint-event-",
            "search-",
            "older-",
            "relay-ecosystem-",
            "thread-event-",
            "thread-replies-",
            "thread-reactions-",
            "thread-zaps-",
            "user-posts-",
            "user-longform-",
            "user-engagement-",
            "engagement-replies-",
            "engagement-reactions-",
            "engagement-zaps-",
            "batch-events-",
            "trust-scores-",
        )

        for (prefix in prefixes) {
            assertTrue(
                "Expected prefix to be one-shot: $prefix",
                SubscriptionRules.isOneShotSubscription(prefix + "12345"),
            )
        }
    }

    @Test
    fun `persistent prefixes are not one-shot`() {
        val persistentPrefixes = listOf(
            "feed-posts-",
            "feed-media-",
            "feed-longform-",
            "follows-",
            "notifs-",
            "browse-",
        )

        for (prefix in persistentPrefixes) {
            assertFalse(
                "Expected prefix to be persistent: $prefix",
                SubscriptionRules.isOneShotSubscription(prefix + "12345"),
            )
        }
    }

    @Test
    fun `PERSISTENT routes home subs, BROWSE and OUTBOX do not`() {
        assertTrue(SubscriptionRules.shouldReceiveHomeSubs(ConnectionPurpose.PERSISTENT))
        assertFalse(SubscriptionRules.shouldReceiveHomeSubs(ConnectionPurpose.BROWSE))
        assertFalse(SubscriptionRules.shouldReceiveHomeSubs(ConnectionPurpose.OUTBOX))
    }
}
