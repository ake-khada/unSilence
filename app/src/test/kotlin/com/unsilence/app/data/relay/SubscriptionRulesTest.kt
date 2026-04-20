package com.unsilence.app.data.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRulesTest {

    /**
     * All 19 one-shot prefixes verified against RelayPool.isOneShotSubscription().
     * Was 21 before engagement consolidation (3 engagement-{replies,reactions,zaps}-
     * merged into single "engagement-" prefix).
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
            "engagement-",
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
