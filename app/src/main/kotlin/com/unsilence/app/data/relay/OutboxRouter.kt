package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.db.dao.FollowDao
import com.unsilence.app.data.db.dao.RelayListDao
import com.unsilence.app.data.db.entity.FollowEntity
import com.unsilence.app.data.db.entity.RelayListEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
 * 1. Registers handlers for kind 3 and kind 10002 with EventProcessor.
 * 2. Requests the logged-in user's kind 3 (follow list) from connected relays.
 * 3. When kind 3 arrives → saves followed pubkeys to Room (follows table).
 * 4. Requests kind 10002 (relay list metadata) for all followed pubkeys.
 * 5. When kind 10002 events arrive → saves write relay URLs to Room.
 * 6. Observes relay_list_metadata in Room → calls RelayPool.connectForAuthors()
 *    for the top 15 relays ranked by coverage (# of follows they serve).
 *
 * Architecture rule: all data flows Relay → Room. UI reads from Room via Flow.
 */
@Singleton
class OutboxRouter @Inject constructor(
    private val keyManager: KeyManager,
    private val followDao: FollowDao,
    private val relayListDao: RelayListDao,
    private val relayPool: RelayPool,
    private val eventProcessor: EventProcessor,
) {
    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private var routingJob: Job? = null

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

        // ── Register event handlers ──────────────────────────────────────────
        eventProcessor.addKindHandler(3) { obj, _ ->
            val pubkey = obj["pubkey"]?.jsonPrimitive?.content ?: return@addKindHandler
            // Only process the logged-in user's own follow list
            if (pubkey != userPubkeyHex) return@addKindHandler
            handleKind3(obj, userPubkeyHex)
        }

        eventProcessor.addKindHandler(10002) { obj, _ ->
            handleKind10002(obj)
        }

        // ── Step 1: request the user's kind 3 from connected relays ──────────
        relayPool.fetchFollowList(userPubkeyHex)

        // ── Step 2: when follows appear in Room, request their kind 10002 ────
        routingScope.launch {
            followDao.followsFlow()
                .filter { it.isNotEmpty() }
                .first()          // one-shot: take the first non-empty emission
                .let { follows ->
                    val pubkeys = follows.map { it.pubkey }
                    Log.d(TAG, "Follow list loaded: ${pubkeys.size} follows — fetching relay lists")
                    relayPool.fetchRelayLists(pubkeys)
                }
        }

        // ── Step 3: when relay lists arrive, route to write relays ───────────
        routingScope.launch {
            relayListDao.allFlow()
                .filter { it.isNotEmpty() }
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
        Log.d(TAG, "Stopped")
    }

    // ── Kind 3: NIP-02 follow list ───────────────────────────────────────────

    private suspend fun handleKind3(obj: kotlinx.serialization.json.JsonObject, userPubkeyHex: String) {
        val tags      = obj["tags"]?.jsonArray ?: return
        val createdAt = obj["created_at"]?.jsonPrimitive?.longOrNull ?: return

        val follows = tags
            .filter { tag -> tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "p" }
            .mapNotNull { tag -> tag.jsonArray.getOrNull(1)?.jsonPrimitive?.content }
            .map { followedPubkey -> FollowEntity(pubkey = followedPubkey, followedAt = createdAt) }

        followDao.replaceAll(follows)
        Log.d(TAG, "Stored ${follows.size} follows from kind 3 for $userPubkeyHex")
    }

    // ── Kind 10002: NIP-65 relay list metadata ────────────────────────────────

    private suspend fun handleKind10002(obj: kotlinx.serialization.json.JsonObject) {
        val pubkey    = obj["pubkey"]?.jsonPrimitive?.content ?: return
        val tags      = obj["tags"]?.jsonArray ?: return
        val createdAt = obj["created_at"]?.jsonPrimitive?.longOrNull ?: return

        // r tags: ["r", "<url>", "write"] → write relay
        //         ["r", "<url>", "read"]  → read relay (skip)
        //         ["r", "<url>"]          → both read+write (include)
        val writeRelays = tags
            .filter { tag ->
                val arr   = tag.jsonArray
                val type  = arr.getOrNull(0)?.jsonPrimitive?.content
                val marker = arr.getOrNull(2)?.jsonPrimitive?.content
                type == "r" && (marker == null || marker.isBlank() || marker == "write")
            }
            .mapNotNull { tag -> tag.jsonArray.getOrNull(1)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } }

        if (writeRelays.isEmpty()) return

        relayListDao.upsert(
            RelayListEntity(
                pubkey      = pubkey,
                writeRelays = Json.encodeToString(writeRelays),
                updatedAt   = createdAt,
            )
        )
    }

    // ── Outbox routing: connect to top write relays ───────────────────────────

    private fun routeToWriteRelays(relayLists: List<RelayListEntity>) {
        // Build relay URL → [pubkeys that write there]
        val relayToAuthors = mutableMapOf<String, MutableList<String>>()
        for (entity in relayLists) {
            val urls = runCatching {
                Json.decodeFromString<List<String>>(entity.writeRelays)
            }.getOrDefault(emptyList())
            for (url in urls) {
                relayToAuthors.getOrPut(url) { mutableListOf() }.add(entity.pubkey)
            }
        }

        // Rank by coverage, cap at 15 concurrent connections (CLAUDE.md spec)
        val top = relayToAuthors.entries
            .sortedByDescending { it.value.size }
            .take(15)

        Log.d(TAG, "Routing to ${top.size} write relays (${relayLists.size} relay lists)")
        for ((url, authors) in top) {
            relayPool.connectForAuthors(url, authors)
        }
    }
}
