package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.memory.NostrEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Subscription"

/**
 * Low-level Nostr REQ subscription primitive.
 *
 * Wraps [RelayTransport] (for sending) and [TapRegistration] (for receiving)
 * to provide a callback-oriented subscribe API. Mirrors Jumble's
 * client.subscribe() in client.service.ts:422.
 *
 * Lifecycle:
 *   1. subscribe(urls, filter, callbacks) — generates subId, ensures
 *      connections, sends REQ to each relay, returns Handle.
 *   2. Per-relay events arrive via the registered tap. Demuxed by subId.
 *      Cross-relay dedup via knownIds. Forwarded to onevent.
 *   3. Per-relay EOSE arrives via the tap. eosedCount incremented.
 *      oneose(allEosed) fires each time a relay completes; the boolean
 *      indicates whether ALL relays have EOSE'd.
 *   4. Per-relay CLOSED arrives via the tap. onclose fires with the reason.
 *      auth-required reasons are passed through unmodified — caller decides
 *      what to do (NIP-42 auth handling lives in callers, not here).
 *   5. Handle.close() sends CLOSE to each relay and unregisters the
 *      subscription. Subsequent messages for this subId are dropped.
 *
 * Reconnect handling is NOT in this primitive. If a relay drops mid-sub,
 * onclose fires once and that relay is done. Higher layers (TimelineService,
 * RelayBrowseSession) handle reconnect-and-resub.
 *
 * Thread safety: subscribe() is suspend (network I/O). Callbacks fire on
 * whatever thread the tap fires on (currently EventProcessor's drainer
 * thread — IO-bound). Callbacks must not block.
 */
