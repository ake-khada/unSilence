package com.unsilence.app.ui.shared

import com.unsilence.app.data.model.VideoRenderModel

/** A live ContentFlow grants playback only for its own media, under its lazy-row owner. */
internal data class VideoPlaybackRegistration(
    val noteId: String,
    val models: List<VideoRenderModel>,
)

/**
 * Cache discovery is not consent. A gated quote may share a lazy row with an
 * ordinary post, so both the row AND the exact URL must have a live registration.
 */
internal fun permittedVideoModels(
    cachedModels: Map<String, List<VideoRenderModel>>,
    registrations: Collection<VideoPlaybackRegistration>,
): Map<String, List<VideoRenderModel>> {
    if (registrations.isEmpty()) return emptyMap()
    val allowedByRow = LinkedHashMap<String, MutableList<VideoRenderModel>>()
    registrations.forEach { registration ->
        allowedByRow.getOrPut(registration.noteId) { ArrayList() }.addAll(registration.models)
    }
    return buildMap {
        // Visit only live registrations, not every cached row on each card mount.
        allowedByRow.forEach { (noteId, models) ->
            val allowedUrls = models.mapTo(HashSet()) { it.videoUrl }
            // Keep cache ordering/own-video priority when available. Late-resolved
            // quotes can play immediately after reveal without a feed-list update.
            val playable = cachedModels[noteId].orEmpty().filter { it.videoUrl in allowedUrls }
                .ifEmpty { models.distinctBy { it.videoUrl } }
            if (playable.isNotEmpty()) put(noteId, playable)
        }
    }
}
