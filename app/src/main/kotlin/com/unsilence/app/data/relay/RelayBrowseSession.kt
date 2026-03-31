package com.unsilence.app.data.relay

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BrowseSession"

/**
 * Sealed browsing mode: owns one live subscription per target relay (max 3).
 *
 * These subs never enter [RelayPool.persistentSubs]. The session manages its
 * own lifecycle — start/stop/reconnect — independently of the pool's
 * persistent subscription machinery.
 *
 * Events flow through the existing [EventProcessor] → Room path unchanged.
 */
@Singleton
class RelayBrowseSession @Inject constructor(
    private val relayPool: RelayPool,
) {
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive

    private val generation = AtomicLong(0)
    private var activeTarget: List<String> = emptyList()
    private val activeSubIds = mutableMapOf<String, String>() // relayUrl -> subId

    init {
        // Register for reconnect notifications so we can resend browse subs
        // when a target relay drops and comes back.
        relayPool.onRelayReconnected = { url -> onRelayReconnected(url) }
    }

    fun start(relayUrls: List<String>) {
        stop()
        val gen = generation.incrementAndGet()
        val normalized = relayUrls.mapNotNull { normalizeRelayUrl(it) }.distinct().take(3)
        if (normalized.isEmpty()) return

        // Tag BROWSE purpose BEFORE connect so subscribeAfterConnect/replayPersistentSubs
        // checks isBrowseOnly(). If a URL also has PERSISTENT or OUTBOX purpose, the
        // purpose map correctly allows persistent replay on dual-purpose relays.
        for (url in normalized) {
            relayPool.addPurpose(url, ConnectionPurpose.BROWSE)
        }

        // Ensure sockets exist (reuses already-open connections).
        relayPool.connect(normalized)

        // Set engagement routing BEFORE sending subs so any engagement fetches
        // triggered by the new feed are already routed correctly.
        relayPool.browseEngagementTargets = normalized

        for (url in normalized) {
            val hash = url.hashCode()
            val subId = "browse-$gen-$hash"
            val req = buildBrowseReq(subId)
            activeSubIds[url] = subId
            relayPool.sendToRelay(url, req)
        }
        activeTarget = normalized
        _isActive.value = true
        Log.d(TAG, "Started gen=$gen on ${normalized.size} relay(s): $normalized")
    }

    fun stop() {
        if (activeSubIds.isEmpty()) return
        for ((url, subId) in activeSubIds) {
            relayPool.sendToRelay(url, """["CLOSE","$subId"]""")
        }
        // Clear engagement routing.
        relayPool.browseEngagementTargets = emptyList()
        // Remove BROWSE purpose. releaseIfUnused is currently a no-op (connections
        // are pooled), but gated on hasAnyPurpose so it's safe if it gains real logic.
        for (url in activeTarget) {
            relayPool.removePurpose(url, ConnectionPurpose.BROWSE)
            if (!relayPool.hasAnyPurpose(url)) {
                relayPool.releaseIfUnused(url)
            }
        }
        val count = activeSubIds.size
        activeSubIds.clear()
        activeTarget = emptyList()
        _isActive.value = false
        Log.d(TAG, "Stopped $count browse sub(s)")
    }

    fun getActiveTarget(): List<String> = activeTarget

    /**
     * Called by [RelayPool] when a relay successfully reconnects.
     * If the relay is one of our active browse targets, resend its sub
     * using the current generation — no persistentSubs involved.
     */
    fun onRelayReconnected(relayUrl: String) {
        val url = normalizeRelayUrl(relayUrl) ?: return
        if (!_isActive.value) return
        val subId = activeSubIds[url] ?: return
        val req = buildBrowseReq(subId)
        relayPool.sendToRelay(url, req)
        Log.d(TAG, "Resent browse sub '$subId' on reconnected $url")
    }

    private fun buildBrowseReq(subId: String): String =
        buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subId))
            add(buildJsonObject {
                put("kinds", buildJsonArray {
                    add(JsonPrimitive(1))
                    add(JsonPrimitive(6))
                    add(JsonPrimitive(20))
                    add(JsonPrimitive(21))
                    add(JsonPrimitive(30023))
                })
                put("limit", JsonPrimitive(300))
            })
        }.toString()
}
