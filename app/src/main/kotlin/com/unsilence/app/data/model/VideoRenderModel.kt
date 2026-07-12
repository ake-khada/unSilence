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
    val shortForm: Boolean = false,
    val fallbackUrls: List<String> = emptyList(),
    val durationSeconds: Double? = null,
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
    cleanMediaUrl(url).let { clean ->
        clean.contains(".mp4", ignoreCase = true) ||
            clean.contains(".mov", ignoreCase = true) ||
            clean.contains(".webm", ignoreCase = true) ||
            clean.contains(".m3u8", ignoreCase = true) ||
            clean.contains(".m4v", ignoreCase = true) ||
            clean.contains(".avi", ignoreCase = true)
    }

private fun isYouTubeUrl(url: String): Boolean = YOUTUBE_REGEX.matches(cleanMediaUrl(url))

private fun isVideoImeta(media: ImetaMedia): Boolean =
    !isYouTubeUrl(media.url) &&
        (media.mimeType?.startsWith("video/") == true || isDirectVideoUrl(media.url))

private const val DEFAULT_ASPECT_RATIO = 16f / 9f

private val MEDIA_TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', ')', ']', '}', '"', '\'')

internal fun cleanMediaUrl(url: String): String =
    url.trim().trimEnd(*MEDIA_TRAILING_PUNCTUATION)

internal fun mediaUrlMatches(left: String, right: String): Boolean {
    val cleanLeft = cleanMediaUrl(left).substringBefore('#')
    val cleanRight = cleanMediaUrl(right).substringBefore('#')
    if (cleanLeft == cleanRight) return true

    val leftWithoutQuery = cleanLeft.substringBefore('?')
    val rightWithoutQuery = cleanRight.substringBefore('?')
    return leftWithoutQuery == rightWithoutQuery &&
        leftWithoutQuery.substringAfterLast('/').contains('.')
}

private fun List<ImetaMedia>.firstMatchingMedia(url: String): ImetaMedia? =
    firstOrNull { mediaUrlMatches(it.url, url) }

private fun Iterable<String>.distinctMediaUrls(): List<String> {
    val result = mutableListOf<String>()
    for (url in this) {
        if (result.none { existing -> mediaUrlMatches(existing, url) }) {
            result.add(url)
        }
    }
    return result
}

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
    val (effectiveContent, imetaMedia, effectiveKind) = if ((kind == 6 || kind == 16) && content.isNotBlank()) {
        runCatching {
            val inner = NostrJson.parseToJsonElement(content).jsonObject
            val innerContent = inner["content"]?.jsonPrimitive?.content ?: content
            val innerTags = inner["tags"]?.toString()?.let { ImetaParser.parse(it) } ?: emptyList()
            Triple(
                innerContent,
                innerTags,
                inner["kind"]?.jsonPrimitive?.content?.toIntOrNull() ?: kind,
            )
        }.getOrElse { Triple(content, ImetaParser.parseFromList(tags), kind) }
    } else {
        Triple(content, ImetaParser.parseFromList(tags), kind)
    }

    val youtubeStripped = YOUTUBE_REGEX.replace(effectiveContent, "")
    val regexVideoUrls = VIDEO_EXT_REGEX.findAll(youtubeStripped).map { cleanMediaUrl(it.value) }.toList()
    val imetaVideoUrls = imetaMedia
        .filter(::isVideoImeta)
        .map { cleanMediaUrl(it.url) }
        .filter { it.isNotBlank() }
    val allVideoUrls = (regexVideoUrls + imetaVideoUrls).distinctMediaUrls()
    if (allVideoUrls.isEmpty()) return emptyList()
    return allVideoUrls.map { url -> buildModelForUrl(url, imetaMedia, isShortFormVideoKind(effectiveKind)) }
}

fun buildVideoRenderModels(row: FeedRow): List<VideoRenderModel> {
    // For kind-6 / kind-16 reposts, extract effective content AND tags from the
    // embedded inner event JSON.  The outer wrapper's tags have no imeta; using
    // them would produce zero video metadata (wrong aspect ratio, no poster URL).
    val (effectiveContent, imetaMedia, effectiveKind) = if ((row.kind == 6 || row.kind == 16) && row.content.isNotBlank()) {
        runCatching {
            val inner = NostrJson.parseToJsonElement(row.content).jsonObject
            val content = inner["content"]?.jsonPrimitive?.content ?: row.content
            val tags = inner["tags"]?.toString()?.let { ImetaParser.parse(it) } ?: emptyList()
            Triple(
                content,
                tags,
                inner["kind"]?.jsonPrimitive?.content?.toIntOrNull() ?: row.kind,
            )
        }.getOrElse { Triple(row.content, ImetaParser.parse(row.tags), row.kind) }
    } else {
        Triple(row.content, ImetaParser.parse(row.tags), row.kind)
    }

    // Strip YouTube URLs first (they're web pages, not playable files)
    val youtubeStripped = YOUTUBE_REGEX.replace(effectiveContent, "")

    // Collect video URLs from regex
    val regexVideoUrls = VIDEO_EXT_REGEX.findAll(youtubeStripped)
        .map { cleanMediaUrl(it.value) }
        .toList()

    // Collect video URLs from imeta (MIME-based)
    val imetaVideoUrls = imetaMedia
        .filter(::isVideoImeta)
        .map { cleanMediaUrl(it.url) }
        .filter { it.isNotBlank() }

    val allVideoUrls = (regexVideoUrls + imetaVideoUrls)
        .distinctMediaUrls()

    if (allVideoUrls.isEmpty()) return emptyList()

    return allVideoUrls.map { url ->
        buildModelForUrl(url, imetaMedia, isShortFormVideoKind(effectiveKind))
    }
}

private fun buildModelForUrl(
    url: String,
    imetaMedia: List<ImetaMedia>,
    shortForm: Boolean,
): VideoRenderModel {
    val cleanUrl = cleanMediaUrl(url)
    val meta = imetaMedia.firstOrNull {
        mediaUrlMatches(it.url, cleanUrl) && it.width != null && it.height != null && it.height != 0
    }
    val aspect = meta?.let { it.width!!.toFloat() / it.height!! } ?: DEFAULT_ASPECT_RATIO
    val imetaForUrl = imetaMedia.firstMatchingMedia(cleanUrl)
    val poster = imetaForUrl?.thumb ?: imetaForUrl?.image

    return VideoRenderModel(
        videoUrl = cleanUrl,
        aspectRatio = aspect,
        posterUrl = poster,
        widthPx = meta?.width,
        heightPx = meta?.height,
        shortForm = shortForm,
        fallbackUrls = imetaForUrl?.fallbacks.orEmpty(),
        durationSeconds = imetaForUrl?.durationSeconds,
    )
}
