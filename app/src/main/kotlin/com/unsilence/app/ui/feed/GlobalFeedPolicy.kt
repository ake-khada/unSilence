package com.unsilence.app.ui.feed

import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.domain.model.GlobalFeedLens
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.LinkedHashMap
import kotlin.math.abs

internal const val GLOBAL_BURST_WINDOW_SECONDS = 10L * 60L
internal const val GLOBAL_BURST_TRACKER_CAP = 2_000
internal const val MAX_JSON_ARTIFACT_LENGTH = 64 * 1024
internal const val LEGACY_POPULAR_RELAY_URL = "wss://antiprimal.net/hot"

internal enum class GlobalFeedVerdict {
    PASS,
    JSON_ARTIFACT,
    TAG_ONLY_PROTOCOL_ARTIFACT,
    BURST_DUPLICATE,
}

internal enum class TrustedGateVerdict {
    PASS,
    HOLD,
    DROP,
}

internal enum class FeedPolicyMode {
    NONE,
    HEURISTICS,
    TRUSTED,
}

internal data class GlobalFeedDropCounters(
    val jsonArtifacts: Int = 0,
    val tagOnlyProtocolArtifacts: Int = 0,
    val burstDuplicates: Int = 0,
    val unscoredAuthors: Int = 0,
    val pendingAuthors: Int = 0,
) {
    val total: Int
        get() = jsonArtifacts + tagOnlyProtocolArtifacts + burstDuplicates + unscoredAuthors + pendingAuthors
}

internal data class GlobalFeedProjection(
    val accepted: List<NostrEvent>,
    val pendingAuthorPubkeys: List<String>,
    val counters: GlobalFeedDropCounters,
)

internal fun isJsonArtifact(content: String): Boolean {
    if (content.isEmpty() || content.length > MAX_JSON_ARTIFACT_LENGTH) return false
    val trimmed = content.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.first() != '{' && trimmed.first() != '[') return false
    val parsed = runCatching { NostrJson.parseToJsonElement(trimmed) }.getOrNull()
    return parsed is JsonObject || parsed is JsonArray
}

internal fun contentFingerprint64(content: String): Long {
    var hash = -3750763034362895579L
    for (character in content) {
        hash = hash xor character.code.toLong()
        hash *= 1099511628211L
    }
    return hash
}

internal fun isTagOnlyProtocolArtifact(event: NostrEvent): Boolean {
    if (event.kind != 1 || event.content.isNotBlank()) return false
    var hasMiasmaPeerTag = false
    var hasMultiaddrTag = false
    for (tag in event.tags) {
        when {
            tag.getOrNull(0) == "t" && tag.getOrNull(1).equals("miasma-peer", ignoreCase = true) -> {
                hasMiasmaPeerTag = true
            }
            tag.getOrNull(0) == "multiaddr" && !tag.getOrNull(1).isNullOrBlank() -> {
                hasMultiaddrTag = true
            }
        }
    }
    return hasMiasmaPeerTag && hasMultiaddrTag
}

internal class BurstDuplicateTracker(
    private val windowSeconds: Long = GLOBAL_BURST_WINDOW_SECONDS,
    maxEntries: Int = GLOBAL_BURST_TRACKER_CAP,
) {
    private data class Key(val pubkey: String, val contentHash: Long)

    private val timestamps = object : LinkedHashMap<Key, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Long>?): Boolean =
            size > maxEntries
    }

    init {
        require(windowSeconds > 0L)
        require(maxEntries > 0)
    }

    fun isBurstDuplicate(pubkey: String, contentHash: Long, createdAtSeconds: Long): Boolean {
        val key = Key(pubkey, contentHash)
        val keptAt = timestamps[key]
        if (keptAt != null && abs(createdAtSeconds - keptAt) < windowSeconds) return true
        timestamps[key] = createdAtSeconds
        return false
    }

    internal val trackedEntryCount: Int
        get() = timestamps.size
}

internal fun trustedGateVerdict(lookup: WotLookup): TrustedGateVerdict = when (lookup) {
    is WotLookup.Scored -> TrustedGateVerdict.PASS
    WotLookup.Pending -> TrustedGateVerdict.HOLD
    WotLookup.Absent -> TrustedGateVerdict.DROP
}

