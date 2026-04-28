package com.unsilence.app.data.relay

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Nostr REQ filter (NIP-01). Mirrors Jumble's TSubRequestFilter.
 *
 * Every field is optional. Tag filters use the `#x` tag-name convention
 * (e.g., #e, #p, #t) — pass them via [tags] keyed by the bare tag name
 * (without `#`); serialization adds the prefix.
 */
data class NostrFilter(
    val kinds: List<Int>? = null,
    val authors: List<String>? = null,
    val ids: List<String>? = null,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
    val search: String? = null,
    val tags: Map<String, List<String>>? = null,
) {
    /** Serialize to a NIP-01 filter JSON object. Collections sorted for stable cache keys. */
    fun toJsonObject(): JsonObject = buildJsonObject {
        kinds?.let {
            put("kinds", buildJsonArray { it.sorted().forEach { v -> add(JsonPrimitive(v)) } })
        }
        authors?.let {
            put("authors", buildJsonArray { it.sorted().forEach { v -> add(JsonPrimitive(v)) } })
        }
        ids?.let {
            put("ids", buildJsonArray { it.sorted().forEach { v -> add(JsonPrimitive(v)) } })
        }
        since?.let { put("since", JsonPrimitive(it)) }
        until?.let { put("until", JsonPrimitive(it)) }
        limit?.let { put("limit", JsonPrimitive(it)) }
        search?.let { put("search", JsonPrimitive(it)) }
        tags?.toSortedMap()?.forEach { (name, values) ->
            put("#$name", buildJsonArray { values.sorted().forEach { add(JsonPrimitive(it)) } })
        }
    }
}
