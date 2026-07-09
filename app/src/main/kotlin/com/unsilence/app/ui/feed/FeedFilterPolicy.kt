package com.unsilence.app.ui.feed

import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.Segment
import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.domain.model.ShowType

internal enum class KindOneMediaType {
    TEXT,
    IMAGE,
    VIDEO,
}

internal fun classifyKindOneMedia(
    content: String,
    model: EventModel?,
): KindOneMediaType {
    if (model != null) {
        if (model.segments.any { it is Segment.Video || it is Segment.YouTube }) {
            return KindOneMediaType.VIDEO
        }
        if (model.segments.any { it is Segment.Image }) {
            return KindOneMediaType.IMAGE
        }
    }
    return when {
        VIDEO_URL_REGEX.containsMatchIn(content) -> KindOneMediaType.VIDEO
        IMAGE_URL_REGEX.containsMatchIn(content) -> KindOneMediaType.IMAGE
        else -> KindOneMediaType.TEXT
    }
}

internal fun matchesShowTypes(
    kind: Int,
    content: String,
    model: EventModel?,
    filter: FeedFilter,
): Boolean {
    if (kind !in filter.enabledKinds) return false
    if (ShowType.ALL in filter.showTypes) return true

    return when (kind) {
        1 -> when (classifyKindOneMedia(content, model)) {
            KindOneMediaType.TEXT -> ShowType.TEXT in filter.showTypes
            KindOneMediaType.IMAGE -> ShowType.IMAGES in filter.showTypes
            KindOneMediaType.VIDEO -> ShowType.VIDEO in filter.showTypes
        }
        20 -> ShowType.IMAGES in filter.showTypes
        21 -> ShowType.VIDEO in filter.showTypes
        30023 -> ShowType.ARTICLES in filter.showTypes
        else -> false
    }
}

internal fun FeedFilter.hasActivityThresholds(): Boolean =
    minReplies > 0 || minReposts > 0 || minReactions > 0 || minZapSats > 0
