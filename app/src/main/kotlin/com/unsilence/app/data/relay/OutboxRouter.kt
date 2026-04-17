package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.RelayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OutboxRouter"

/**
 * Implements NIP-65 outbox routing for the Following feed.
 *
 * Flow:
 * 1. EventProcessor dispatches kind-3 and kind-10002 events to handler methods.
 * 2. When kind 3 arrives → MES updates follows via updateFollows().
 * 3. Requests kind 10002 (relay list metadata) for all followed pubkeys.
 * 4. When kind 10002 events arrive → MES updates relay lists via handleReadWriteRelayList().
 * 5. Observes relay lists in MES → calls RelayPool.connectForAuthors()
 *    for the top 15 relays ranked by coverage (# of follows they serve).
 *
 * Architecture: all data flows Relay → MES. UI reads from MES via Flow.
 */
@Singleton
class OutboxRouter @Inject constructor(
    private val keyManager: KeyManager,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
) {
    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private var routingJob: Job? = null

    /** URLs currently tagged OUTBOX — tracked so we can remove stale purposes on set change. */
    private var currentOutboxUrls: Set<String> = emptySet()

    /**
     * Idempotent entry point. Called when the user switches to the Following feed.
     * Registers handlers and kicks off the relay fetch pipeline.
     */
    fun start() {
        if (!started.getAndSet(true)) launchRouting()
    }

    private fun launchRouting() {
        routingJob = Job(scope.coroutineContext[Job])
        val routingScope = CoroutineScope(scope.coroutineContext + routingJob!!)

        val userPubkeyHex = keyManager.getPublicKeyHex() ?: run {
            Log.w(TAG, "No pubkey — not logged in, skipping outbox routing")
            return
        }

        // Kind-3 and kind-10002 handlers are called directly by EventProcessor
        // via the immutable kindHandlers map — no registration needed.

        // ── Step 1: request the user's kind 3 from connected relays ──────────
        relayPool.fetchFollowList(userPubkeyHex)

        // ── Step 2: when follows appear in MES, request their kind 10002 ─────
        routingScope.launch {
            memoryEventStore.followsFlow(userPubkeyHex)
                .filter { it.isNotEmpty() }
                .first()          // one-shot: take the first non-empty emission
                .let { follows ->
                    Log.d(TAG, "Follow list loaded: ${follows.size} follows — fetching relay lists")
                    relayPool.fetchRelayLists(follows.toList())
                }
        }

        // ── Step 3: when relay lists arrive, route to write relays ───────────
        routingScope.launch {
            @OptIn(FlowPreview::class)
            memoryEventStore.allRelayListsFlow()
                .filter { it.isNotEmpty() }
                .debounce(2000)
                .collectLatest { relayLists ->
                    routeToWriteRelays(relayLists)
                }
        }
    }

    /** Cancel routing coroutines. Called on logout. */
    fun stop() {
        routingJob?.cancel()
        routingJob = null
        started.set(false)
        // Remove OUTBOX purposes for relays we were routing to.
        // disconnectAll() also clears the purpose map, but this keeps bookkeeping clean.
        for (url in currentOutboxUrls) {
            relayPool.removePurpose(url, ConnectionPurpose.OUTBOX)
        }
        currentOutboxUrls = emptySet()
        Log.d(TAG, "Stopped")
    }

    // ── Public handler methods (called by EventProcessor's immutable kindHandlers map) ──

    /**
     * Called by EventProcessor for every kind-3 event.
     * MES handles follow state via updateFollows(); this just logs.
     */
    suspend fun handleContactList(obj: kotlinx.serialization.json.JsonObject) {
        val pubkey = obj["pubkey"]?.jsonPrimitive?.content ?: return
        val userPubkeyHex = keyManager.getPublicKeyHex() ?: return
        if (pubkey != userPubkeyHex) return

        val tags = obj["tags"]?.jsonArray ?: return
        val count = tags.count { tag ->
            tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "p"
        }
        Log.d(TAG, "Kind-3 contact list: $count follows for $userPubkeyHex")
    }

    /**
     * Called by EventProcessor for every kind-10002 event.
     * MES handles relay list state via handleReadWriteRelayList(); this just logs.
     */
    suspend fun handleRelayList(obj: kotlinx.serialization.json.JsonObject) {
        val pubkey = obj["pubkey"]?.jsonPrimitive?.content ?: return
        val tags = obj["tags"]?.jsonArray ?: return
        val writeCount = tags.count { tag ->
            val arr = tag.jsonArray
            val type = arr.getOrNull(0)?.jsonPrimitive?.content
            val marker = arr.getOrNull(2)?.jsonPrimitive?.content
            type == "r" && (marker == null || marker.isBlank() || marker == "write")
        }
        Log.d(TAG, "Kind-10002 relay list for $pubkey: $writeCount write relays")
    }

    /**
     * Called by EventProcessor for kind 10006 (blocked) and 10007 (search) events.
     * MES handles state via insert() handlers; this just logs.
     */
    suspend fun handleRelayKindList(obj: kotlinx.serialization.json.JsonObject, kind: Int) {
        val pubkey    = obj["pubkey"]?.jsonPrimitive?.content ?: return
        val tags      = obj["tags"]?.jsonArray ?: return
        val createdAt = obj["created_at"]?.jsonPrimitive?.longOrNull ?: return

        if (pubkey != keyManager.getPublicKeyHex()) return

        val count = tags.count { tag ->
            tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "relay"
        }
        Log.d(TAG, "Received $count relay configs for kind $kind (created_at=$createdAt)")
    }

    /**
     * Called by EventProcessor for kind 10012 (favorite/browsable relays).
     * MES handles state via insert() handlers; this just logs.
     */
    suspend fun handleFavoriteRelays(obj: kotlinx.serialization.json.JsonObject) {
        val pubkey    = obj["pubkey"]?.jsonPrimitive?.content ?: return
        val tags      = obj["tags"]?.jsonArray ?: return
        val createdAt = obj["created_at"]?.jsonPrimitive?.longOrNull ?: return

        if (pubkey != keyManager.getPublicKeyHex()) return

        val count = tags.count { tag ->
            val tagName = tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content
            tagName == "relay" || tagName == "a"
        }
        Log.d(TAG, "Received $count favorite relay entries (created_at=$createdAt)")
    }

    /**
     * Called by EventProcessor for kind 30002 (NIP-51 relay set).
     * MES handles relay set state via insert() → handleRelaySetMaterialized().
     */
    suspend fun handleRelaySet(obj: kotlinx.serialization.json.JsonObject) {
        val pubkey    = obj["pubkey"]?.jsonPrimitive?.content ?: return
        val tags      = obj["tags"]?.jsonArray ?: return
        val createdAt = obj["created_at"]?.jsonPrimitive?.longOrNull ?: return

        if (pubkey != keyManager.getPublicKeyHex()) return

        val dTag = tags.firstOrNull { tag ->
            tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "d"
        }?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content ?: return

        val memberCount = tags
            .count { tag -> tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "relay" }

        Log.d(TAG, "Received relay set '$dTag' with $memberCount members (created_at=$createdAt)")
    }

    // ── Outbox routing: connect to top write relays ───────────────────────────

    private fun routeToWriteRelays(relayLists: Map<String, RelayList>) {
        // Build relay URL → [pubkeys that write there]
        val relayToAuthors = mutableMapOf<String, MutableList<String>>()
        for ((pubkey, relayList) in relayLists) {
            for (url in relayList.write) {
                relayToAuthors.getOrPut(url) { mutableListOf() }.add(pubkey)
            }
        }

        // Rank by coverage, cap at 15 concurrent connections (CLAUDE.md spec)
        val top = relayToAuthors.entries
            .sortedByDescending { it.value.size }
            .take(15)

        // Remove OUTBOX purpose from relays no longer in the top set
        val newOutboxUrls = top.mapNotNull { (url, _) -> normalizeRelayUrl(url) }.toSet()
        val removed = currentOutboxUrls - newOutboxUrls
        for (url in removed) {
            relayPool.removePurpose(url, ConnectionPurpose.OUTBOX)
        }
        currentOutboxUrls = newOutboxUrls

        Log.d(TAG, "Routing to ${top.size} write relays (${relayLists.size} relay lists, ${removed.size} removed)")
        for ((url, authors) in top) {
            normalizeRelayUrl(url)?.let { relayPool.addPurpose(it, ConnectionPurpose.OUTBOX) }
            relayPool.connectForAuthors(url, authors)
        }
    }
}
