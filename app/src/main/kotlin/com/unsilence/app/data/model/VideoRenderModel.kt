package com.unsilence.app.data.model

import com.unsilence.app.data.memory.FeedRow
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
@androidx.compose.runtime.Immutable
data class VideoRenderModel(
    val videoUrl: String,
    val aspectRatio: Float,       // width / height, e.g. 1.778 for 16:9
    val posterUrl: String?,       // imeta thumb/image URL
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

private const val DEFAULT_ASPECT_RATIO = 16f / 9f

/**
 * Build [VideoRenderModel]s for a single feed row by combining imeta tags
 * with regex-detected video URLs from content. Mirrors the extraction logic
 * that previously lived inside NoteCard's remember {} block.
 */
/**
 * Overload for insert-time population of the MES sidecar cache.
 * Avoids FeedRow construction and JSON round-trip of tags.
 */
fun buildVideoRenderModels(
    kind: Int,
    content: String,
    tags: List<List<String>>,
): List<VideoRenderModel> {
    val (effectiveContent, imetaMedia) = if (kind == 6 && content.isNotBlank()) {
        runCatching {
            val inner = NostrJson.parseToJsonElement(content).jsonObject
            val innerContent = inner["content"]?.jsonPrimitive?.content ?: content
            val innerTags = inner["tags"]?.toString()?.let { ImetaParser.parse(it) } ?: emptyList()
            innerContent to innerTags
        }.getOrElse { content to ImetaParser.parseFromList(tags) }
    } else {
        content to ImetaParser.parseFromList(tags)
    }

    val youtubeStripped = YOUTUBE_REGEX.replace(effectiveContent, "")
    val regexVideoUrls = VIDEO_EXT_REGEX.findAll(youtubeStripped).map { it.value }.toList()
    val imetaVideoUrls = imetaMedia
        .filter { it.mimeType?.startsWith("video/") == true && isDirectVideoUrl(it.url) }
        .map { it.url }
    val allVideoUrls = (regexVideoUrls + imetaVideoUrls).distinct().filter(::isDirectVideoUrl)
    if (allVideoUrls.isEmpty()) return emptyList()
    return allVideoUrls.map { url -> buildModelForUrl(url, imetaMedia) }
}

fun buildVideoRenderModels(row: FeedRow): List<VideoRenderModel> {
    // For kind-6 reposts, extract effective content AND tags from the embedded
    // inner event JSON.  The outer wrapper's tags have no imeta; using them
    // would produce zero video metadata (wrong aspect ratio, no poster URL).
    val (effectiveContent, imetaMedia) = if (row.kind == 6 && row.content.isNotBlank()) {
        runCatching {
            val inner = NostrJson.parseToJsonElement(row.content).jsonObject
            val content = inner["content"]?.jsonPrimitive?.content ?: row.content
            val tags = inner["tags"]?.toString()?.let { ImetaParser.parse(it) } ?: emptyList()
            content to tags
        }.getOrElse { row.content to ImetaParser.parse(row.tags) }
    } else {
        row.content to ImetaParser.parse(row.tags)
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
    val meta = imetaMedia.firstOrNull { it.url == url && it.width != null && it.height != null && it.height != 0 }
    val aspect = meta?.let { it.width!!.toFloat() / it.height!! } ?: DEFAULT_ASPECT_RATIO
    val imetaForUrl = imetaMedia.firstOrNull { it.url == url }
    val poster = imetaForUrl?.thumb ?: imetaForUrl?.image

    return VideoRenderModel(
        videoUrl = url,
        aspectRatio = aspect,
        posterUrl = poster,
        widthPx = meta?.width,
        heightPx = meta?.height,
    )
}
