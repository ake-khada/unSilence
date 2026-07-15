package com.unsilence.app.ui.navigation

import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.domain.model.GlobalFeedLens
import com.unsilence.app.domain.model.ShowType
import com.unsilence.app.domain.model.label
import com.unsilence.app.ui.feed.FeedType

internal const val PULL_STRETCH_MAX_FACTOR = 1.6f
internal const val REFRESH_SWEEP_PERIOD_MS = 1_400
internal const val REFRESH_SWEEP_SEGMENT_FRACTION = 0.18f
internal const val LENS_TINT_TRANSITION_MS = 300

internal data class FeedHeaderElements(
    val sourceLabel: String,
    val lens: GlobalFeedLens?,
    val activeShowTypes: List<ShowType>,
) {
    val showTrustChip: Boolean
        get() = lens != null

    val showFormatChip: Boolean
        get() = activeShowTypes.isNotEmpty()

    val formatContentDescription: String?
        get() = when (activeShowTypes.size) {
            0 -> null
            1 -> "${activeShowTypes.single().label} filter"
            else -> activeShowTypes.joinToString(", ") { it.label } + " filters"
        }
}

internal fun feedHeaderElements(
    feedType: FeedType,
    lens: GlobalFeedLens,
    filter: FeedFilter,
): FeedHeaderElements {
    val activeShowTypes = filter.showTypes
        .asSequence()
        .filter { it != ShowType.ALL }
        .sortedBy { it.ordinal }
        .toList()
    return FeedHeaderElements(
        sourceLabel = when (feedType) {
            FeedType.Following -> "Following"
            FeedType.Global -> "Global"
            is FeedType.SingleRelay -> feedType.displayLabel
            is FeedType.RelaySet -> feedType.name
        },
        lens = lens.takeIf { feedType is FeedType.Global },
        activeShowTypes = activeShowTypes,
    )
}

internal fun pullStretchFactor(fraction: Float): Float =
    1f + (PULL_STRETCH_MAX_FACTOR - 1f) * fraction.coerceIn(0f, 1f)

internal fun feedHeaderMotionEnabled(
    isPowerSaveMode: Boolean,
    animatorDurationScale: Float,
): Boolean = !isPowerSaveMode && animatorDurationScale > 0f

internal fun effectivePullStretchFactor(
    fraction: Float,
    motionEnabled: Boolean,
): Float = when {
    motionEnabled -> pullStretchFactor(fraction)
    fraction >= 1f -> PULL_STRETCH_MAX_FACTOR
    else -> 1f
}

internal fun shouldAnimateLensTransition(
    previous: GlobalFeedLens?,
    current: GlobalFeedLens?,
    motionEnabled: Boolean,
): Boolean = motionEnabled && previous != null && current != null && previous != current
