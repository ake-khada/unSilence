package com.unsilence.app.domain.model

enum class GlobalFeedLens {
    TRUSTED,
    RAW,
}

fun parseGlobalFeedLens(value: String?): GlobalFeedLens =
    GlobalFeedLens.entries.firstOrNull { it.name == value } ?: GlobalFeedLens.TRUSTED
