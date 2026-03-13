package com.unsilence.app.data.relay

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

data class ImetaMedia(
    val url: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val thumb: String?,
)

object ImetaParser {

    fun parse(tagsJson: String): List<ImetaMedia> = runCatching {
        NostrJson.parseToJsonElement(tagsJson).jsonArray
            .filter { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "imeta" }
            .mapNotNull { tag ->
                val kvMap = tag.jsonArray.drop(1).associate { entry ->
                    val s = entry.jsonPrimitive.content
                    val space = s.indexOf(' ')
                    if (space < 0) s to "" else s.substring(0, space) to s.substring(space + 1)
                }
                val url = kvMap["url"] ?: return@mapNotNull null
                val dim = kvMap["dim"]
                val (w, h) = if (dim != null && dim.contains("x")) {
                    val parts = dim.split("x", limit = 2)
                    val pw = parts[0].toIntOrNull()
                    val ph = parts[1].toIntOrNull()
                    if (pw != null && pw > 0 && ph != null && ph > 0) pw to ph else null to null
                } else null to null
                ImetaMedia(
                    url = url,
                    mimeType = kvMap["m"],
                    width = w,
                    height = h,
                    thumb = kvMap["thumb"],
                )
            }
    }.getOrElse { emptyList() }

    fun videos(tagsJson: String): List<ImetaMedia> =
        parse(tagsJson).filter { it.mimeType?.startsWith("video/") == true }

    fun images(tagsJson: String): List<ImetaMedia> =
        parse(tagsJson).filter { it.mimeType?.startsWith("image/") == true }
}
