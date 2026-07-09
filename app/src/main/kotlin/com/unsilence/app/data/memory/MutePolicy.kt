package com.unsilence.app.data.memory

import com.unsilence.app.data.model.ContentParser
import com.unsilence.app.data.relay.NostrJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

const val DEFAULT_HASHTAG_CAP = 5

fun isPubkeyMuted(pubkey: String, muteList: MuteList?): Boolean =
    muteList != null && (pubkey in muteList.pubkeys || pubkey in muteList.privatePubkeys)

fun normalizedMutedHashtags(muteList: MuteList?): Set<String> {
    if (muteList == null) return emptySet()
    return (muteList.hashtags.asSequence() + muteList.privateHashtags.asSequence())
        .mapNotNull(::normalizeMutedHashtag)
        .toSet()
}

fun isMuted(event: NostrEvent, muteList: MuteList?): Boolean =
    isMutedFields(
        pubkey = event.pubkey,
        id = event.id,
        kind = event.kind,
        content = event.content,
        tagProvider = { event.tags.asSequence() },
        muteList = muteList,
    )

fun isMuted(row: FeedRow, muteList: MuteList?): Boolean =
    isMutedFields(
        pubkey = row.pubkey,
        id = row.id,
        kind = row.kind,
        content = row.content,
        tagProvider = { parseTagsJson(row.tags).asSequence() },
        muteList = muteList,
    )

private fun isMutedFields(
    pubkey: String,
    id: String,
    kind: Int,
    content: String,
    tagProvider: () -> Sequence<List<String>>,
    muteList: MuteList?,
): Boolean {
    if (muteList == null) return false
    if (isPubkeyMuted(pubkey, muteList)) return true
    if (id in muteList.eventIds || id in muteList.privateEventIds) return true

    // kind-6 content is a NIP-18 JSON envelope, not user-authored text.
    if (kind != 6 && (muteList.words.isNotEmpty() || muteList.privateWords.isNotEmpty())) {
        val lowerContent by lazy(LazyThreadSafetyMode.NONE) { content.lowercase() }
        for (word in muteList.words) if (lowerContent.contains(word)) return true
        for (word in muteList.privateWords) if (lowerContent.contains(word)) return true
    }

    if (muteList.hashtags.isNotEmpty() || muteList.privateHashtags.isNotEmpty()) {
        for (tag in tagProvider()) {
            if (tag.size >= 2 && tag[0] == "t") {
                val ht = tag[1].lowercase()
                if (ht in muteList.hashtags || ht in muteList.privateHashtags) return true
            }
        }
    }
    return false
}

fun hashtagCount(tags: List<List<String>>, content: String): Int {
    val tagCount = tags.asSequence()
        .mapNotNull { tag ->
            if (tag.size >= 2 && tag[0] == "t") normalizeHashtagForCap(tag[1]) else null
        }
        .distinct()
        .count()
    val contentCount = ContentParser.findHashtags(content)
        .count { (_, _, tag) -> normalizeHashtagForCap(tag) != null }
    return maxOf(tagCount, contentCount)
}

fun exceedsHashtagCap(event: NostrEvent, cap: Int?): Boolean {
    if (cap == null) return false
    return hashtagCount(
        tags = event.tags,
        content = event.contentForHashtagCap(),
    ) > cap
}

fun exceedsHashtagCap(row: FeedRow, cap: Int?): Boolean {
    if (cap == null) return false
    return hashtagCount(
        tags = parseTagsJson(row.tags),
        content = row.contentForHashtagCap(),
    ) > cap
}

private fun NostrEvent.contentForHashtagCap(): String =
    if (kind == 6 || kind == 16) "" else content

private fun FeedRow.contentForHashtagCap(): String =
    if (kind == 6 || kind == 16) "" else content

private fun parseTagsJson(tagsJson: String): List<List<String>> =
    runCatching {
        NostrJson.parseToJsonElement(tagsJson).jsonArray.mapNotNull { tagElement ->
            val tagArray = tagElement as? JsonArray ?: return@mapNotNull null
            tagArray.mapNotNull { part -> runCatching { part.jsonPrimitive.content }.getOrNull() }
        }
    }.getOrDefault(emptyList())

private fun normalizeMutedHashtag(tag: String): String? {
    val normalized = tag.trim().trimStart('#').lowercase()
    return normalized.takeIf { it.isNotEmpty() }
}

private fun normalizeHashtagForCap(tag: String): String? {
    val normalized = tag.trim().trimStart('#').lowercase()
    if (normalized.isEmpty()) return null
    if (!normalized.first().isLetter()) return null
    return normalized.takeIf { value -> value.all { it.isLetterOrDigit() || it == '_' } }
}
