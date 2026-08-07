package com.unsilence.app.data.model

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.relay.ImetaMedia
import com.unsilence.app.data.relay.ImetaParser
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.parseRepostInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
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
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
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

// Render-model DoS bounds (untrusted relay content up to 512KB reaches both the
// insert-time sidecar path and the on-composition sidecar-miss fallback):
// scan cap mirrors ContentParser's MAX_PARSE_CHARS (private there) — VIDEO_EXT_REGEX
// is O(content); URL cap bounds the per-event sidecar models, downstream only ever
// consumes the first few (autoplay firstOrNull).
private const val MAX_VIDEO_SCAN_CHARS = 20_000
private const val MAX_VIDEO_URLS = 8

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

/** Dedup key — the exact comparison [mediaUrlMatches] makes: cleaned URL, hash
 *  stripped, and query stripped when the path ends in a filename-like segment
 *  (its last-'.'-segment check). Lets dedup run O(n) instead of pairwise O(n²). */
private fun mediaUrlDedupKey(url: String): String {
    val cleaned = cleanMediaUrl(url).substringBefore('#')
    val withoutQuery = cleaned.substringBefore('?')
    return if (withoutQuery.substringAfterLast('/').contains('.')) withoutQuery else cleaned
}

private fun Sequence<String>.distinctMediaUrls(limit: Int): List<String> {
    val seen = LinkedHashSet<String>()
    val result = mutableListOf<String>()
    for (url in this) {
        if (seen.add(mediaUrlDedupKey(url))) {
            result.add(url)
            if (result.size == limit) break
        }
    }
    return result
}

private fun buildBoundedVideoRenderModels(
    effectiveContent: String,
    imetaMedia: List<ImetaMedia>,
    effectiveKind: Int,
): List<VideoRenderModel> {
    val cappedContent = effectiveContent.take(MAX_VIDEO_SCAN_CHARS)
    val regexVideoUrls = VIDEO_EXT_REGEX
        .findAll(YOUTUBE_REGEX.replace(cappedContent, ""))
        .map { cleanMediaUrl(it.value) }
    val imetaVideoUrls = imetaMedia.asSequence()
        .filter(::isVideoImeta)
        .map { cleanMediaUrl(it.url) }
        .filter { it.isNotBlank() }
    val allVideoUrls = (regexVideoUrls + imetaVideoUrls)
        .distinctMediaUrls(MAX_VIDEO_URLS)
    return allVideoUrls.map { url ->
        buildModelForUrl(url, imetaMedia, isShortFormVideoKind(effectiveKind))
    }
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
    preparsedRepost: RepostInfo? = null,
): List<VideoRenderModel> {
    val repost = if (kind == 6 || kind == 16) {
        preparsedRepost ?: parseRepostInfo(kind, content, tags)
    } else null
    val verified = (repost?.payload as? RepostPayload.VerifiedEmbedded)?.event
    val effectiveContent = when {
        verified != null -> verified.content
        repost != null -> ""
        else -> content
    }
    val effectiveTags = when {
        verified != null -> verified.tags
        repost != null -> emptyList()
        else -> tags
    }
    val effectiveKind = verified?.kind ?: kind
    val imetaMedia = ImetaParser.parseFromList(effectiveTags)

    return buildBoundedVideoRenderModels(effectiveContent, imetaMedia, effectiveKind)
}

fun buildVideoRenderModels(row: FeedRow): List<VideoRenderModel> {
    val tags = parseTagLists(row.tags)
    // FeedRow is a flattened UI projection and cannot prove that embedded
    // fields passed verified ingest. Never run crypto on the composition path;
    // reference-only video is adopted from the independently verified target.
    val safeRepost = if (row.kind == 6 || row.kind == 16) {
        parseRepostInfo(row.kind, row.content, tags, verifyEmbedded = { false })
    } else {
        null
    }
    return buildVideoRenderModels(row.kind, row.content, tags, safeRepost)
}

private fun parseTagLists(tagsJson: String): List<List<String>> = runCatching {
    NostrJson.parseToJsonElement(tagsJson).jsonArray.mapNotNull { element ->
        val row = element as? JsonArray ?: return@mapNotNull null
        row.mapNotNull { part -> runCatching { part.jsonPrimitive.content }.getOrNull() }
    }
}.getOrDefault(emptyList())

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
        mimeType = imetaForUrl?.mimeType,
        sizeBytes = imetaForUrl?.sizeBytes,
    )
}
