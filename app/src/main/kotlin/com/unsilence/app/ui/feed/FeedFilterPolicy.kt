package com.unsilence.app.ui.feed

import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.memory.EventStats
import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.domain.model.ShowType

internal enum class KindOneMediaType {
    TEXT,
    IMAGE,
    VIDEO,
}

internal data class ResolvedRepostTarget(
    val content: String,
    val model: EventModel,
)

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
    resolvedRepostTarget: ResolvedRepostTarget? = null,
): Boolean {
    if (kind !in filter.enabledKinds) return false
    if (ShowType.ALL in filter.showTypes) return true

    if (kind == 6 || kind == 16) {
        val repost = model?.repost ?: return false
        val effective = if (repost.embeddedJson != null && repost.resolvedFromInner) {
            // ContentParser already unwrapped the embedded event into this model.
            ResolvedRepostTarget(content = "", model = model)
        } else {
            // A k/e/a tag alone only describes a reference. Until its target is
            // hydrated, do not guess its content class in a specific view.
            resolvedRepostTarget ?: return false
        }
        return matchesEffectiveShowType(
            kind = effective.model.effectiveKind,
            content = effective.content,
            model = effective.model,
            filter = filter,
        )
    }

    return matchesEffectiveShowType(kind, content, model, filter)
}

private fun matchesEffectiveShowType(
    kind: Int,
    content: String,
    model: EventModel?,
    filter: FeedFilter,
): Boolean = when (kind) {
        1 -> when (classifyKindOneMedia(content, model)) {
            KindOneMediaType.TEXT -> ShowType.TEXT in filter.showTypes
            KindOneMediaType.IMAGE -> ShowType.IMAGES in filter.showTypes
            KindOneMediaType.VIDEO -> ShowType.VIDEO in filter.showTypes
        }
        20 -> ShowType.IMAGES in filter.showTypes
        21, 22, 34235, 34236 -> ShowType.VIDEO in filter.showTypes
        1068 -> ShowType.TEXT in filter.showTypes
        30023 -> ShowType.ARTICLES in filter.showTypes
        else -> false
    }

internal fun FeedFilter.hasActivityThresholds(): Boolean =
    minReplies > 0 || minReposts > 0 || minReactions > 0 || minZapSats > 0

internal fun activityStatsTargetId(kind: Int, id: String, rootId: String?): String? =
    if (kind == 6 || kind == 16) rootId else id

internal fun activityThresholdsPass(filter: FeedFilter, stats: EventStats): Boolean =
    stats.replyCount >= filter.minReplies &&
        stats.repostCount >= filter.minReposts &&
        stats.reactionCount >= filter.minReactions &&
        stats.zapTotalSats >= filter.minZapSats
