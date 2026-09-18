package com.unsilence.app.ui.feed

import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.domain.model.FeedFilter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Save references, never event bodies or media. The visible feed is capped at 500 rows.
internal const val SAVED_FEED_EVENT_LIMIT = 500
private const val MAX_SAVED_FEED_CHARS = 96 * 1024
private val FeedStateJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class SavedFeedSource(
    val kind: String,
    val id: String = "",
    val label: String = "",
) {
    fun restore(): FeedType? = when (kind) {
        "following" -> FeedType.Following
        "global" -> FeedType.Global
        "relay-set" -> FeedType.RelaySet(id, label)
        "relay" -> normalizeRelayUrl(id)?.let { FeedType.SingleRelay(it, label) }
        else -> null
    }?.let(::restoreFeedTypeOrGlobal)
}

internal fun FeedType.savedSource(): SavedFeedSource = when (this) {
    FeedType.Following -> SavedFeedSource("following")
    FeedType.Global -> SavedFeedSource("global")
    is FeedType.RelaySet -> SavedFeedSource("relay-set", dTag, name)
    is FeedType.SingleRelay -> SavedFeedSource("relay", url, label)
}

@Serializable
internal data class SavedFeedSession(
    val owner: String,
    val source: SavedFeedSource,
    val filter: FeedFilter = FeedFilter(),
    val contentFilter: Int = FeedContentFilter.NOTES_ONLY.value,
    val automaticTrustedGlobal: Boolean = false,
    val isAtTop: Boolean = true,
    val eventIds: List<String> = emptyList(),
)

internal fun encodeFeedSession(state: SavedFeedSession): String? =
    FeedStateJson.encodeToString(state.copy(eventIds = state.eventIds.take(SAVED_FEED_EVENT_LIMIT)))
        .takeIf { it.length <= MAX_SAVED_FEED_CHARS }

internal fun decodeFeedSession(encoded: String?, owner: String): SavedFeedSession? {
    if (encoded.isNullOrEmpty() || encoded.length > MAX_SAVED_FEED_CHARS) return null
    return runCatching { FeedStateJson.decodeFromString<SavedFeedSession>(encoded) }.getOrNull()
        ?.takeIf { it.owner == owner && it.source.restore() != null }
        ?.let { it.copy(eventIds = it.eventIds.take(SAVED_FEED_EVENT_LIMIT)) }
}
