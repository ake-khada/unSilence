package com.unsilence.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ShowType { ALL, TEXT, IMAGES, VIDEO, ARTICLES, REPOSTS }

@Serializable
data class FeedFilter(
    val showTypes: Set<ShowType> = setOf(ShowType.ALL),
    val sinceHours: Int? = null,
    val minReplies: Int = 0,
    val minReposts: Int = 0,
    val minReactions: Int = 0,
    val minZapSats: Long = 0,
) {
    /**
     * Kinds to pass to the feed query. When specific types are selected,
     * we include all kinds that COULD match — post-query filtering in the
     * ViewModel handles Text vs Images vs Video within kind 1.
     */
    val enabledKinds: List<Int> get() {
        if (ShowType.ALL in showTypes) return listOf(1, 6, 20, 21, 30023)
        return buildList {
            // TEXT, IMAGES, VIDEO all need kind 1 in the SQL query
            if (ShowType.TEXT in showTypes || ShowType.IMAGES in showTypes || ShowType.VIDEO in showTypes) add(1)
            if (ShowType.IMAGES in showTypes) add(20)
            if (ShowType.VIDEO in showTypes) add(21)
            if (ShowType.REPOSTS in showTypes) add(6)
            if (ShowType.ARTICLES in showTypes) add(30023)
        }.distinct()
    }

    /** Whether post-query media filtering is needed (Text/Images/Video selected without ALL). */
    val needsMediaFilter: Boolean get() =
        ShowType.ALL !in showTypes &&
        (ShowType.TEXT in showTypes || ShowType.IMAGES in showTypes || ShowType.VIDEO in showTypes)

    /** True when any field differs from the defaults (used for the filter dot). */
    val isNonDefault: Boolean get() =
        showTypes != setOf(ShowType.ALL) ||
        sinceHours != null ||
        minReplies > 0 || minReposts > 0 || minReactions > 0 || minZapSats > 0

    companion object {
        val globalDefault = FeedFilter()
    }
}
