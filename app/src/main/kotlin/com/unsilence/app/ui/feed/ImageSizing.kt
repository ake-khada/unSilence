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
    val raw = rawAspectRatio?.takeIf { it > 0f } ?: (4f / 3f) // default: 4:3 (most common photo)
    return when {
        raw >= 1f -> raw              // landscape: use actual
        else -> maxOf(raw, 9f / 16f)  // portrait: cap at 9:16
    }
}
