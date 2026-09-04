package com.unsilence.app.data.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRulesTest {

    /** Every canonical one-shot family must close and release its pooled slot on EOSE. */
    @Test
    fun `all one-shot prefixes are recognized`() {
        val prefixes = listOf(
            "account-metadata-",
            "kind3-",
            "kind10002-",
            "profiles-",
            "hint-profiles-",
            "src-profiles-",
            "hint-event-",
            "hint-batch-",
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
            "prefetch-",
            "mute-",
            "own-eng-",
            "eng-",
            "article-comments-",
            "article-addr-",
            "poll-responses-",
            "comment-replies-",
            "comment-parents-",
            "emoji-list-",
            "emoji-set-",
            "emoji-discover-",
            "setref-",
            "wot-10040-",
            "wot-30382-",
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
    fun `PERSISTENT routes home subs, BROWSE does not`() {
        assertTrue(SubscriptionRules.shouldReceiveHomeSubs(ConnectionPurpose.PERSISTENT))
        assertFalse(SubscriptionRules.shouldReceiveHomeSubs(ConnectionPurpose.BROWSE))
    }
}
