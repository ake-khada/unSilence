package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.db.dao.FollowDao
import com.unsilence.app.data.db.dao.NostrRelaySetDao
import com.unsilence.app.data.db.dao.RelayConfigDao
import com.unsilence.app.data.db.dao.RelayListDao
import com.unsilence.app.data.db.entity.FollowEntity
import com.unsilence.app.data.db.entity.NostrRelaySetEntity
import com.unsilence.app.data.db.entity.NostrRelaySetMemberEntity
import com.unsilence.app.data.db.entity.RelayConfigEntity
import com.unsilence.app.data.db.entity.RelayListEntity
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
 * 1. EventProcessor dispatches kind-3 and kind-10002 events to handler methods.
 * 2. When kind 3 arrives → saves followed pubkeys to Room (follows table).
 * 3. Requests kind 10002 (relay list metadata) for all followed pubkeys.
 * 4. When kind 10002 events arrive → saves write relay URLs to Room.
 * 5. Observes relay_list_metadata in Room → calls RelayPool.connectForAuthors()
 *    for the top 15 relays ranked by coverage (# of follows they serve).
 *
 * Architecture rule: all data flows Relay → Room. UI reads from Room via Flow.
 */
@Singleton
class OutboxRouter @Inject constructor(
    private val keyManager: KeyManager,
    private val followDao: FollowDao,
    private val relayListDao: RelayListDao,
    private val relayConfigDao: RelayConfigDao,
    private val nostrRelaySetDao: NostrRelaySetDao,
    private val relayPool: RelayPool,
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

        // Kind-3 and kind-10002 handlers are called directly by EventProcessor
        // via the immutable kindHandlers map — no registration needed.

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
            @OptIn(FlowPreview::class)
            relayListDao.allFlow()
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
        Log.d(TAG, "Stopped")
    }

    // ── Public handler methods (called by EventProcessor's immutable kindHandlers map) ──

    /**
     * Called by EventProcessor for every kind-3 event.
     * Filters to the logged-in user's own contact list only.
     */
    suspend fun handleContactList(obj: kotlinx.serialization.json.JsonObject) {
        val pubkey = obj["pubkey"]?.jsonPrimitive?.content ?: return
        val userPubkeyHex = keyManager.getPublicKeyHex() ?: return
        if (pubkey != userPubkeyHex) return
        handleKind3(obj, userPubkeyHex)
    }

    /**
     * Called by EventProcessor for every kind-10002 event.
     * Processes relay lists for any pubkey (used for outbox routing).
     */
    suspend fun handleRelayList(obj: kotlinx.serialization.json.JsonObject) {
        handleKind10002(obj)
    }

    /**
     * Called by EventProcessor for kind 10006 (blocked) and 10007 (search) events.
     * Parses ["relay", url] tags → RelayConfigEntity list.
     */
    suspend fun handleRelayKindList(obj: kotlinx.serialization.json.JsonObject, kind: Int) {
        val pubkey    = obj["pubkey"]?.jsonPrimitive?.content ?: return
        val tags      = obj["tags"]?.jsonArray ?: return
        val createdAt = obj["created_at"]?.jsonPrimitive?.longOrNull ?: return

        // Only process our own lists
        if (pubkey != keyManager.getPublicKeyHex()) return

        val entities = tags
            .filter { tag ->
                val arr = tag.jsonArray
                arr.getOrNull(0)?.jsonPrimitive?.content == "relay"
            }
            .mapNotNull { tag ->
                val url = tag.jsonArray.getOrNull(1)?.jsonPrimitive?.content
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RelayConfigEntity(kind = kind, relayUrl = url)
            }

        relayConfigDao.replaceForKind(kind, pubkey, createdAt, entities)
        Log.d(TAG, "Stored ${entities.size} relay configs for kind $kind (created_at=$createdAt)")
    }

    /**
     * Called by EventProcessor for kind 10012 (favorite/browsable relays).
     * Parses both ["relay", url] tags AND ["a", "30002:pubkey:d-tag"] references.
     */
    suspend fun handleFavoriteRelays(obj: kotlinx.serialization.json.JsonObject) {
        val pubkey    = obj["pubkey"]?.jsonPrimitive?.content ?: return
        val tags      = obj["tags"]?.jsonArray ?: return
        val createdAt = obj["created_at"]?.jsonPrimitive?.longOrNull ?: return

        if (pubkey != keyManager.getPublicKeyHex()) return

        val entities = tags.mapNotNull { tag ->
            val arr = tag.jsonArray
            val tagName = arr.getOrNull(0)?.jsonPrimitive?.content ?: return@mapNotNull null
            when (tagName) {
                "relay" -> {
                    val url = arr.getOrNull(1)?.jsonPrimitive?.content
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    RelayConfigEntity(kind = 10012, relayUrl = url)
                }
                "a" -> {
                    val ref = arr.getOrNull(1)?.jsonPrimitive?.content
                        ?.takeIf { it.startsWith("30002:") } ?: return@mapNotNull null
                    RelayConfigEntity(kind = 10012, relayUrl = "", setRef = ref)
                }
                else -> null
            }
        }

        relayConfigDao.replaceForKind(10012, pubkey, createdAt, entities)
        Log.d(TAG, "Stored ${entities.size} favorite relay entries (created_at=$createdAt)")
    }

    /**
     * Called by EventProcessor for kind 30002 (NIP-51 relay set).
     * Parses d-tag, title/description/image, and ["relay", url] members.
     */
    suspend fun handleRelaySet(obj: kotlinx.serialization.json.JsonObject) {
        val pubkey    = obj["pubkey"]?.jsonPrimitive?.content ?: return
        val tags      = obj["tags"]?.jsonArray ?: return
        val createdAt = obj["created_at"]?.jsonPrimitive?.longOrNull ?: return

        if (pubkey != keyManager.getPublicKeyHex()) return

        val dTag = tags.firstOrNull { tag ->
            tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "d"
        }?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content ?: return

        val title = tags.firstOrNull { tag ->
            tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "title"
        }?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content

        val description = tags.firstOrNull { tag ->
            tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "description"
        }?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content

        val image = tags.firstOrNull { tag ->
            tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "image"
        }?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content

        val members = tags
            .filter { tag -> tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "relay" }
            .mapNotNull { tag ->
                val url = tag.jsonArray.getOrNull(1)?.jsonPrimitive?.content
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                NostrRelaySetMemberEntity(setDTag = dTag, ownerPubkey = pubkey, relayUrl = url)
            }

        val setEntity = NostrRelaySetEntity(
            dTag = dTag,
            ownerPubkey = pubkey,
            title = title,
            description = description,
            image = image,
        )

        nostrRelaySetDao.replaceSet(setEntity, members, createdAt)
        Log.d(TAG, "Stored relay set '$dTag' with ${members.size} members (created_at=$createdAt)")
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
        //         ["r", "<url>", "read"]  → read relay (skip for outbox routing)
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

        // If this is our own relay list, populate relay_configs table (kind 10002).
        // Only accept if newer than what we already have (replaceable event semantics).
        if (pubkey == keyManager.getPublicKeyHex()) {
            val existing = relayConfigDao.maxCreatedAt(10002) ?: 0L
            if (createdAt <= existing) {
                Log.d(TAG, "Skipping older own kind-10002 (have=$existing, got=$createdAt)")
                return
            }

            val ownRelays = tags
                .filter { tag -> tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "r" }
                .mapNotNull { tag ->
                    val url = tag.jsonArray.getOrNull(1)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val marker = tag.jsonArray.getOrNull(2)?.jsonPrimitive?.content
                    RelayConfigEntity(
                        kind   = 10002,
                        relayUrl = url,
                        marker = when {
                            marker == null || marker.isBlank() -> null  // both read + write
                            marker == "read"  -> "read"
                            marker == "write" -> "write"
                            else -> null
                        },
                    )
                }
            if (ownRelays.isNotEmpty()) {
                relayConfigDao.replaceForKind(10002, pubkey, createdAt, ownRelays)
                Log.d(TAG, "Seeded ${ownRelays.size} own relays from kind 10002 (created_at=$createdAt)")
            }
        }
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
