package com.unsilence.app.data.relay

sealed class CoverageIntent {
    abstract val scopeType: String
    abstract val scopeKey: String
    abstract val relaySetId: String
    abstract val staleAfterMs: Long

    data class HomeFeed(
        val until: Long = System.currentTimeMillis() / 1000,
    ) : CoverageIntent() {
        override val scopeType = "home"
        override val scopeKey = "home"
        override val relaySetId = "global"
        override val staleAfterMs = 60_000L
    }

    data class UserPosts(val pubkey: String) : CoverageIntent() {
        override val scopeType = "user_posts"
        override val scopeKey = pubkey
        override val relaySetId = "global"
        override val staleAfterMs = 300_000L
    }

    data class Thread(val rootId: String) : CoverageIntent() {
        override val scopeType = "thread"
        override val scopeKey = rootId
        override val relaySetId = "global"
        override val staleAfterMs = 120_000L
    }

    data class ProfileMeta(val pubkey: String) : CoverageIntent() {
        override val scopeType = "profile_meta"
        override val scopeKey = pubkey
        override val relaySetId = "indexers"
        override val staleAfterMs = 3600_000L
    }

    data class Engagement(val eventIds: List<String>) : CoverageIntent() {
        override val scopeType = "engagement"
        override val scopeKey = eventIds.sorted().joinToString(",")
            .let {
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(it.toByteArray())
                    .joinToString("") { b -> "%02x".format(b) }
                    .take(16)
            }
        override val relaySetId = "global"
        override val staleAfterMs = 300_000L
    }
}
