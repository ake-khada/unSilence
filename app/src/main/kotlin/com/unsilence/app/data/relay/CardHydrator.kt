package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.db.dao.EventDao
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.repository.UserRepository
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CardHydrator"

private val NOSTR_URI_REGEX = Regex("nostr:[a-z0-9]+", RegexOption.IGNORE_CASE)

/**
 * Unified card hydration: resolves ALL missing data for visible cards.
 *
 * Handles:
 *  - Author profiles (kind 0)
 *  - Repost original-author profiles (NIP-18 p-tag)
 *  - Referenced events for reposts (kind 6 e-tag) and quotes (nostr:nevent/note)
 *  - Referenced event author profiles
 *
 * Idempotent — already-hydrated IDs are skipped.
 */
@Singleton
class CardHydrator @Inject constructor(
    private val eventDao: EventDao,
    private val relayPool: RelayPool,
    private val userRepository: UserRepository,
) {
    private val hydratedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun hydrateVisibleCards(events: List<FeedRow>) {
        val newEvents = events.filter { it.id !in hydratedIds }
        if (newEvents.isEmpty()) return
        Log.d(TAG, "hydrateVisibleCards count=${events.size} new=${newEvents.size} ids=${newEvents.map { it.id.take(8) }}")
        hydratedIds.addAll(newEvents.map { it.id })

        // 1. Collect all pubkeys needing profiles (authors + repost original authors)
        val pubkeys = mutableSetOf<String>()
        val referencedIds = mutableSetOf<String>()

        for (event in newEvents) {
            pubkeys.add(event.pubkey)

            if (event.kind == 6) {
                Log.d(TAG, "Kind-6 repost ${event.id.take(12)}: tags.class=${event.tags::class.simpleName} tags=${event.tags.take(300)}")
                // Repost: extract original author pubkey from p-tag
                val repostPubkey = extractRepostAuthorPubkey(event.content, event.tags)
                Log.d(TAG, "  repostAuthorPubkey=${repostPubkey?.take(12)}")
                repostPubkey?.let { pubkeys.add(it) }
                // Repost: extract target event ID from e-tag
                val targetId = extractRepostTargetId(event.tags)
                Log.d(TAG, "  repostTargetId=${targetId?.take(12)}")
                targetId?.let { referencedIds.add(it) }
            }

            // Quote: nostr:nevent1... or nostr:note1... in content
            val quotedIds = extractQuotedEventIds(event.content)
            if (quotedIds.isNotEmpty()) {
                Log.d(TAG, "Quoted refs in ${event.id.take(12)}: ${quotedIds.map { it.take(12) }}")
            }
            quotedIds.forEach { referencedIds.add(it) }
        }

        // 2. Fetch missing profiles
        if (pubkeys.isNotEmpty()) {
            userRepository.fetchMissingProfiles(pubkeys.toList())
        }

        // 3. Fetch missing referenced events
        for (id in referencedIds) {
            val found = eventDao.getEventById(id) != null
            Log.d(TAG, "Ref ${id.take(12)} inRoom=$found")
        }
        val missingRefs = referencedIds.filter { eventDao.getEventById(it) == null }
        Log.d(TAG, "Referenced IDs: ${referencedIds.size} total, ${missingRefs.size} missing from Room")
        if (referencedIds.isNotEmpty()) {
            Log.d(TAG, "Ref IDs: ${referencedIds.map { it.take(12) }}")
            Log.d(TAG, "Missing: ${missingRefs.map { it.take(12) }}")
        }
        for (id in missingRefs) {
            Log.d(TAG, "Fetching missing ref: ${id.take(12)}")
            relayPool.fetchEventById(id)
        }

        // 4. After referenced events arrive, fetch their authors' profiles
        if (missingRefs.isNotEmpty()) {
            delay(1500)
            val refPubkeys = missingRefs.mapNotNull { eventDao.getEventById(it)?.pubkey }
            if (refPubkeys.isNotEmpty()) {
                userRepository.fetchMissingProfiles(refPubkeys)
            }
        }

        Log.d(TAG, "Hydrated ${newEvents.size} cards: ${pubkeys.size} profiles, ${referencedIds.size} refs (${missingRefs.size} missing)")
    }

    fun clearCache() { hydratedIds.clear() }
}

/** Extract the repost target event ID from the first "e" tag in a tags JSON string. */
fun extractRepostTargetId(tagsJson: String): String? {
    return try {
        val parsed = NostrJson.parseToJsonElement(tagsJson).jsonArray
        val eTag = parsed.firstOrNull { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "e" }
        val result = eTag?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content
        if (result == null) {
            Log.d("CardHydrator", "extractRepostTargetId: no e-tag found in ${parsed.size} tags, input=${tagsJson.take(200)}")
        }
        result
    } catch (e: Exception) {
        Log.w("CardHydrator", "extractRepostTargetId parse failed: ${e.message}, input=${tagsJson.take(200)}")
        null
    }
}

/** Extract quoted event IDs from nostr:nevent1.../nostr:note1... URIs in content. */
fun extractQuotedEventIds(content: String): List<String> {
    if (!content.contains("nostr:")) return emptyList()
    return NOSTR_URI_REGEX.findAll(content).mapNotNull { match ->
        runCatching {
            when (val entity = Nip19Parser.uriToRoute(match.value)?.entity) {
                is NEvent -> entity.hex
                is NNote -> entity.hex
                else -> null
            }
        }.getOrNull()
    }.toList()
}
