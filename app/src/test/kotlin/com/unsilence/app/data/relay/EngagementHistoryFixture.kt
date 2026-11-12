package com.unsilence.app.data.relay

import kotlinx.serialization.json.*

/** Disposable relay history: independent tag matching, per-filter limits and OR union. */
internal fun engagementHistory(request: JsonArray, events: List<EventDto>): List<EventDto> {
    check(request[0].jsonPrimitive.content == "REQ")
    return request.drop(2).flatMap { element ->
        val filter = element.jsonObject
        check(filter.keys.all { it == "kinds" || it == "limit" || it.startsWith("#") })
        val kinds = filter["kinds"]!!.jsonArray.map { it.jsonPrimitive.int }
        val tags = filter.filterKeys { it.startsWith("#") }
        events.filter { event ->
            event.kind in kinds && tags.all { (key, values) ->
                val accepted = values.jsonArray.map { it.jsonPrimitive.content }
                event.tags.any { it.size >= 2 && it[0] == key.drop(1) && it[1] in accepted }
            }
        }.sortedWith(compareByDescending<EventDto> { it.createdAt }.thenBy { it.id })
            .take(filter["limit"]?.jsonPrimitive?.int ?: events.size)
    }.distinctBy { it.id }
}
