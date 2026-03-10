package com.unsilence.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class FeedFilter(
    val showKind1: Boolean = true,
    val showKind6: Boolean = true,
    val showKind20: Boolean = true,
    val showKind21: Boolean = true,
    val showKind30023: Boolean = true,
    val sinceHours: Int? = null,
    val requireReposts: Boolean = false,
    val requireReactions: Boolean = false,
    val requireReplies: Boolean = false,
    val requireZaps: Boolean = false,
) {
    /** Kinds enabled by this filter. */
    val enabledKinds: List<Int> get() = buildList {
        if (showKind1)     add(1)
        if (showKind6)     add(6)
        if (showKind20)    add(20)
        if (showKind21)    add(21)
        if (showKind30023) add(30023)
    }

    /** True when any field differs from the defaults (used for the filter dot). */
    val isNonDefault: Boolean get() =
        sinceHours != null ||
        requireReposts || requireReactions || requireReplies || requireZaps ||
        !showKind1 || !showKind6 || !showKind20 || !showKind21 || !showKind30023

    companion object {
        val globalDefault = FeedFilter()
    }
}