@Singleton
class Subscription @Inject constructor(
    private val transport: RelayTransport,
    private val tapRegistration: TapRegistration,
) {
    /** Active subscription state, keyed by subId. */
    private data class SubState(
        val urls: Set<String>,
        val onevent: (NostrEvent) -> Unit,
        val oneose: (allEosed: Boolean) -> Unit,
        val onclose: (url: String, reason: String) -> Unit,
        val knownIds: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val eosedRelays: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val closedRelays: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    )

    private val subs = ConcurrentHashMap<String, SubState>()
    private val seqCounter = AtomicLong(0)

    /**
     * The single tap registered with [TapRegistration]. Demuxes by subId,
     * dispatches to per-sub callbacks. Registered lazily on first subscribe.
     */
    private val tap = RelayMessageTap { raw, relayUrl ->
        dispatchMessage(raw, relayUrl)
    }
    private var tapRegistered = false
    private val tapLock = Any()

    /**
     * Subscribe to [urls] with [filter]. Caller-provided callbacks fire on
     * incoming events / EOSEs / closes for this subscription's subId.
     *
     * Returns a [Handle] — call close() to terminate. Idempotent close.
     */
    suspend fun subscribe(
        urls: List<String>,
        filter: NostrFilter,
        onevent: (NostrEvent) -> Unit,
        oneose: (allEosed: Boolean) -> Unit = {},
        onclose: (url: String, reason: String) -> Unit = { _, _ -> },
    ): Handle {
        ensureTapRegistered()

        val urlSet = urls.toSet()
        val subId = generateSubId(urls)
        val state = SubState(
            urls = urlSet,
            onevent = onevent,
            oneose = oneose,
            onclose = onclose,
        )
        subs[subId] = state

        // Connection establishment is the caller's responsibility for
        // PERSISTENT-purpose relays (browse / outbox / home feed). For
        // ad-hoc one-off subs we still call connectAndAwait — it's a no-op
        // for already-connected relays.
        transport.connectAndAwait(urls, timeoutMs = 5_000)

        // Build REQ once, send to each relay.
        val req = buildReqJson(subId, filter)
        for (url in urls) {
            val sent = transport.sendToRelay(url, req)
            if (!sent) {
                // Connection not present — treat as immediate EOSE for this relay
                // so callers don't hang waiting on it.
                handleRelayEose(subId, url)
            }
        }

        return HandleImpl(subId)
    }

    private fun ensureTapRegistered() {
        synchronized(tapLock) {
            if (tapRegistered) return
            tapRegistration.registerTap(tap)
            tapRegistered = true
        }
    }

    /**
     * Demux an incoming raw relay message. Cheap parse: reads only the
     * type tag and subId without full JSON deserialization for the common
     * EVENT case.
     */
    private fun dispatchMessage(raw: String, relayUrl: String) {
        when {
            raw.startsWith("[\"EVENT\"") -> dispatchEvent(raw, relayUrl)
            raw.startsWith("[\"EOSE\"") -> {
                val subId = extractSubId(raw) ?: return
                handleRelayEose(subId, relayUrl)
            }
            raw.startsWith("[\"CLOSED\"") -> {
                val subId = extractSubId(raw) ?: return
                val reason = extractClosedReason(raw) ?: ""
                handleRelayClosed(subId, relayUrl, reason)
            }
            // OK / NOTICE / AUTH messages aren't routed here — they're handled
            // upstream (RelayPool for OK, EventProcessor for NOTICE).
        }
    }

    private fun dispatchEvent(raw: String, relayUrl: String) {
        // We need both the subId and the event. Full JSON parse required.
        val msg = try {
            NostrJson.parseToJsonElement(raw).jsonArray
        } catch (_: Exception) {
            return
        }
        if (msg.size < 3) return
        val subId = (msg[1] as? JsonPrimitive)?.content ?: return
        val state = subs[subId] ?: return  // not our sub, or already closed

        val obj = msg[2].jsonObject
        val eventId = (obj["id"] as? JsonPrimitive)?.content ?: return

        // Cross-relay dedup
        if (!state.knownIds.add(eventId)) return

        val event = parseEvent(obj, relayUrl) ?: return
        try {
            state.onevent(event)
        } catch (t: Throwable) {
            Log.w(TAG, "onevent threw for sub=$subId", t)
        }
    }

    private fun handleRelayEose(subId: String, relayUrl: String) {
        val state = subs[subId] ?: return
        if (!state.eosedRelays.add(relayUrl)) return  // already EOSE'd this relay
        val allEosed = state.eosedRelays.size >= state.urls.size
        try {
            state.oneose(allEosed)
        } catch (t: Throwable) {
            Log.w(TAG, "oneose threw for sub=$subId", t)
        }
    }

    private fun handleRelayClosed(subId: String, relayUrl: String, reason: String) {
        val state = subs[subId] ?: return
        if (!state.closedRelays.add(relayUrl)) return
        try {
            state.onclose(relayUrl, reason)
        } catch (t: Throwable) {
            Log.w(TAG, "onclose threw for sub=$subId", t)
        }
        // Treat as EOSE-equivalent so callers waiting on allEosed don't hang.
        if (!state.eosedRelays.contains(relayUrl)) {
            handleRelayEose(subId, relayUrl)
        }
    }

    private fun parseEvent(obj: kotlinx.serialization.json.JsonObject, relayUrl: String): NostrEvent? {
        return try {
            val id = (obj["id"] as? JsonPrimitive)?.content ?: return null
            val pubkey = (obj["pubkey"] as? JsonPrimitive)?.content ?: return null
            val kind = (obj["kind"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
            val createdAt = (obj["created_at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
            val content = (obj["content"] as? JsonPrimitive)?.content ?: ""
            val sig = (obj["sig"] as? JsonPrimitive)?.content ?: ""
            val tagsArray = (obj["tags"] as? JsonArray) ?: JsonArray(emptyList())
            val tags = tagsArray.mapNotNull { tagEl ->
                (tagEl as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
            }
            val tagsJson = tagsArray.toString()
            val now = System.currentTimeMillis()
            NostrEvent(
                id = id,
                pubkey = pubkey,
                kind = kind,
                createdAt = createdAt,
                content = content,
                tags = tags,
                tagsJson = tagsJson,
                sig = sig,
                relayUrl = relayUrl,
                replyToId = null,
                rootId = null,
                hasContentWarning = false,
                contentWarningReason = null,
                firstSeenAt = now,
                relaysSeen = ConcurrentHashMap.newKeySet<String>().also { it.add(relayUrl) },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun generateSubId(urls: List<String>): String {
        val seq = seqCounter.incrementAndGet()
        val urlHash = urls.joinToString(",").hashCode().toString(16)
        return "sub-$seq-$urlHash"
    }

    private fun buildReqJson(subId: String, filter: NostrFilter): String =
        buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(filter.toJsonObject())
        }.toString()

    private fun extractSubId(raw: String): String? {
        // ["EOSE","sub-id"] or ["CLOSED","sub-id","reason"]
        val firstComma = raw.indexOf(',')
        if (firstComma < 0) return null
        val firstQuote = raw.indexOf('"', firstComma + 1)
        if (firstQuote < 0) return null
        val secondQuote = raw.indexOf('"', firstQuote + 1)
        if (secondQuote < 0) return null
        return raw.substring(firstQuote + 1, secondQuote)
    }

    private fun extractClosedReason(raw: String): String? {
        val msg = try {
            NostrJson.parseToJsonElement(raw).jsonArray
        } catch (_: Exception) {
            return null
        }
        if (msg.size < 3) return null
        return (msg[2] as? JsonPrimitive)?.content
    }

    /** Test-only: synchronous direct dispatch. Avoid in production paths. */
    internal fun dispatchForTest(raw: String, relayUrl: String) = dispatchMessage(raw, relayUrl)

    /** Test-only: drop all subs and unregister tap. */
    internal fun resetForTest() {
        subs.clear()
        synchronized(tapLock) {
            if (tapRegistered) {
                tapRegistration.unregisterTap(tap)
                tapRegistered = false
            }
        }
    }

    interface Handle {
        fun close()
    }

    private inner class HandleImpl(private val subId: String) : Handle {
        @Volatile private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            val state = subs.remove(subId) ?: return
            val closeMsg = buildJsonArray {
                add(JsonPrimitive("CLOSE"))
                add(JsonPrimitive(subId))
            }.toString()
            for (url in state.urls) {
                transport.sendToRelay(url, closeMsg)
            }
        }
    }
}
