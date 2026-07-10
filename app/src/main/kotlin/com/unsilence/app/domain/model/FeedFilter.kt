package com.unsilence.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ShowType { ALL, TEXT, IMAGES, VIDEO, ARTICLES }

enum class ActivityPreset(val label: String) {
    ANY("Any"),
    DISCUSSED("Discussed"),
    POPULAR("Popular"),
    ZAPPED("Zapped"),
}

const val DISCUSSED_MIN_REPLIES = 1
const val POPULAR_MIN_REACTIONS = 10
const val ZAPPED_MIN_ZAP_SATS = 1L

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
        if (ShowType.ALL in showTypes) return listOf(1, 6, 16, 20, 21, 1068, 30023)
        return buildList {
            // TEXT, IMAGES, VIDEO all need kind 1 in the SQL query
            if (ShowType.TEXT in showTypes || ShowType.IMAGES in showTypes || ShowType.VIDEO in showTypes) add(1)
            if (ShowType.TEXT in showTypes) add(1068)
            if (ShowType.IMAGES in showTypes) add(20)
            if (ShowType.VIDEO in showTypes) add(21)
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

fun FeedFilter.activityPreset(): ActivityPreset = when {
    minReplies == 0 && minReposts == 0 && minReactions == 0 && minZapSats == 0L ->
        ActivityPreset.ANY
    minReplies == DISCUSSED_MIN_REPLIES && minReposts == 0 && minReactions == 0 && minZapSats == 0L ->
        ActivityPreset.DISCUSSED
    minReplies == 0 && minReposts == 0 && minReactions == POPULAR_MIN_REACTIONS && minZapSats == 0L ->
        ActivityPreset.POPULAR
    minReplies == 0 && minReposts == 0 && minReactions == 0 && minZapSats == ZAPPED_MIN_ZAP_SATS ->
        ActivityPreset.ZAPPED
    else -> ActivityPreset.ANY
}

fun FeedFilter.withActivityPreset(preset: ActivityPreset): FeedFilter = when (preset) {
    ActivityPreset.ANY -> copy(minReplies = 0, minReposts = 0, minReactions = 0, minZapSats = 0L)
    ActivityPreset.DISCUSSED -> copy(
        minReplies = DISCUSSED_MIN_REPLIES,
        minReposts = 0,
        minReactions = 0,
        minZapSats = 0L,
    )
    ActivityPreset.POPULAR -> copy(
        minReplies = 0,
        minReposts = 0,
        minReactions = POPULAR_MIN_REACTIONS,
        minZapSats = 0L,
    )
    ActivityPreset.ZAPPED -> copy(
        minReplies = 0,
        minReposts = 0,
        minReactions = 0,
        minZapSats = ZAPPED_MIN_ZAP_SATS,
    )
}

fun FeedFilter.summaryLabel(): String? {
    if (!isNonDefault) return null
    val parts = buildList {
        if (showTypes != setOf(ShowType.ALL)) {
            add(showTypes.sortedBy { it.ordinal }.joinToString("+") { it.label })
        }
        sinceHours?.let { add(sinceLabel(it)) }
        val preset = activityPreset()
        when {
            preset != ActivityPreset.ANY -> add(preset.label)
            minReplies > 0 || minReposts > 0 || minReactions > 0 || minZapSats > 0 -> add("Activity")
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

val ShowType.label: String
    get() = when (this) {
        ShowType.ALL -> "All"
        ShowType.TEXT -> "Text"
        ShowType.IMAGES -> "Images"
        ShowType.VIDEO -> "Video"
        ShowType.ARTICLES -> "Articles"
    }

fun sinceLabel(hours: Int): String = when (hours) {
    1 -> "1h"
    6 -> "6h"
    24 -> "24h"
    168 -> "7d"
    else -> "${hours}h"
}
