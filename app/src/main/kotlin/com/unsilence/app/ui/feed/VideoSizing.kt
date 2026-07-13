package com.unsilence.app.ui.feed

import kotlin.math.abs

private const val DEFAULT_VIDEO_ASPECT_RATIO = 16f / 9f
internal const val VIDEO_ASPECT_CORRECTION_THRESHOLD = 0.02f

/**
 * Single source of truth for feed video container aspect ratios.
 * Used by both thumbnail and active player paths so the box never changes size.
 */
internal fun feedVideoAspectRatio(
    rawAspectRatio: Float?,
    forceSquare: Boolean = false,
): Float {
    if (forceSquare) return 1f
    return rawAspectRatio?.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_VIDEO_ASPECT_RATIO
}

/** Prevents tiny decoder/imeta differences from resizing a card while allowing one real correction. */
internal fun shouldCorrectVideoAspectRatio(assumed: Float, resolved: Float): Boolean {
    if (!assumed.isFinite() || assumed <= 0f || !resolved.isFinite() || resolved <= 0f) return false
    return abs(resolved - assumed) / assumed > VIDEO_ASPECT_CORRECTION_THRESHOLD
}
