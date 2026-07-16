package com.unsilence.app.ui.relays

import com.unsilence.app.data.memory.RelaySet
import com.unsilence.app.data.relay.normalizeRelayUrl
import java.util.Locale

private val MODELED_RELAY_SET_TAGS = setOf("d", "title", "description", "image", "relay")

data class RelaySetEditorDraft(
    val dTag: String? = null,
    val title: String = "",
    val description: String = "",
    val image: String = "",
    val members: List<String> = emptyList(),
    val relayTags: List<List<String>> = emptyList(),
    val foreignTags: List<List<String>> = emptyList(),
)

data class RelaySetPublishPayload(
    val dTag: String,
    val title: String,
    val description: String?,
    val image: String?,
    val members: List<String>,
    val tags: List<List<String>>,
)

internal fun relaySetEditorDraft(set: RelaySet?): RelaySetEditorDraft =
    if (set == null) {
        RelaySetEditorDraft()
    } else {
        RelaySetEditorDraft(
            dTag = set.dTag,
            title = set.title ?: set.dTag,
            description = set.description.orEmpty(),
            image = set.image.orEmpty(),
            members = set.members,
            relayTags = set.relayTags,
            foreignTags = set.foreignTags,
        )
    }

internal fun relaySetDTagBase(title: String): String =
    title.trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "relay-set" }

/**
 * Builds the complete kind-30002 tag envelope. Existing relay tags are selected by member URL
 * rather than rebuilt, preserving marker and extension bytes written by other clients.
 */
internal fun buildRelaySetPublishPayload(
    dTag: String,
    draft: RelaySetEditorDraft,
): RelaySetPublishPayload {
    val title = draft.title.trim()
    val description = draft.description.trim().takeIf { it.isNotEmpty() }
    val image = draft.image.trim().takeIf { it.isNotEmpty() }
    val members = draft.members
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy(::relayMemberIdentity)
    val memberKeys = members.mapTo(linkedSetOf(), ::relayMemberIdentity)
    val retainedRelayTags = draft.relayTags
        .filter { tag ->
            tag.size >= 2 && tag[0] == "relay" && relayMemberIdentity(tag[1]) in memberKeys
        }
        .map { it.toList() }
    val representedKeys = retainedRelayTags.mapTo(mutableSetOf()) { relayMemberIdentity(it[1]) }
    val newRelayTags = members
        .filter { relayMemberIdentity(it) !in representedKeys }
        .map { listOf("relay", it) }
    val foreignTags = draft.foreignTags
        .filter { it.firstOrNull() !in MODELED_RELAY_SET_TAGS }
        .map { it.toList() }

    val tags = buildList {
        add(listOf("d", dTag))
        if (title.isNotEmpty()) add(listOf("title", title))
        description?.let { add(listOf("description", it)) }
        image?.let { add(listOf("image", it)) }
        addAll(retainedRelayTags)
        addAll(newRelayTags)
        addAll(foreignTags)
    }
    return RelaySetPublishPayload(
        dTag = dTag,
        title = title,
        description = description,
        image = image,
        members = members,
        tags = tags,
    )
}

private fun relayMemberIdentity(url: String): String = normalizeRelayUrl(url) ?: url.trim()