internal fun feedPolicyMode(type: FeedType, lens: GlobalFeedLens): FeedPolicyMode = when {
    type is FeedType.Global && lens == GlobalFeedLens.TRUSTED -> FeedPolicyMode.TRUSTED
    type is FeedType.SingleRelay || type is FeedType.RelaySet -> FeedPolicyMode.HEURISTICS
    else -> FeedPolicyMode.NONE
}

/**
 * Deterministic snapshot policy. Each projection owns a fresh burst tracker so
 * flow recomposition cannot turn a previously accepted event into a duplicate.
 */
internal class GlobalFeedPolicy {
    @Volatile
    var latestCounters: GlobalFeedDropCounters = GlobalFeedDropCounters()
        private set

    fun project(
        candidates: List<NostrEvent>,
        applyHeuristics: Boolean,
        trustLookup: ((String) -> WotLookup)? = null,
    ): GlobalFeedProjection {
        if (!applyHeuristics && trustLookup == null) {
            return GlobalFeedProjection(
                accepted = candidates,
                pendingAuthorPubkeys = emptyList(),
                counters = GlobalFeedDropCounters(),
            ).also { latestCounters = it.counters }
        }

        val duplicateTracker = BurstDuplicateTracker()
        val accepted = ArrayList<NostrEvent>(candidates.size)
        val pendingAuthors = LinkedHashSet<String>()
        var jsonArtifacts = 0
        var tagOnlyProtocolArtifacts = 0
        var burstDuplicates = 0
        var unscoredAuthors = 0
        var pendingAuthorRows = 0

        for (event in candidates) {
            val heuristicVerdict = if (applyHeuristics) {
                heuristicVerdict(event, duplicateTracker)
            } else {
                GlobalFeedVerdict.PASS
            }
            when (heuristicVerdict) {
                GlobalFeedVerdict.JSON_ARTIFACT -> {
                    jsonArtifacts++
                    continue
                }
                GlobalFeedVerdict.TAG_ONLY_PROTOCOL_ARTIFACT -> {
                    tagOnlyProtocolArtifacts++
                    continue
                }
                GlobalFeedVerdict.BURST_DUPLICATE -> {
                    burstDuplicates++
                    continue
                }
                GlobalFeedVerdict.PASS -> Unit
            }

            when (trustLookup?.invoke(event.pubkey)?.let(::trustedGateVerdict)) {
                TrustedGateVerdict.DROP -> unscoredAuthors++
                TrustedGateVerdict.HOLD -> {
                    pendingAuthorRows++
                    pendingAuthors += event.pubkey
                }
                TrustedGateVerdict.PASS, null -> accepted += event
            }
        }

        val counters = GlobalFeedDropCounters(
            jsonArtifacts = jsonArtifacts,
            tagOnlyProtocolArtifacts = tagOnlyProtocolArtifacts,
            burstDuplicates = burstDuplicates,
            unscoredAuthors = unscoredAuthors,
            pendingAuthors = pendingAuthorRows,
        )
        latestCounters = counters
        return GlobalFeedProjection(
            accepted = accepted,
            pendingAuthorPubkeys = pendingAuthors.toList(),
            counters = counters,
        )
    }

    private fun heuristicVerdict(
        event: NostrEvent,
        duplicateTracker: BurstDuplicateTracker,
    ): GlobalFeedVerdict {
        if (isTagOnlyProtocolArtifact(event)) {
            return GlobalFeedVerdict.TAG_ONLY_PROTOCOL_ARTIFACT
        }
        // Kinds 6 and 16 may carry a JSON event envelope as protocol data.
        if (event.kind != 6 && event.kind != 16 && isJsonArtifact(event.content)) {
            return GlobalFeedVerdict.JSON_ARTIFACT
        }
        // Empty content is valid for media events and e-tag reposts; tags make
        // those events distinct, so content-only dedup must not collapse them.
        if (event.content.isNotBlank() && duplicateTracker.isBurstDuplicate(
                pubkey = event.pubkey,
                contentHash = contentFingerprint64(event.content),
                createdAtSeconds = event.createdAt,
            )
        ) {
            return GlobalFeedVerdict.BURST_DUPLICATE
        }
        return GlobalFeedVerdict.PASS
    }
}

internal fun isLegacyPopularRelay(url: String): Boolean =
    normalizeRelayUrl(url) == normalizeRelayUrl(LEGACY_POPULAR_RELAY_URL)

internal fun restoreFeedTypeOrGlobal(type: FeedType): FeedType =
    if (type is FeedType.SingleRelay && isLegacyPopularRelay(type.url)) FeedType.Global else type
