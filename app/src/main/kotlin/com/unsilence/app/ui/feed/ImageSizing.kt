package com.unsilence.app.ui.feed

/**
 * Single source of truth for feed image container aspect ratios.
 * Mirrors [feedVideoAspectRatio] so images and videos size identically.
 */
internal fun feedImageAspectRatio(
    rawAspectRatio: Float?,
    forceSquare: Boolean = false,
): Float {
    if (forceSquare) return 1f
    return rawAspectRatio?.takeIf { it > 0f } ?: (4f / 3f) // default: 4:3 (most common photo)
}
