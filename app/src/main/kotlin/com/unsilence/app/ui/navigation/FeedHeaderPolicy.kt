package com.unsilence.app.ui.navigation

import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.domain.model.GlobalFeedLens
import com.unsilence.app.domain.model.ShowType
import com.unsilence.app.domain.model.label
import com.unsilence.app.ui.feed.FeedType

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
