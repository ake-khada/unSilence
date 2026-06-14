package com.unsilence.app.data.relay

/**
 * Pure subscription routing rules extracted from RelayPool.
 * This is the canonical source of truth for EOSE close behavior
 * and home-sub routing eligibility.
 *
 * RelayPool delegates to these functions. Tests verify the rules
 * without needing WebSocket infrastructure.
 */
object SubscriptionRules {

    /**
     * One-shot subscription prefixes: relay sends CLOSE after EOSE.
     *
     * 28 prefixes (emoji/hint-batch/setref added for slot leak fix).
     */
    private val oneShotPrefixes = listOf(
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
        "emoji-list-",
        "emoji-set-",
        "emoji-discover-",
        "setref-",
    )

    fun isOneShotSubscription(subId: String): Boolean =
        oneShotPrefixes.any { subId.startsWith(it) }

    /**
     * Only PERSISTENT-purpose relays receive home feed subscriptions.
     * BROWSE relays must not receive these.
     */
    fun shouldReceiveHomeSubs(purpose: ConnectionPurpose): Boolean =
        purpose == ConnectionPurpose.PERSISTENT
}
