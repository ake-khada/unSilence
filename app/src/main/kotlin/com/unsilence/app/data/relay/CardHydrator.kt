package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.db.dao.EventDao
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.model.buildVideoRenderModels
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.ui.feed.VideoThumbnailCache
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    private val thumbnailCache: VideoThumbnailCache,
) {
    private val hydratedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun hydrateVisibleCards(events: List<FeedRow>) {
        val newEvents = events.filter { it.id !in hydratedIds }
        if (newEvents.isEmpty()) return
        hydratedIds.addAll(newEvents.map { it.id })

        // 1. Collect all pubkeys, referenced event IDs, and nprofile relay hints
        val pubkeys = mutableSetOf<String>()
        val referencedIds = mutableSetOf<String>()
        val profileHints = mutableMapOf<String, MutableList<String>>()

        for (event in newEvents) {
            pubkeys.add(event.pubkey)
            if (event.kind == 6) {
                extractRepostAuthorPubkey(event.content, event.tags)?.let { pubkeys.add(it) }
                extractRepostTargetId(event.tags)?.let { referencedIds.add(it) }
            }
            extractQuotedEventIds(event.content).forEach { referencedIds.add(it) }
            extractProfileHints(event.content).forEach { (pk, relays) ->
                pubkeys.add(pk)
                profileHints.getOrPut(pk) { mutableListOf() }.addAll(relays)
            }
        }

        // 2. Fetch missing referenced events (batched, deduped in RelayPool)
        val missingRefs = referencedIds.filter { eventDao.getEventById(it) == null }
        if (missingRefs.isNotEmpty()) {
            relayPool.fetchEventsByIds(missingRefs.toList())
        }

        // 3. After ref events arrive, collect their authors too
        if (missingRefs.isNotEmpty()) {
            delay(1500)
            missingRefs.mapNotNull { eventDao.getEventById(it)?.pubkey }
                .forEach { pubkeys.add(it) }
        }

        // 4. Single profile fetch for ALL pubkeys (authors + repost authors + ref authors)
        //    ProfileResolver handles batching (200ms window) and in-flight dedup internally.
        if (pubkeys.isNotEmpty()) {
            userRepository.fetchMissingProfiles(pubkeys.toList())
        }

        // 4b. Targeted fetch for pubkeys with nprofile relay hints
        if (profileHints.isNotEmpty()) {
            relayPool.fetchProfilesFromHints(profileHints.mapValues { it.value.distinct() })
        }

        // 5. Prefetch video thumbnails for aspect ratio cache (capped at 3 per batch).
        //    By the time a video card enters the viewport, resolvedAspectRatios
        //    already has the correct ratio — zero sizing pop on first compose.
        var thumbnailCount = 0
        for (event in newEvents) {
            if (thumbnailCount >= 3) break
            if (event.kind == 30023) continue
            val models = buildVideoRenderModels(event)
            for (model in models) {
                if (thumbnailCount >= 3) break
                if (model.widthPx != null && model.heightPx != null) continue  // imeta has dimensions
                if (thumbnailCache.resolvedAspectRatios.containsKey(model.videoUrl)) continue
                withContext(Dispatchers.IO) { thumbnailCache.getThumbnail(model.videoUrl) }
                thumbnailCount++
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

/** Extract pubkey → relay hints from nostr:nprofile1... URIs in content. */
fun extractProfileHints(content: String): Map<String, List<String>> {
    if (!content.contains("nostr:")) return emptyMap()
    val hints = mutableMapOf<String, List<String>>()
    NOSTR_URI_REGEX.findAll(content).forEach { match ->
        runCatching {
            val entity = Nip19Parser.uriToRoute(match.value)?.entity
            if (entity is NProfile && entity.relay.isNotEmpty()) {
                hints[entity.hex] = entity.relay.map { it.url }
            }
        }
    }
    return hints
}
