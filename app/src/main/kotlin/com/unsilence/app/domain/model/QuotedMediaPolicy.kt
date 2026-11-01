package com.unsilence.app.domain.model

/** Feed Show types constrain quoted media, independently of automatic loading preferences. */
data class QuotedMediaPolicy(val images: Boolean = true, val videos: Boolean = true) {
    fun allows(type: ShowType?): Boolean = when (type) {
        ShowType.IMAGES -> images
        ShowType.VIDEO -> videos
        else -> true // Article artwork and OG previews retain their existing behavior.
    }
}

fun FeedFilter.quotedMediaPolicy(): QuotedMediaPolicy = QuotedMediaPolicy(
    images = ShowType.ALL in showTypes || ShowType.IMAGES in showTypes,
    videos = ShowType.ALL in showTypes || ShowType.VIDEO in showTypes,
)
