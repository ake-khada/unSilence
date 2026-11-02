package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.NostrEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Existing processor/drainer fixtures isolate storage, crypto and duplicate behavior.
 * Give their envelope a genuinely sent unrestricted request. This adapter exists
 * only in tests; production ingestion always requires the source connection.
 * Request-admission tests instead supply their own connection and restrictive REQs.
 */
internal suspend fun EventProcessor.process(raw: String, rawRelayUrl: String): Boolean {
    val connection = fixtureRequest(raw, rawRelayUrl)
    return try { process(raw, connection) } finally { connection.close() }
}

internal fun EventProcessor.parseAndVerify(raw: String, relayUrl: String): NostrEvent? {
    val connection = fixtureRequest(raw, relayUrl)
    return try { parseAndVerify(raw, connection) } finally { connection.close() }
}

private fun fixtureRequest(raw: String, url: String): RelayConnection =
    AdmissionSocket().connection(url).also { connection ->
        connection.send(buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(extractSubscriptionIdFromRaw(raw) ?: "fixture"))
            add(buildJsonObject {})
        }.toString())
    }
