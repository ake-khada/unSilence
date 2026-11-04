package com.unsilence.app.data.relay

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Only genuine completion of every requested relay allows a visit to reuse its result. */
internal suspend fun awaitThreadOneShot(
    outcomes: OneShotOutcomes,
    subId: String,
    relays: List<String>,
    dispatch: suspend ((String) -> Unit) -> Set<String>,
    cleanup: () -> Unit,
    onEose: (String) -> Unit = {},
): Boolean {
    val result = outcomes.fetch(subId, relays, 8_000L, dispatch, cleanup,
        onOutcome = { relay, outcome -> if (outcome == OneShotOutcome.EOSE) onEose(relay) })
    return result.isNotEmpty() && result.values.all { it == OneShotOutcome.EOSE }
}

/** Completion belongs to one root and survives its temporary navigation pause. */
internal suspend fun fetchThreadRequests(
    eventId: String,
    token: String,
    relays: List<String>,
    completed: Set<Pair<String, String>>,
    fetch: suspend (subId: String, relays: List<String>, req: String, onEose: (String) -> Unit) -> Unit,
    onCompleted: (query: String, relay: String) -> Unit,
    replyRelays: List<String> = relays,
) = coroutineScope {
    for (req in threadRequestPayloads(eventId, token)) {
        val subId = NostrJson.parseToJsonElement(req).jsonArray[1].jsonPrimitive.content
        val query = subId.removeSuffix("-$token")
        val targets = if (query == "thread-replies") replyRelays else relays
        val pending = targets.mapNotNull(::normalizeRelayUrl).distinct()
            .filter { (query to it) !in completed }
        if (pending.isNotEmpty()) launch {
            fetch(subId, pending, req) { relay -> onCompleted(query, relay) }
        }
    }
}

/** Keep ID backoff and reply-filter rejection separate from reaction/receipt queries. */
internal fun threadRequestPayloads(eventId: String, token: String): List<String> {
    // Deep links can also open poll responses and zap events retained by MES.
    val rootKinds = EVENT_REFERENCE_FETCH_KINDS + listOf(1018, 9734, 9735)
    // The event itself
    val eventReq = buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive("thread-event-$token"))
        add(buildJsonObject {
            put("ids", buildJsonArray { add(JsonPrimitive(eventId)) })
            put("kinds", buildJsonArray { rootKinds.forEach { add(JsonPrimitive(it)) } })
        })
    }.toString()

    // Lowercase e finds direct replies; uppercase E also finds nested comments
    // rooted at this event, even when the intermediate parent is not cached.
    val repliesReq = buildThreadRepliesReq("thread-replies-$token", eventId)

    // Reactions on this event and its replies
    val reactionsReq = buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive("thread-reactions-$token"))
        add(buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(7)) })
            put("#e",    buildJsonArray { add(JsonPrimitive(eventId)) })
            put("limit", JsonPrimitive(100))
        })
    }.toString()

    // Zaps on this event and its replies
    val zapsReq = buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive("thread-zaps-$token"))
        add(buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(9735)) })
            put("#e",    buildJsonArray { add(JsonPrimitive(eventId)) })
            put("limit", JsonPrimitive(100))
        })
    }.toString()

    return listOf(eventReq, repliesReq, reactionsReq, zapsReq)
}

/** Prefixes owned by cancellable, outcome-tracked one-shots (including startup metadata). */
internal fun isOwnedOneShotRequest(subId: String): Boolean =
    subId.startsWith("thread-") || subId.startsWith("article-comments-") ||
        subId.startsWith("comment-replies-") || subId.startsWith("comment-parents-") ||
        subId.startsWith("kind10002-") || subId.startsWith("following-history-")
