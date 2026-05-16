package com.unsilence.app.ui.feed

/**
 * Single source of truth for feed image container aspect ratios.
 * Mirrors [feedVideoAspectRatio] so images and videos size identically.
 *
 * Portrait cap: freakishly tall images are capped at 9:16 so they don't
 * produce five-screen-tall cards; ContentScale.Fit letterboxes within the
 * capped container.
 */
internal fun feedImageAspectRatio(
    rawAspectRatio: Float?,
    forceSquare: Boolean = false,
): Float {
    if (forceSquare) return 1f
    val raw = rawAspectRatio?.takeIf { it > 0f } ?: return (4f / 3f)
    return if (raw >= 1f) raw else maxOf(raw, 9f / 16f)
}
