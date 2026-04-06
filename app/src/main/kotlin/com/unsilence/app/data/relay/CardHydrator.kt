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
 * Stateless — give it a batch, it hydrates them.
 */
@Singleton
class CardHydrator @Inject constructor(
    private val eventDao: EventDao,
    private val relayPool: RelayPool,
    private val userRepository: UserRepository,
    private val thumbnailCache: VideoThumbnailCache,
) {
    // Reusable collections — cleared at start of each call. Safe because
    // FeedHydrationController serializes profileJob/refJob (one at a time).
    private val reusePubkeys = mutableSetOf<String>()
    private val reuseProfileHints = mutableMapOf<String, MutableList<String>>()
    private val reuseRefIds = mutableSetOf<String>()
    private val reuseRelayHints = mutableMapOf<String, String>()
    /**
     * Phase 1: Profile resolution only — avatar, name, identity.
     * Fires immediately with no delay. Called from WARM_CATCHUP and SLOW_SCROLL
     * BEFORE any ref/engagement work.
     */
    suspend fun hydrateProfiles(events: List<FeedRow>) {
        if (events.isEmpty()) return

        reusePubkeys.clear()
        reuseProfileHints.clear()

        for (event in events) {
            reusePubkeys.add(event.pubkey)
            if (event.kind == 6) {
                extractRepostAuthorPubkey(event.content, event.tags)?.let { reusePubkeys.add(it) }
            }
            extractProfileHints(event.content).forEach { (pk, relays) ->
                reusePubkeys.add(pk)
                reuseProfileHints.getOrPut(pk) { mutableListOf() }.addAll(relays)
            }
        }

        if (reusePubkeys.isNotEmpty()) {
            userRepository.fetchMissingProfiles(reusePubkeys.toList())

            val sourceRelays = events.map { it.relayUrl }.distinct()
            if (sourceRelays.isNotEmpty()) {
                relayPool.fetchProfilesFromSourceRelays(reusePubkeys.toList(), sourceRelays)
            }
        }
        if (reuseProfileHints.isNotEmpty()) {
            relayPool.fetchProfilesFromHints(reuseProfileHints.mapValues { it.value.distinct() })
        }

        Log.d(TAG, "Phase1 profiles: ${events.size} cards → ${reusePubkeys.size} pubkeys, ${events.map { it.relayUrl }.distinct().size} source relays")
    }

    /**
     * Phase 2: Referenced events + thumbnails. The slow path — ref fetches have a
     * 1500ms wait for relay responses. Also resolves ref-event author profiles.
     * Called from SLOW_SCROLL (after profiles) and IDLE.
     */
    suspend fun hydrateRefs(events: List<FeedRow>) {
        if (events.isEmpty()) return

        reuseRefIds.clear()
        reuseRelayHints.clear()
        for (event in events) {
            if (event.kind == 6) {
                extractRepostTargetId(event.tags)?.let { id ->
                    reuseRefIds.add(id)
                    extractRepostTargetRelay(event.tags)?.let { relay -> reuseRelayHints[id] = relay }
                }
            }
            extractQuotedEventIds(event.content).forEach { reuseRefIds.add(it) }
        }

        // Fetch missing referenced events
        val missingRefs = reuseRefIds.filter { eventDao.getEventById(it) == null }
        if (missingRefs.isNotEmpty()) {
            // Broadcast fetch for all missing refs
            relayPool.fetchEventsByIds(missingRefs.toList())
            // Also try relay hints for repost targets (bridge events may only exist there)
            for (id in missingRefs) {
                val hint = reuseRelayHints[id] ?: continue
                relayPool.fetchEventById(id, listOf(hint))
            }
        }

        // After ref events arrive, resolve their authors
        if (missingRefs.isNotEmpty()) {
            delay(1500)
            val refAuthors = missingRefs.mapNotNull { eventDao.getEventById(it)?.pubkey }
            if (refAuthors.isNotEmpty()) {
                userRepository.fetchMissingProfiles(refAuthors)
            }
        }

        // Prefetch video thumbnails (capped at 3 per batch)
        var thumbnailCount = 0
        var videoFound = 0
        for (event in events) {
            if (thumbnailCount >= 3) break
            if (event.kind == 30023) continue
            val models = buildVideoRenderModels(event)
            videoFound += models.size
            for (model in models) {
                if (thumbnailCount >= 3) break
                if (model.widthPx != null && model.heightPx != null) continue
                if (thumbnailCache.resolvedAspectRatios.containsKey(model.videoUrl)) continue
                try {
                    withContext(Dispatchers.IO) { thumbnailCache.getThumbnail(model.videoUrl) }
                    thumbnailCount++
                    Log.d(TAG, "Prefetched thumbnail: ${model.videoUrl.take(60)}")
                } catch (e: Exception) {
                    Log.w(TAG, "Thumbnail prefetch failed: ${e.message}")
                }
            }
        }

        Log.d(TAG, "Phase2 refs: ${events.size} cards → ${reuseRefIds.size} refs (${missingRefs.size} missing), $thumbnailCount thumbnails")
    }

    /**
     * Full hydration: profiles + refs + thumbnails. Used by IDLE state where
     * there's no urgency to split phases.
     */
    suspend fun hydrateVisibleCards(events: List<FeedRow>) {
        if (events.isEmpty()) return
        hydrateProfiles(events)
        hydrateRefs(events)
    }
}

/** Extract the relay hint (index 2) from the first "e" tag in a repost's tags. */
fun extractRepostTargetRelay(tagsJson: String): String? {
    return try {
        val parsed = NostrJson.parseToJsonElement(tagsJson).jsonArray
        val eTag = parsed.firstOrNull { it.jsonArray.getOrNull(0)?.jsonPrimitive?.content == "e" }
        eTag?.jsonArray?.getOrNull(2)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
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
