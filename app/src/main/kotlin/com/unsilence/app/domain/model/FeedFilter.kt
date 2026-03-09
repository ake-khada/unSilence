package com.unsilence.app.domain.model

import kotlinx.serialization.Serializable

enum class ContentType { ALL, NOTES_ONLY, REPLIES_ONLY }

@Serializable
data class FeedFilter(
    val contentType: ContentType = ContentType.NOTES_ONLY,
    val hideSensitive: Boolean = false,
    val showKind1: Boolean = true,
    val showKind6: Boolean = true,
    val showKind20: Boolean = true,
    val showKind21: Boolean = true,
    val showKind30023: Boolean = true,
    val minReactions: Int = 0,
    val minZapAmount: Long = 0,
    val minReplies: Int = 0,
    val relaySetId: String? = null,
) {
    /** Kinds enabled by this filter. */
    val enabledKinds: List<Int> get() = buildList {
        if (showKind1)     add(1)
        if (showKind6)     add(6)
        if (showKind20)    add(20)
        if (showKind21)    add(21)
        if (showKind30023) add(30023)
    }

    /** True when any field differs from the per-relay-set defaults (used for the filter dot). */
    val isNonDefault: Boolean get() =
        contentType   != ContentType.NOTES_ONLY ||
        hideSensitive != false ||
        !showKind1    || !showKind6 || !showKind20 || !showKind21 || !showKind30023 ||
        minReactions  != 0 ||
        minZapAmount  != 0L ||
        minReplies    != 0

    companion object {
        /** No reaction filter — show everything. WoT spam filtering (NIP-85) will replace this. */
        val globalDefault = FeedFilter(minReactions = 0)
    }
}
