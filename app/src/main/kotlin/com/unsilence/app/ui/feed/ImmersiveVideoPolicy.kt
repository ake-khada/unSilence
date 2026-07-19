package com.unsilence.app.ui.feed

import androidx.media3.common.Player
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.domain.model.ShowType

private val IMMERSIVE_VIDEO_KINDS = setOf(1, 21, 22, 34235, 34236)
private val REPOST_KINDS = setOf(6, 16)

internal const val HIGH_BITRATE_VIDEO_BPS = 4_000_000L
internal const val MAX_RESILIENT_STARTUP_BUFFER_MS = 1_500L
private const val MIN_RESILIENT_STARTUP_BUFFER_MS = 500L

internal data class ImmersiveVideoItem(
    val row: FeedRow,
    val video: VideoRenderModel,
    /** Stable content identity; repost wrappers point at their target event. */
    val contentId: String = row.id,
    /** Immersive is a content surface, so this is always the original author. */
    val authorPubkey: String = row.pubkey,
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
    if (current.isEmpty()) return incoming.distinctBy { it.contentId }
    if (incoming.isEmpty()) return current

    val currentIds = current.asSequence().map { it.contentId }.toHashSet()
    val oldestSharedIndex = incoming.indexOfLast { it.contentId in currentIds }
    if (oldestSharedIndex < 0) return current

    val appends = incoming.asSequence()
        .drop(oldestSharedIndex + 1)
        .filter { it.contentId !in currentIds }
        .distinctBy { it.contentId }
        .toList()
    return if (appends.isEmpty()) current else current + appends
}

/**
 * Select directly playable content, treating repost rows as aliases of their
 * target ID. VideoRenderModel deliberately excludes YouTube page URLs, so a
 * YouTube-only kind-1 post never enters the pager.
 */
internal fun selectImmersiveVideoItems(
    rows: List<FeedRow>,
    videoModelsFor: (String) -> List<VideoRenderModel>,
    authorPubkeyFor: (String) -> String? = { null },
): List<ImmersiveVideoItem> {
    val seenContentIds = HashSet<String>()
    return rows.mapNotNull { row ->
        val contentId = when (row.kind) {
            in IMMERSIVE_VIDEO_KINDS -> row.id
            in REPOST_KINDS -> row.rootId?.takeIf { it.isNotBlank() }
            else -> null
        } ?: return@mapNotNull null
        // Feed order is newest-first; the first playable occurrence becomes the
        // canonical page and later wrappers of the same target do no extra work.
        if (contentId in seenContentIds) return@mapNotNull null
        val authorPubkey = if (row.kind in REPOST_KINDS) {
            authorPubkeyFor(contentId) ?: return@mapNotNull null
        } else {
            row.pubkey
        }
        videoModelsFor(contentId).firstOrNull()?.let {
            seenContentIds.add(contentId)
            ImmersiveVideoItem(
                row = row,
                video = it,
                contentId = contentId,
                authorPubkey = authorPubkey,
            )
        }
    }
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
