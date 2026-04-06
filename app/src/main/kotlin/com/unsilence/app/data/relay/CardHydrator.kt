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
    /**
     * Phase 1: Profile resolution only — avatar, name, identity.
     * Fires immediately with no delay. Called from WARM_CATCHUP and SLOW_SCROLL
     * BEFORE any ref/engagement work.
     *
     * @param fanOut When false, only fetches from indexer relays (fastest path).
     *   Source relay and hint relay fetches are skipped — use [fanOutProfiles]
     *   later in IDLE to catch up.
     */
    suspend fun hydrateProfiles(events: List<FeedRow>, fanOut: Boolean = true) {
        if (events.isEmpty()) return

        val pubkeys = mutableSetOf<String>()
        val profileHints = mutableMapOf<String, MutableList<String>>()

        for (event in events) {
            pubkeys.add(event.pubkey)
            if (event.kind == 6) {
                extractRepostAuthorPubkey(event.content, event.tags)?.let { pubkeys.add(it) }
            }
            if (fanOut) {
                extractProfileHints(event.content).forEach { (pk, relays) ->
                    pubkeys.add(pk)
                    profileHints.getOrPut(pk) { mutableListOf() }.addAll(relays)
                }
            }
        }

        if (pubkeys.isNotEmpty()) {
            userRepository.fetchMissingProfiles(pubkeys.toList())

            if (fanOut) {
                val sourceRelays = events.map { it.relayUrl }.distinct()
                if (sourceRelays.isNotEmpty()) {
                    relayPool.fetchProfilesFromSourceRelays(pubkeys.toList(), sourceRelays)
                }
            }
        }
        if (fanOut && profileHints.isNotEmpty()) {
            relayPool.fetchProfilesFromHints(profileHints.mapValues { it.value.distinct() })
        }

        Log.d(TAG, "Phase1 profiles: ${events.size} cards → ${pubkeys.size} pubkeys${if (!fanOut) " (indexer-only)" else ", ${events.map { it.relayUrl }.distinct().size} source relays"}")
    }

    /**
     * Deferred fan-out: source relay + hint relay profile fetches for items
     * that were previously hydrated with fanOut=false during scroll.
     * Called from IDLE to catch up on profiles that only exist on non-indexer relays.
     */
    suspend fun fanOutProfiles(events: List<FeedRow>) {
        if (events.isEmpty()) return

        val pubkeys = mutableSetOf<String>()
        val profileHints = mutableMapOf<String, MutableList<String>>()

        for (event in events) {
            pubkeys.add(event.pubkey)
            if (event.kind == 6) {
                extractRepostAuthorPubkey(event.content, event.tags)?.let { pubkeys.add(it) }
            }
            extractProfileHints(event.content).forEach { (pk, relays) ->
                pubkeys.add(pk)
                profileHints.getOrPut(pk) { mutableListOf() }.addAll(relays)
            }
        }

        if (pubkeys.isNotEmpty()) {
            val sourceRelays = events.map { it.relayUrl }.distinct()
            if (sourceRelays.isNotEmpty()) {
                relayPool.fetchProfilesFromSourceRelays(pubkeys.toList(), sourceRelays)
            }
        }
        if (profileHints.isNotEmpty()) {
            relayPool.fetchProfilesFromHints(profileHints.mapValues { it.value.distinct() })
        }

        Log.d(TAG, "Fan-out profiles: ${events.size} cards → ${pubkeys.size} pubkeys, ${events.map { it.relayUrl }.distinct().size} source relays")
    }

    /**
     * Phase 2: Referenced events + thumbnails. The slow path — ref fetches have a
     * 1500ms wait for relay responses. Also resolves ref-event author profiles.
     * Called from SLOW_SCROLL (after profiles) and IDLE.
     */
    suspend fun hydrateRefs(events: List<FeedRow>) {
        if (events.isEmpty()) return

        val referencedIds = mutableSetOf<String>()
        val relayHints = mutableMapOf<String, String>()
        for (event in events) {
            if (event.kind == 6) {
                extractRepostTargetId(event.tags)?.let { id ->
                    referencedIds.add(id)
                    extractRepostTargetRelay(event.tags)?.let { relay -> relayHints[id] = relay }
                }
            }
            extractQuotedEventIds(event.content).forEach { referencedIds.add(it) }
        }

        // Fetch missing referenced events
        val missingRefs = referencedIds.filter { eventDao.getEventById(it) == null }
        if (missingRefs.isNotEmpty()) {
            // Broadcast fetch for all missing refs
            relayPool.fetchEventsByIds(missingRefs.toList())
            // Also try relay hints for repost targets (bridge events may only exist there)
            for (id in missingRefs) {
                val hint = relayHints[id] ?: continue
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

        Log.d(TAG, "Phase2 refs: ${events.size} cards → ${referencedIds.size} refs (${missingRefs.size} missing), $thumbnailCount thumbnails")
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
