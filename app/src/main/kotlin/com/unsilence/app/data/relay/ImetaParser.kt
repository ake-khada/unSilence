package com.unsilence.app.data.relay

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

data class ImetaMedia(
    val url: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val thumb: String?,
    val image: String?,  // NIP-92 poster image for video entries
    val fallbacks: List<String> = emptyList(),
    val durationSeconds: Double? = null,
    val sizeBytes: Long? = null,
)

object ImetaParser {

    fun parse(tagsJson: String): List<ImetaMedia> = runCatching {
        NostrJson.parseToJsonElement(tagsJson).jsonArray
            .filter { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "imeta" }
            .mapNotNull { tag -> parseFields(tag.jsonArray.drop(1).map { it.jsonPrimitive.content }) }
    }.getOrElse { emptyList() }

    fun parseFromList(tags: List<List<String>>): List<ImetaMedia> =
        tags.filter { it.firstOrNull() == "imeta" }
            .mapNotNull { tag -> parseFields(tag.drop(1)) }

    private fun parseFields(fields: List<String>): ImetaMedia? {
        val values = fields.map { field ->
            val space = field.indexOf(' ')
            if (space < 0) field to "" else field.substring(0, space) to field.substring(space + 1)
        }
        fun first(key: String): String? = values.firstOrNull { it.first == key }?.second

        val url = first("url")?.takeIf { it.isNotBlank() } ?: return null
        val dim = first("dim")
        val (width, height) = if (dim != null && dim.contains('x')) {
            val parts = dim.split('x', limit = 2)
            val parsedWidth = parts[0].toIntOrNull()
            val parsedHeight = parts[1].toIntOrNull()
            if (parsedWidth != null && parsedWidth > 0 && parsedHeight != null && parsedHeight > 0) {
                parsedWidth to parsedHeight
            } else {
                null to null
            }
        } else {
            null to null
        }
        return ImetaMedia(
            url = url,
            mimeType = first("m"),
            width = width,
            height = height,
            thumb = first("thumb"),
            image = first("image"),
            fallbacks = values.asSequence()
                .filter { it.first == "fallback" }
                .map { it.second }
                .filter { it.startsWith("https://") || it.startsWith("http://") }
                .distinct()
                .toList(),
            durationSeconds = first("duration")?.toDoubleOrNull()?.takeIf { it >= 0.0 },
            sizeBytes = first("size")?.toLongOrNull()?.takeIf { it > 0L },
        )
    }

    fun videos(tagsJson: String): List<ImetaMedia> =
        parse(tagsJson).filter { it.mimeType?.startsWith("video/") == true }

    fun images(tagsJson: String): List<ImetaMedia> =
        parse(tagsJson).filter { it.mimeType?.startsWith("image/") == true }
}
