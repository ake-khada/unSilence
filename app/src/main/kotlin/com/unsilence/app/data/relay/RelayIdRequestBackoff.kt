package com.unsilence.app.data.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val ID_REQUEST_FAILURE_THRESHOLD = 3
internal const val ID_REQUEST_FAILURE_WINDOW_MS = 5 * 60_000L
internal const val ID_REQUEST_BACKOFF_MS = 5 * 60_000L
internal const val ID_REQUEST_TRACKING_MS = 60_000L
internal const val ID_REQUEST_MAX_RELAYS = 256
internal const val ID_REQUEST_MAX_PENDING = 1_024

/** Only an entirely ID-constrained REQ qualifies. Mixed OR filters and search stay live. */
internal fun idLookupSubId(req: String): String? {
    if (!req.contains("\"ids\"")) return null
    val frame = runCatching { NostrJson.parseToJsonElement(req) as? JsonArray }.getOrNull()
        ?: return null
    if ((frame.firstOrNull() as? JsonPrimitive)?.contentOrNull != "REQ" || frame.size < 3) return null
    val subId = (frame[1] as? JsonPrimitive)?.contentOrNull ?: return null
    return subId.takeIf {
        frame.drop(2).all { element ->
            val filter = element as? JsonObject ?: return@all false
            val ids = filter["ids"] as? JsonArray ?: return@all false
            "search" !in filter && ids.isNotEmpty() && ids.all { id ->
                (id as? JsonPrimitive)?.isString == true && id.content.isNotEmpty()
            }
        }
    }
}

/**
 * Request-class backoff, not relay health or a persisted search-only classification.
 * Only an empty CLOSED for an actually sent ID REQ teaches a failure. Other endings
 * retain their existing policies. A genuine ID EOSE (even zero results) proves the
 * query class works. Socket ownership prevents late/duplicate replies adding strikes.
 *
 * State and outstanding observations are bounded; missing terminal messages expire.
 * Elapsed time includes device sleep; wall-clock changes cannot prolong the pause.
 * No callback executes under this monitor (the pool also has its lifecycle lock).
 */
internal class RelayIdRequestBackoff(
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val maxRelays: Int = ID_REQUEST_MAX_RELAYS,
    private val maxPending: Int = ID_REQUEST_MAX_PENDING,
) {
    private data class Failure(val since: Long, var count: Int = 0, var until: Long = 0)
    private data class Key(val owner: Any, val subId: String)
    private data class Pending(val url: String, val sentAt: Long)
    private val failures = LinkedHashMap<String, Failure>()
    private val pending = LinkedHashMap<Key, Pending>()
    private var sent = 0L
    private var skipped = 0L
    private var opened = 0L

    init {
        require(maxRelays > 0 && maxPending > 0)
    }

    /** Selection/dispatch denials, not a count of wire requests saved. */
    @Synchronized
    fun shouldSkip(url: String): Boolean {
        val normalized = normalizeRelayUrl(url) ?: return false
        val blocked = failureAt(normalized, nowMs())?.until?.let { it != 0L } == true
        if (blocked) skipped++
        return blocked
    }

    fun shouldSkip(url: String, req: String): Boolean {
        val normalized = normalizeRelayUrl(url) ?: return false
        // Most relays have no ID failure history. Avoid decoding their REQs at
        // preflight checks; the final send classifies once for response ownership.
        synchronized(this) {
            if (failureAt(normalized, nowMs())?.until?.let { it != 0L } != true) return false
        }
        return idLookupSubId(req) != null && shouldSkip(normalized)
    }

    /** Shared final gate for pooled, queue-flushed and ephemeral sends. Null means sent. */
    fun send(owner: Any, url: String, req: String, wireSend: () -> Boolean): OneShotOutcome? {
        val subId = idLookupSubId(req)
        if (subId == null) return if (wireSend()) null else OneShotOutcome.FAILED
        val normalized = normalizeRelayUrl(url) ?: return OneShotOutcome.SKIPPED
        val key = Key(owner, subId)
        synchronized(this) {
            if (shouldSkip(normalized)) return OneShotOutcome.SKIPPED
            val now = nowMs()
            prunePending(now)
            pending.remove(key)
            pending[key] = Pending(normalized, now)
            while (pending.size > maxPending) pending.remove(pending.keys.first())
        }
        val success = try {
            wireSend()
        } catch (error: Exception) {
            synchronized(this) { pending.remove(key) }
            throw error
        }
        synchronized(this) {
            if (success) sent++ else pending.remove(key)
        }
        return if (success) null else OneShotOutcome.FAILED
    }

    /** Returns true only when this response opens a new backoff window. */
    @Synchronized
    fun onClosed(owner: Any, subId: String, reason: String): Boolean {
        val request = takePending(owner, subId) ?: return false
        if (reason.isNotEmpty()) return false
        val now = nowMs()
        val failure = failureAt(request.url, now) ?: Failure(now).also {
            failures[request.url] = it
            while (failures.size > maxRelays) failures.remove(failures.keys.first())
        }
        // Already-sent rejections must not extend the fixed expiry indefinitely.
        if (failure.until != 0L) return false
        if (++failure.count < ID_REQUEST_FAILURE_THRESHOLD) return false
        failure.until = now + ID_REQUEST_BACKOFF_MS
        opened++
        return true
    }

    @Synchronized
    fun onEose(owner: Any, subId: String) {
        val request = takePending(owner, subId) ?: return
        failures.remove(request.url)
    }

    @Synchronized
    fun forgetSubscription(subId: String) {
        pending.keys.removeAll { it.subId == subId }
    }

    @Synchronized
    fun forgetOwner(owner: Any) {
        pending.keys.removeAll { it.owner === owner }
    }

    @Synchronized
    fun clear() {
        failures.clear()
        pending.clear()
        sent = 0
        skipped = 0
        opened = 0
    }

    data class Metrics(val sent: Long, val skipped: Long, val opened: Long, val activeRelays: Int)

    @Synchronized
    fun snapshotMetrics(): Metrics {
        val now = nowMs()
        failures.entries.removeAll { (_, failure) -> expired(failure, now) }
        prunePending(now)
        return Metrics(sent, skipped, opened, failures.values.count { it.until != 0L }).also {
            sent = 0
            skipped = 0
            opened = 0
        }
    }

    private fun expired(failure: Failure, now: Long): Boolean =
        if (failure.until != 0L) now >= failure.until
        else now - failure.since >= ID_REQUEST_FAILURE_WINDOW_MS

    private fun failureAt(url: String, now: Long): Failure? {
        val failure = failures[url] ?: return null
        if (!expired(failure, now)) return failure
        failures.remove(url)
        return null
    }

    private fun takePending(owner: Any, subId: String): Pending? {
        prunePending(nowMs())
        return pending.remove(Key(owner, subId))
    }

    private fun prunePending(now: Long) {
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.sentAt < ID_REQUEST_TRACKING_MS) break
            iterator.remove()
        }
    }
}
