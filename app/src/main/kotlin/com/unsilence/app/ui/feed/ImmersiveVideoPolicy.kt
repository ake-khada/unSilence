package com.unsilence.app.ui.feed

import androidx.media3.common.Player
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.domain.model.ShowType

private val IMMERSIVE_VIDEO_KINDS = setOf(1, 21, 22, 34235, 34236)

internal data class ImmersiveVideoItem(
    val row: FeedRow,
    val video: VideoRenderModel,
)

internal fun FeedFilter.isImmersiveVideoMode(): Boolean =
    showTypes == setOf(ShowType.VIDEO)

internal fun shouldClearRenderedFrame(reason: Int): Boolean =
    reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT

/**
 * Select only directly playable video rows. VideoRenderModel deliberately excludes
 * YouTube page URLs, so a YouTube-only kind-1 post never enters the pager.
 */
internal fun selectImmersiveVideoItems(
    rows: List<FeedRow>,
    videoModelsFor: (String) -> List<VideoRenderModel>,
): List<ImmersiveVideoItem> = rows.mapNotNull { row ->
    if (row.kind !in IMMERSIVE_VIDEO_KINDS) return@mapNotNull null
    videoModelsFor(row.id).firstOrNull()?.let { ImmersiveVideoItem(row, it) }
}

/** Exactly one next item is eligible, and never while Battery Saver is active. */
internal fun immersivePreloadIndex(
    currentIndex: Int,
    itemCount: Int,
    isPowerSaveMode: Boolean,
): Int? {
    if (isPowerSaveMode || currentIndex !in 0 until itemCount) return null
    return (currentIndex + 1).takeIf { it < itemCount }
}

internal enum class FilterIconKind {
    GRID,
    TEXT,
    IMAGE,
    VIDEO,
    ARTICLE,
}

internal fun filterIconKind(showType: ShowType): FilterIconKind = when (showType) {
    ShowType.ALL -> FilterIconKind.GRID
    ShowType.TEXT -> FilterIconKind.TEXT
    ShowType.IMAGES -> FilterIconKind.IMAGE
    ShowType.VIDEO -> FilterIconKind.VIDEO
    ShowType.ARTICLES -> FilterIconKind.ARTICLE
}
