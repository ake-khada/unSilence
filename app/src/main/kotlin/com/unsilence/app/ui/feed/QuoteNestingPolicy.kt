package com.unsilence.app.ui.feed

internal enum class QuoteRenderMode {
    FULL,
    COMPACT,
    CONTINUATION,
}

/**
 * [nestDepth] is zero-based: the first embedded quote is depth 0. Two quote
 * levels retain full content, the third is compact, and deeper content stops.
 */
internal fun quoteRenderMode(nestDepth: Int): QuoteRenderMode = when {
    nestDepth < 2 -> QuoteRenderMode.FULL
    nestDepth == 2 -> QuoteRenderMode.COMPACT
    else -> QuoteRenderMode.CONTINUATION
}
