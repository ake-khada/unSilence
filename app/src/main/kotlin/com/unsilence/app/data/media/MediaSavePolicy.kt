package com.unsilence.app.data.media

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

enum class SaveMediaKind {
    IMAGE,
    VIDEO,
}

private val FILENAME_UNSAFE = Regex("[^A-Za-z0-9._-]+")
private val LEADING_TRAILING_DOTS = Regex("^\\.+|\\.+$")

private val IMAGE_MIME_BY_EXTENSION = mapOf(
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "heic" to "image/heic",
    "heif" to "image/heif",
    "avif" to "image/avif",
)

private val VIDEO_MIME_BY_EXTENSION = mapOf(
    "mp4" to "video/mp4",
    "m4v" to "video/mp4",
    "mov" to "video/quicktime",
    "webm" to "video/webm",
    "avi" to "video/x-msvideo",
    "m3u8" to "application/vnd.apple.mpegurl",
)

private val EXTENSION_BY_MIME = mapOf(
    "image/jpeg" to "jpg",
    "image/png" to "png",
    "image/gif" to "gif",
    "image/webp" to "webp",
    "image/heic" to "heic",
    "image/heif" to "heif",
    "image/avif" to "avif",
    "video/mp4" to "mp4",
    "video/quicktime" to "mov",
    "video/webm" to "webm",
    "video/x-msvideo" to "avi",
)

private val HLS_MIME_TYPES = setOf(
    "application/vnd.apple.mpegurl",
    "application/x-mpegurl",
    "application/mpegurl",
    "audio/mpegurl",
    "audio/x-mpegurl",
)

fun deriveMediaFilename(
    url: String,
    mimeType: String?,
    kind: SaveMediaKind,
): String {
    val resolvedMime = resolveMediaMimeType(mimeType, url, kind)
    val extension = extensionForMime(resolvedMime, kind)
    val sanitizedBase = sanitizeFilename(urlBasename(url))
    val base = sanitizedBase.ifBlank {
        "${kind.name.lowercase(Locale.US)}-${shortHash(url)}"
    }
    val truncated = base.take(96).trim('.', '_', '-').ifBlank {
        "${kind.name.lowercase(Locale.US)}-${shortHash(url)}"
    }
    return if (truncated.hasRecognizedExtension()) {
        truncated
    } else {
        "$truncated.$extension"
    }
}

fun resolveMediaMimeType(
    contentTypeHeader: String?,
    url: String,
    kind: SaveMediaKind,
): String {
    normalizeContentType(contentTypeHeader)?.takeIf { it.isMediaMimeType() || it in HLS_MIME_TYPES }?.let {
        return it
    }
    extensionFromUrl(url)?.let { extension ->
        IMAGE_MIME_BY_EXTENSION[extension]?.let { return it }
        VIDEO_MIME_BY_EXTENSION[extension]?.let { return it }
    }
    return when (kind) {
        SaveMediaKind.IMAGE -> "image/jpeg"
        SaveMediaKind.VIDEO -> "video/mp4"
    }
}

fun isSavableVideoSource(
    url: String,
    contentTypeHeader: String? = null,
): Boolean {
    val mimeType = normalizeContentType(contentTypeHeader)
    if (mimeType in HLS_MIME_TYPES) return false
    return extensionFromUrl(url) != "m3u8"
}

internal fun extensionForMime(mimeType: String?, kind: SaveMediaKind): String {
    val normalized = normalizeContentType(mimeType)
    return EXTENSION_BY_MIME[normalized] ?: when (kind) {
        SaveMediaKind.IMAGE -> "jpg"
        SaveMediaKind.VIDEO -> "mp4"
    }
}

private fun String.hasRecognizedExtension(): Boolean {
    val extension = substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.US)
    return extension in IMAGE_MIME_BY_EXTENSION || extension in VIDEO_MIME_BY_EXTENSION
}

private fun String.isMediaMimeType(): Boolean =
    startsWith("image/") || startsWith("video/")

private fun normalizeContentType(contentTypeHeader: String?): String? =
    contentTypeHeader
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf { it.isNotBlank() }

private fun urlBasename(url: String): String {
    val path = runCatching { URI(url).rawPath }.getOrNull()
        ?: url.substringBefore('?').substringBefore('#')
    val encoded = path.substringAfterLast('/', missingDelimiterValue = path)
    return runCatching {
        URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
    }.getOrDefault(encoded)
}

private fun extensionFromUrl(url: String): String? {
    val base = urlBasename(url)
    return base.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.US)
        .takeIf { it.isNotBlank() }
}

private fun sanitizeFilename(raw: String): String =
    raw.trim()
        .replace(FILENAME_UNSAFE, "_")
        .replace(Regex("_+"), "_")
        .replace(LEADING_TRAILING_DOTS, "")
        .trim('_', '-', '.')

private fun shortHash(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
    return digest.take(6).joinToString("") { "%02x".format(it) }
}
