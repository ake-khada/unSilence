package com.unsilence.app.ui.feed

/**
 * Single source of truth for feed video container aspect ratios.
 * Used by both thumbnail and active player paths so the box never changes size.
 */
internal fun feedVideoAspectRatio(
    rawAspectRatio: Float?,
    forceSquare: Boolean = false,
): Float {
    if (forceSquare) return 1f
    val raw = rawAspectRatio?.takeIf { it > 0f } ?: (16f / 9f)
    return when {
        raw >= 1f -> raw              // landscape: use actual
        else -> maxOf(raw, 9f / 16f)  // portrait: cap at 9:16
    }
}
