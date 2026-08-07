package com.unsilence.app.data.memory

import com.unsilence.app.data.model.ContentParser
import com.unsilence.app.data.model.RepostPayload
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

fun isMuted(
    event: NostrEvent,
    muteList: MuteList?,
    targetResolver: ((String) -> NostrEvent?)? = null,
): Boolean {
    if (muteList == null) return false
    // The wrapper remains user-authored: muting the reposter or this exact
    // repost must always work independently of target resolution.
    if (isMutedIdentity(event.pubkey, event.id, muteList)) return true

    val subject = effectiveModerationSubject(event, targetResolver)
        ?: return repostTargetId(event)?.let { isEventIdMuted(it, muteList) } == true
    if (subject.id != event.id && isMutedIdentity(subject.pubkey, subject.id, muteList)) return true
    return isMutedContent(subject.content, subject.tags.asSequence(), muteList)
}

fun isMuted(row: FeedRow, muteList: MuteList?): Boolean {
    if (muteList == null) return false
    if (isMutedIdentity(row.pubkey, row.id, muteList)) return true
    // A flattened row cannot independently resolve a reference target. It can
    // still honor an exact target-id mute, but must never inspect envelope JSON.
    if (row.kind == 6 || row.kind == 16) {
        return row.rootId?.let { isEventIdMuted(it, muteList) } == true
    }
    return isMutedContent(row.content, parseTagsJson(row.tags).asSequence(), muteList)
}

private data class ModerationSubject(
    val id: String,
    val pubkey: String,
    val content: String,
    val tags: List<List<String>>,
)

private fun effectiveModerationSubject(
    event: NostrEvent,
    targetResolver: ((String) -> NostrEvent?)?,
    visited: MutableSet<String> = mutableSetOf(),
): ModerationSubject? {
    if (!visited.add(event.id)) return null
    if (event.kind != 6 && event.kind != 16) {
        return ModerationSubject(event.id, event.pubkey, event.content, event.tags)
    }
    val verified = (event.repostInfo?.payload as? RepostPayload.VerifiedEmbedded)?.event
    if (verified != null) {
        return ModerationSubject(
            verified.id,
            verified.pubkey,
            verified.content,
            verified.tags,
        )
    }
    val targetId = repostTargetId(event) ?: return null
    val target = targetResolver?.invoke(targetId) ?: return null
    return effectiveModerationSubject(target, targetResolver, visited)
}

private fun repostTargetId(event: NostrEvent): String? =
    event.repostInfo?.targetId ?: event.rootId

private fun isMutedIdentity(pubkey: String, id: String, muteList: MuteList): Boolean =
    isPubkeyMuted(pubkey, muteList) || isEventIdMuted(id, muteList)

private fun isEventIdMuted(id: String, muteList: MuteList): Boolean =
    id in muteList.eventIds || id in muteList.privateEventIds

private fun isMutedContent(
    content: String,
    tags: Sequence<List<String>>,
    muteList: MuteList,
): Boolean {
    if (muteList.words.isNotEmpty() || muteList.privateWords.isNotEmpty()) {
        val lowerContent = content.lowercase()
        for (word in muteList.words) if (lowerContent.contains(word)) return true
        for (word in muteList.privateWords) if (lowerContent.contains(word)) return true
    }

    if (muteList.hashtags.isNotEmpty() || muteList.privateHashtags.isNotEmpty()) {
        for (tag in tags) {
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
    return exceedsHashtagCap(event, cap, targetResolver = null)
}

fun exceedsHashtagCap(
    event: NostrEvent,
    cap: Int?,
    targetResolver: ((String) -> NostrEvent?)?,
): Boolean {
    if (cap == null) return false
    val subject = effectiveModerationSubject(event, targetResolver) ?: return false
    return hashtagCount(
        tags = subject.tags,
        content = subject.content,
    ) > cap
}

fun exceedsHashtagCap(row: FeedRow, cap: Int?): Boolean {
    if (cap == null) return false
    // Wrapper content and tags describe the repost assertion, not the target.
    // MES-backed paths resolve the effective event before applying this cap.
    if (row.kind == 6 || row.kind == 16) return false
    return hashtagCount(
        tags = parseTagsJson(row.tags),
        content = row.content,
    ) > cap
}

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
