package com.unsilence.app.data.relay

import com.unsilence.app.data.model.EventModel
import com.unsilence.app.domain.model.QuotedMediaPolicy
import com.unsilence.app.domain.model.ShowType

internal data class ImageCandidate(
    val url: String,
    val aspectRatio: Float,
    val dimensionsKnown: Boolean,
    val preview: Boolean = false,
    val showType: ShowType? = null,
)

internal fun imageCandidates(model: EventModel, policy: QuotedMediaPolicy = QuotedMediaPolicy()): List<ImageCandidate> = buildList {
    val articleImage = model.article?.image
    if (!articleImage.isNullOrBlank()) add(ImageCandidate(articleImage, 16f / 9f, dimensionsKnown = false))

    if (policy.images) for (image in model.media.images.take(MAX_MEDIA_PER_CARD)) {
        val imetaKnown = hasUsableAspectMetadata(image.imetaAspect)
        add(ImageCandidate(image.url, feedSafeAspect(image.imetaAspect), imetaKnown, showType = ShowType.IMAGES))
    }

    if (policy.videos) for (video in model.media.videos.take(MAX_MEDIA_PER_CARD).map { it.model }) {
        val poster = video.posterUrl
        if (!poster.isNullOrBlank()) {
            // VideoRenderModel falls back to 16:9 when imeta dimensions are
            // absent; only raw width/height make this authoritative enough
            // to suppress the poster probe.
            val aspectKnown = video.widthPx != null &&
                video.heightPx != null && video.heightPx > 0
            add(ImageCandidate(poster, feedSafeAspect(video.aspectRatio), aspectKnown, showType = ShowType.VIDEO))
        }
    }

    if (policy.videos) for (youtube in model.media.youtubes.take(MAX_MEDIA_PER_CARD)) {
        add(ImageCandidate(
            "https://img.youtube.com/vi/${youtube.videoId}/hqdefault.jpg",
            16f / 9f,
            dimensionsKnown = true,
            preview = true,
            showType = ShowType.VIDEO,
        ))
    }
}

internal fun feedSafeAspect(aspectRatio: Float?): Float {
    val ratio = aspectRatio?.takeIf { it > 0f } ?: (16f / 9f)
    return ratio.coerceIn(0.2f, 5f)
}

/** Keep the provider live until a missing reference arrives; repost/parent bodies stay unrestricted. */
internal fun referenceAssetPolicy(
    isQuote: Boolean,
    quotedPolicy: () -> QuotedMediaPolicy,
): () -> QuotedMediaPolicy = if (isQuote) quotedPolicy else { { QuotedMediaPolicy() } }
