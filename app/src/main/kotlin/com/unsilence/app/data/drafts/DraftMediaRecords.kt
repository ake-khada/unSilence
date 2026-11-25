package com.unsilence.app.data.drafts

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Keep the original per-account JSON readable by earlier releases. They ignore
// non-string preferences and can edit unrelated drafts without dropping media.
// A legacy save of this particular draft supersedes its previous media revision.
@Serializable
private data class DraftMediaRecord(
    val key: String,
    val updatedAt: Long,
    val attachments: List<IndexedMedia>,
    val hadUnsavedMedia: Boolean,
)

@Serializable
private data class IndexedMedia(val index: Int, val media: DraftBlock.LocalMedia)

private fun mediaKey(owner: String) = stringSetPreferencesKey("media:$owner")

internal fun Json.readDrafts(prefs: Preferences, owner: String): List<Draft> {
    val drafts = prefs[stringPreferencesKey(owner)]?.let { decodeFromString<List<Draft>>(it) }.orEmpty()
    val media = prefs[mediaKey(owner)] ?: return drafts
    val byKey = media.map { decodeFromString<DraftMediaRecord>(it) }.associateBy { it.key }
    return drafts.map { draft ->
        val record = byKey[draft.key]
        if (record == null || record.updatedAt != draft.updatedAt) draft else {
            val blocks = draft.blocks.toMutableList()
            for (attachment in record.attachments) {
                if (attachment.index !in 0..blocks.size) throw SerializationException("Invalid draft attachment position")
                blocks.add(attachment.index, attachment.media)
            }
            draft.copy(blocks = blocks, hadUnsavedMedia = record.hadUnsavedMedia)
        }
    }
}

internal fun Json.writeDrafts(prefs: MutablePreferences, owner: String, drafts: List<Draft>) {
    val media = mutableSetOf<String>()
    val compatible = drafts.map { draft ->
        val local = draft.blocks.mapIndexedNotNull { index, block ->
            (block as? DraftBlock.LocalMedia)?.let { IndexedMedia(index, it) }
        }
        if (local.isEmpty()) draft else {
            media += encodeToString(DraftMediaRecord(draft.key, draft.updatedAt, local, draft.hadUnsavedMedia))
            draft.copy(blocks = draft.blocks.filterNot { it is DraftBlock.LocalMedia }, hadUnsavedMedia = true)
        }
    }
    val key = stringPreferencesKey(owner)
    if (compatible.isEmpty()) prefs.remove(key) else prefs[key] = encodeToString(compatible)
    if (media.isEmpty()) prefs.remove(mediaKey(owner)) else prefs[mediaKey(owner)] = media
}
