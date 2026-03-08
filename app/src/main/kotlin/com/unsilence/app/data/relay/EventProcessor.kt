package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.db.dao.EventDao
import com.unsilence.app.data.db.dao.ReactionDao
import com.unsilence.app.data.db.dao.UserDao
import com.unsilence.app.data.db.entity.EventEntity
import com.unsilence.app.data.db.entity.ReactionEntity
import com.unsilence.app.data.db.entity.UserEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EventProcessor"

/**
 * Parses raw relay wire messages and writes valid events to Room.
 *
 * Handles: kind 0 (profiles), 1 (notes), 6 (reposts), 7 (reactions), 20, 21.
 * Validates: NIP-40 expiration.
 * Extracts: NIP-10 threading, NIP-36 content-warning.
 */
@Singleton
class EventProcessor @Inject constructor(
    private val eventDao: EventDao,
    private val userDao: UserDao,
    private val reactionDao: ReactionDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nowSeconds: Long get() = System.currentTimeMillis() / 1000L

    fun process(raw: String, relayUrl: String) {
        scope.launch {
            try {
                val msg = NostrJson.parseToJsonElement(raw).jsonArray
                val type = msg[0].jsonPrimitive.content
                if (type == "EVENT" && msg.size >= 3) {
                    handleEvent(msg[2].jsonObject, relayUrl)
                }
                // EOSE and NOTICE are logged but need no action in Sprint 1
            } catch (e: Exception) {
                // Malformed relay message — skip silently
            }
        }
    }

    private suspend fun handleEvent(obj: JsonObject, relayUrl: String) {
        val id        = obj["id"]?.jsonPrimitive?.content         ?: return
        val pubkey    = obj["pubkey"]?.jsonPrimitive?.content      ?: return
        val kind      = obj["kind"]?.jsonPrimitive?.intOrNull      ?: return
        val content   = obj["content"]?.jsonPrimitive?.content     ?: return
        val createdAt = obj["created_at"]?.jsonPrimitive?.longOrNull ?: return
        val sig       = obj["sig"]?.jsonPrimitive?.content         ?: return
        val tagsJson  = obj["tags"]?.toString()                    ?: "[]"
        val tags      = obj["tags"]?.jsonArray ?: JsonArray(emptyList())

        // NIP-40: skip events that have already expired
        val expiration = tags.firstOrNull { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "expiration" }
            ?.jsonArray?.getOrNull(1)?.jsonPrimitive?.longOrNull
        if (expiration != null && expiration < nowSeconds) return

        when (kind) {
            0    -> processMetadata(pubkey, content)
            7    -> processReaction(id, pubkey, createdAt, content, tags)
            1, 6, 20, 21 -> processContent(
                id, pubkey, kind, content, createdAt, tagsJson, sig, tags, relayUrl
            )
        }
    }

    // ── Kind 0 ───────────────────────────────────────────────────────────────

    private suspend fun processMetadata(pubkey: String, content: String) {
        try {
            val meta = NostrJson.parseToJsonElement(content).jsonObject
            fun str(key: String) = meta[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

            userDao.upsert(
                UserEntity(
                    pubkey      = pubkey,
                    name        = str("name"),
                    displayName = str("display_name"),
                    about       = str("about"),
                    picture     = str("picture"),
                    nip05       = str("nip05"),
                    lud16       = str("lud16"),
                    banner      = str("banner"),
                    updatedAt   = nowSeconds,
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Bad metadata for $pubkey: ${e.message}")
        }
    }

    // ── Kind 7 ───────────────────────────────────────────────────────────────

    private suspend fun processReaction(
        id: String,
        pubkey: String,
        createdAt: Long,
        content: String,
        tags: JsonArray,
    ) {
        val targetId = tags.lastOrNull { tag ->
            tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "e"
        }?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content ?: return

        reactionDao.insertOrIgnore(
            ReactionEntity(
                eventId       = id,
                targetEventId = targetId,
                pubkey        = pubkey,
                content       = content.ifBlank { "+" },
                createdAt     = createdAt,
            )
        )
    }

    // ── Kinds 1, 6, 20, 21 ───────────────────────────────────────────────────

    private suspend fun processContent(
        id: String,
        pubkey: String,
        kind: Int,
        content: String,
        createdAt: Long,
        tagsJson: String,
        sig: String,
        tags: JsonArray,
        relayUrl: String,
    ) {
        val (replyToId, rootId) = parseNip10Threading(tags)
        val (hasCw, cwReason)   = parseContentWarning(tags)

        eventDao.insertOrIgnore(
            EventEntity(
                id                   = id,
                pubkey               = pubkey,
                kind                 = kind,
                content              = content,
                createdAt            = createdAt,
                tags                 = tagsJson,
                sig                  = sig,
                relayUrl             = relayUrl,
                replyToId            = replyToId,
                rootId               = rootId,
                hasContentWarning    = hasCw,
                contentWarningReason = cwReason,
                cachedAt             = nowSeconds,
            )
        )
    }

    // ── NIP-10: threading ─────────────────────────────────────────────────────

    /**
     * Returns (replyToId, rootId) parsed from `e` tags.
     *
     * Priority: explicit "root"/"reply" markers. Fallback: positional
     * (first e = root, last e = reply-to). If only one e tag, it is the root.
     */
    private fun parseNip10Threading(tags: JsonArray): Pair<String?, String?> {
        val eTags = tags.filter { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "e" }
            .map { it.jsonArray }

        if (eTags.isEmpty()) return Pair(null, null)

        // Marker-based (NIP-10 recommended)
        val rootId    = eTags.firstOrNull { it.getOrNull(3)?.jsonPrimitive?.content == "root" }
            ?.getOrNull(1)?.jsonPrimitive?.content
        val replyToId = eTags.firstOrNull { it.getOrNull(3)?.jsonPrimitive?.content == "reply" }
            ?.getOrNull(1)?.jsonPrimitive?.content

        if (rootId != null || replyToId != null) return Pair(replyToId, rootId)

        // Positional fallback
        val ids = eTags.mapNotNull { it.getOrNull(1)?.jsonPrimitive?.content }
        return when (ids.size) {
            0    -> Pair(null, null)
            1    -> Pair(null, ids[0])   // single e = root
            else -> Pair(ids.last(), ids.first())
        }
    }

    // ── NIP-36: content-warning ───────────────────────────────────────────────

    private fun parseContentWarning(tags: JsonArray): Pair<Boolean, String?> {
        val cwTag = tags.firstOrNull { tag ->
            tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "content-warning"
        }?.jsonArray ?: return Pair(false, null)

        val reason = cwTag.getOrNull(1)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        return Pair(true, reason)
    }
}
