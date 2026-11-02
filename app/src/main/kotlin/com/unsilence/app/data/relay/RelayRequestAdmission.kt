package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.NostrEvent
import kotlinx.serialization.json.*

/**
 * Filters actually accepted by a single socket, not planned/queued requests.
 * Owned by RelayConnection: no global registry, timer, or second event decoder.
 * A same-ID replay replaces the filter only on successful send. Nostr supplies no
 * generation in EVENT/EOSE, so same-socket same-ID replies use the current filter.
 * After matching and verification, a frame is admitted once. Its callback may close
 * the request; that admitted frame still finishes ingestion. Cancellation revokes
 * later frames, not work already admitted. Remote termination drains the inbox
 * before clearing this table; explicit cancellation/replacement clears immediately.
 */
internal class RelayRequestAdmission(private val capacity: Int = 256) {
    private val active = HashMap<String, Request>()
    private var closed = false

    internal class Request(val subId: String, private val filters: List<EventFilter>) {
        fun matches(event: NostrEvent): Boolean = filters.any { it.matches(event) }
    }

    /** Short send/registration transaction; never held across parsing incoming events or crypto. */
    @Synchronized
    fun send(text: String, send: () -> Boolean): Boolean {
        if (closed) return false
        if (text.startsWith("[\"REQ\"")) {
            val request = parseRequest(text) ?: return false
            // Fail the send rather than evict an active owner's admission contract.
            if (request.subId !in active && active.size >= capacity) return false
            if (!send()) return false
            active[request.subId] = request
            return true
        }
        if (text.startsWith("[\"CLOSE\"")) {
            // Local cancellation revokes admission even if the socket cannot send CLOSE.
            extractSubscriptionIdFromRaw(text)?.let(active::remove)
        }
        return send()
    }

    @Synchronized fun request(subId: String): Request? = active[subId]
    @Synchronized fun isCurrent(request: Request): Boolean = active[request.subId] === request
    @Synchronized fun close(request: Request) { active.remove(request.subId, request) }
    @Synchronized fun clear() { closed = true; active.clear() }

    private fun parseRequest(text: String): Request? = try {
        val array = NostrJson.parseToJsonElement(text).jsonArray
        require(array.size >= 3 && array[0].jsonPrimitive.content == "REQ")
        val id = array[1].jsonPrimitive.also { require(it.isString) }.content
        Request(id, array.drop(2).map { EventFilter(it.jsonObject) })
    } catch (_: IllegalArgumentException) { null }

    /** NIP-01 deterministic fields only. Search is relay-defined; limit is not a predicate. */
    internal class EventFilter(filter: JsonObject) {
        private val ids = strings(filter, "ids")
        private val authors = strings(filter, "authors")
        private val kinds = filter["kinds"]?.jsonArray?.map { it.jsonPrimitive.int }?.toHashSet()
        private val since = filter["since"]?.jsonPrimitive?.long
        private val until = filter["until"]?.jsonPrimitive?.long
        private val tags = filter.entries.filter { it.key.startsWith("#") }.associate {
            it.key.substring(1) to requireNotNull(strings(filter, it.key))
        }

        fun matches(event: NostrEvent): Boolean =
            (ids == null || event.id in ids) &&
                (authors == null || event.pubkey in authors) &&
                (kinds == null || event.kind in kinds) &&
                (since == null || event.createdAt >= since) &&
                (until == null || event.createdAt <= until) &&
                tags.all { (name, values) ->
                    event.tags.any { it.size >= 2 && it[0] == name && it[1] in values }
                }

        private fun strings(filter: JsonObject, key: String): Set<String>? =
            filter[key]?.jsonArray?.map {
                it.jsonPrimitive.also { value -> require(value.isString) }.content
            }?.toHashSet()
    }
}
