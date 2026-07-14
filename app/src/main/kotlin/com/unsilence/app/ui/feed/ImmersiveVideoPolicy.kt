package com.unsilence.app.ui.feed

import androidx.media3.common.Player
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.domain.model.ShowType

private val IMMERSIVE_VIDEO_KINDS = setOf(1, 21, 22, 34235, 34236)

internal const val HIGH_BITRATE_VIDEO_BPS = 4_000_000L
internal const val MAX_RESILIENT_STARTUP_BUFFER_MS = 1_500L
private const val MIN_RESILIENT_STARTUP_BUFFER_MS = 500L

internal data class ImmersiveVideoItem(
    val row: FeedRow,
    val video: VideoRenderModel,
)

internal fun FeedFilter.isImmersiveVideoMode(): Boolean =
    showTypes == setOf(ShowType.VIDEO)

internal fun shouldClearRenderedFrame(reason: Int): Boolean =
    reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT

/**
 * Keep an immersive session stable while the live feed mutates. Existing rows
 * never move or disappear; only novel rows after the oldest shared row append.
 */
internal fun mergeImmersiveItems(
    current: List<ImmersiveVideoItem>,
    incoming: List<ImmersiveVideoItem>,
): List<ImmersiveVideoItem> {
    if (current.isEmpty()) return incoming.distinctBy { it.row.id }
    if (incoming.isEmpty()) return current

    val currentIds = current.asSequence().map { it.row.id }.toHashSet()
    val oldestSharedIndex = incoming.indexOfLast { it.row.id in currentIds }
    if (oldestSharedIndex < 0) return current

    val appends = incoming.asSequence()
        .drop(oldestSharedIndex + 1)
        .filter { it.row.id !in currentIds }
        .distinctBy { it.row.id }
        .toList()
    return if (appends.isEmpty()) current else current + appends
}

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

/**
 * Imeta size and duration let the player distinguish CDN-hostile camera media
 * from already-compressed feed video without hard-coding a relay or host.
 */
internal fun estimatedVideoBitrateBps(sizeBytes: Long?, durationSeconds: Double?): Long? {
    if (sizeBytes == null || sizeBytes <= 0L || durationSeconds == null || durationSeconds <= 0.0) {
        return null
    }
    return ((sizeBytes.toDouble() * 8.0) / durationSeconds)
        .takeIf { it.isFinite() && it <= Long.MAX_VALUE.toDouble() }
        ?.toLong()
}

/**
 * Hold proven high-bitrate media until half of a short clip (at most 1.5
 * seconds) is buffered. Ordinary feed uploads retain the fast 500 ms path.
 */
internal fun resilientStartupBufferMs(video: VideoRenderModel): Long {
    val bitrate = estimatedVideoBitrateBps(video.sizeBytes, video.durationSeconds)
    if (bitrate == null || bitrate < HIGH_BITRATE_VIDEO_BPS) return 0L
    val halfDurationMs = (video.durationSeconds!! * 500.0).toLong()
    return halfDurationMs.coerceIn(
        MIN_RESILIENT_STARTUP_BUFFER_MS,
        MAX_RESILIENT_STARTUP_BUFFER_MS,
    )
}

internal fun shouldDeferImmersivePreload(video: VideoRenderModel): Boolean =
    resilientStartupBufferMs(video) > 0L

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
