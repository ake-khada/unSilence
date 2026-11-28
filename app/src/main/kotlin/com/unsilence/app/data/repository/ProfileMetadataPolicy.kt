package com.unsilence.app.data.repository

import com.unsilence.app.data.relay.NostrJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The kind-0 fields owned by unSilence's Edit Profile form. */
internal data class EditableProfileMetadata(
    val name: String,
    val displayName: String,
    val about: String,
    val picture: String,
    val banner: String,
    val nip05: String,
    val lud16: String,
    val website: String,
)

internal fun profileMetadataHasChanges(
    original: EditableProfileMetadata,
    edited: EditableProfileMetadata,
): Boolean = listOf(
    original.name to edited.name,
    original.displayName to edited.displayName,
    original.about to edited.about,
    original.picture to edited.picture,
    original.banner to edited.banner,
    original.nip05 to edited.nip05,
    original.lud16 to edited.lud16,
    original.website to edited.website,
).any { (before, after) -> before.trim() != after.trim() }

internal data class MergedProfileMetadata(val content: String, val tags: List<List<String>>)

private val profileImageKeys = listOf("picture", "avatar", "image", "banner")

private fun Map<String, JsonElement>.imageUrls(): Set<String> = profileImageKeys.mapNotNullTo(mutableSetOf()) {
    (this[it] as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.takeIf(String::isNotBlank)
}

/**
 * Applies the form-owned fields to an existing kind-0 document.
 *
 * JSON and tags are merged from the same relay event. Existing field-mirror
 * tags follow explicit edits. All unrelated extensions retain their order and values.
 * Blank values remove their canonical key and any legacy alias understood by MES, so
 * clearing a field cannot be undone by an old alias on the next parse.
 * Returns null rather than rebuilding from scratch when the retained document
 * is not a JSON object.
 */
internal fun mergeProfileMetadata(
    existingContent: String,
    edited: EditableProfileMetadata,
    original: EditableProfileMetadata? = null,
    existingTags: List<List<String>> = emptyList(),
): MergedProfileMetadata? {
    val existing = runCatching {
        NostrJson.parseToJsonElement(existingContent).jsonObject
    }.getOrNull() ?: return null

    val merged = LinkedHashMap(existing)
    val changedFields = mutableMapOf<String, String>()

    fun applyField(
        key: String,
        aliases: Set<String> = emptySet(),
        editedValue: String,
        originalValue: String?,
    ) {
        val normalized = editedValue.trim()
        // When a form was opened from an older snapshot, untouched fields must
        // retain the value from the freshly fetched kind-0. Only an actual user
        // edit is allowed to overwrite the relay-fresh document.
        if (originalValue != null && normalized == originalValue.trim()) return
        merged.remove(key)
        changedFields[key] = normalized
        for (alias in aliases) {
            merged.remove(alias)
            changedFields[alias] = normalized
        }
        normalized.takeIf(String::isNotEmpty)?.let { merged[key] = JsonPrimitive(it) }
    }

    applyField("name", editedValue = edited.name, originalValue = original?.name)
    applyField(
        key = "display_name",
        aliases = setOf("displayName"),
        editedValue = edited.displayName,
        originalValue = original?.displayName,
    )
    applyField("about", editedValue = edited.about, originalValue = original?.about)
    applyField(
        key = "picture",
        aliases = setOf("avatar", "image"),
        editedValue = edited.picture,
        originalValue = original?.picture,
    )
    applyField("banner", editedValue = edited.banner, originalValue = original?.banner)
    applyField("nip05", editedValue = edited.nip05, originalValue = original?.nip05)
    applyField("lud16", editedValue = edited.lud16, originalValue = original?.lud16)
    applyField("website", editedValue = edited.website, originalValue = original?.website)

    val displacedImages = if (profileImageKeys.any(changedFields::containsKey)) {
        (existing.imageUrls() - merged.imageUrls()).mapTo(mutableSetOf()) { "url $it" }
    } else {
        emptySet()
    }
    val tags = existingTags.mapNotNull { tag ->
        val key = tag.firstOrNull()
        val value = changedFields[key]
        when {
            key == "client" -> null // The previous editor did not publish this replacement.
            key == "imeta" && displacedImages.isNotEmpty() &&
                tag.singleOrNull { it.startsWith("url ") } in displacedImages -> null
            value == null -> tag
            value.isEmpty() -> null
            else -> listOf(tag.first(), value) + tag.drop(2)
        }
    }
    return MergedProfileMetadata(JsonObject(merged).toString(), tags)
}
