package com.unsilence.app.data.model

import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.relay.ImetaMedia
import com.unsilence.app.data.relay.ImetaParser
import com.unsilence.app.data.relay.NostrJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pre-computed video rendering metadata for a single video URL in a feed item.
 * Derived from imeta tags and content URL detection at hydration time,
 * so composables never need to parse tags or compute aspect ratios.
 */
data class VideoRenderModel(
    val videoUrl: String,
    val aspectRatio: Float,       // width / height, e.g. 1.778 for 16:9
    val posterUrl: String?,       // imeta thumb/image URL
    val contentType: String?,     // "video/mp4", "application/x-mpegURL" etc.
    val isHls: Boolean,           // derived from contentType or .m3u8 extension
    val widthPx: Int?,            // raw pixel width from imeta
    val heightPx: Int?,           // raw pixel height from imeta
)

private val YOUTUBE_REGEX = Regex(
    """https?://(?:www\.)?(?:youtube\.com/(?:watch\?v=|shorts/)|youtu\.be/)[A-Za-z0-9_-]{11}\S*""",
    RegexOption.IGNORE_CASE,
)

private val VIDEO_EXT_REGEX = Regex(
    """https?://\S+\.(?:mp4|mov|webm|m3u8|m4v|avi)(?:\?\S*)?""",
    RegexOption.IGNORE_CASE,
)

private fun isDirectVideoUrl(url: String): Boolean =
    url.contains(".mp4", ignoreCase = true) ||
        url.contains(".mov", ignoreCase = true) ||
        url.contains(".webm", ignoreCase = true) ||
        url.contains(".m3u8", ignoreCase = true) ||
        url.contains(".m4v", ignoreCase = true) ||
        url.contains(".avi", ignoreCase = true)

private fun isHlsUrl(url: String, mimeType: String?): Boolean =
    url.contains(".m3u8", ignoreCase = true) ||
        mimeType?.equals("application/x-mpegURL", ignoreCase = true) == true

private const val DEFAULT_ASPECT_RATIO = 16f / 9f

/**
 * Build [VideoRenderModel]s for a single feed row by combining imeta tags
 * with regex-detected video URLs from content. Mirrors the extraction logic
 * that previously lived inside NoteCard's remember {} block.
 */
fun buildVideoRenderModels(row: FeedRow): List<VideoRenderModel> {
    val imetaMedia = ImetaParser.parse(row.tags)

    // For kind-6 reposts, extract effective content from embedded JSON
    // (mirrors NoteCard's boostedJson → effectiveContent logic).
    val effectiveContent = if (row.kind == 6 && row.content.isNotBlank()) {
        runCatching {
            NostrJson.parseToJsonElement(row.content).jsonObject["content"]
                ?.jsonPrimitive?.content
        }.getOrNull() ?: row.content
    } else {
        row.content
    }

    // Strip YouTube URLs first (they're web pages, not playable files)
    val youtubeStripped = YOUTUBE_REGEX.replace(effectiveContent, "")

    // Collect video URLs from regex
    val regexVideoUrls = VIDEO_EXT_REGEX.findAll(youtubeStripped)
        .map { it.value }
        .toList()

    // Collect video URLs from imeta (MIME-based)
    val imetaVideoUrls = imetaMedia
        .filter { it.mimeType?.startsWith("video/") == true && isDirectVideoUrl(it.url) }
        .map { it.url }

    val allVideoUrls = (regexVideoUrls + imetaVideoUrls)
        .distinct()
        .filter(::isDirectVideoUrl)

    if (allVideoUrls.isEmpty()) return emptyList()

    return allVideoUrls.map { url ->
        buildModelForUrl(url, imetaMedia)
    }
}

private fun buildModelForUrl(url: String, imetaMedia: List<ImetaMedia>): VideoRenderModel {
    val meta = imetaMedia.firstOrNull { it.url == url && it.width != null && it.height != null }
    val aspect = meta?.let { it.width!!.toFloat() / it.height!! } ?: DEFAULT_ASPECT_RATIO
    val poster = imetaMedia.firstOrNull { it.url == url }?.thumb
    val mimeType = imetaMedia.firstOrNull { it.url == url }?.mimeType

    return VideoRenderModel(
        videoUrl = url,
        aspectRatio = aspect,
        posterUrl = poster,
        contentType = mimeType,
        isHls = isHlsUrl(url, mimeType),
        widthPx = meta?.width,
        heightPx = meta?.height,
    )
}
