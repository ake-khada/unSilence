package com.unsilence.app.data.memory

/**
 * In-memory Nostr event representation. Pre-parsed NIP-10 threading
 * and NIP-36 content warnings. Tags stored as parsed lists, not JSON.
 *
 * NOT a UI-facing type — never passed to @Composable functions.
 * The MutableSet<String> on relaysSeen is intentional: relay provenance
 * accumulates across duplicate arrivals via insert()'s dedup path.
 * Must be a ConcurrentHashMap.newKeySet() — iterated on Default dispatcher
 * (feedFlow scans) while mutated on IO (addRelaySeen, insert dedup).
 */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val kind: Int,
    val content: String,
    val createdAt: Long,
    val tags: List<List<String>>,
    val tagsJson: String,
    val sig: String,
    val relayUrl: String,
    val replyToId: String?,
    val rootId: String?,
    val hasContentWarning: Boolean,
    val contentWarningReason: String?,
    /** Wall-clock when this event was first inserted, in epoch milliseconds. */
    val firstSeenAt: Long,
    val relaysSeen: MutableSet<String>,
)

data class ZapAggregate(val count: Int, val totalSats: Long) {
    companion object {
        val EMPTY = ZapAggregate(0, 0L)
    }
}

/**
 * Per-zap breakdown for the engagement drawer.
 * senderPubkey is null when the description tag is missing, malformed,
 * or the zap was sent anonymously (one-time keypair, no resolvable actor).
 * Anonymous entries are grouped into a single chip by the drawer.
 */
data class ZapDetail(
    val senderPubkey: String?,
    val sats: Long,
    val comment: String?,
    val eventId: String? = null,  // kind-9735 receipt id; null on V10 entries
)

/** Reaction content — Unicode emoji (or "+"/"-") vs NIP-30 custom emoji with image URL. */
sealed interface ReactionContent {
    data class Standard(val emoji: String) : ReactionContent
    data class Custom(val shortcode: String, val url: String) : ReactionContent
}

/** Reaction record stored in reactionsByTarget. */
data class ReactionInfo(val pubkey: String, val content: ReactionContent)

/**
 * Per-event engagement counts. Snapshot of MES aggregate state for a single
 * event, surfaced via [com.unsilence.app.data.memory.MemoryEventStore.statsFlow]
 * so individual cards can observe their own counts without going through a
 * list-wide signal trigger. Equality enables [kotlinx.coroutines.flow.distinctUntilChanged]
 * to suppress emissions when counts didn't change for THIS event.
 */
@androidx.compose.runtime.Immutable
data class EventStats(
    val replyCount: Int,
    val repostCount: Int,
    val reactionCount: Int,
    val zapCount: Int,
    val zapTotalSats: Long,
) {
    companion object {
        val EMPTY = EventStats(0, 0, 0, 0, 0L)
    }
}

data class RelayList(val read: List<String>, val write: List<String>)

/** NIP-36 content-warning display mode. Persisted in DataStore. */
enum class SensitiveContentMode {
    /** Hide sensitive posts entirely (filtered out of feed). */
    HIDE,
    /** Show blurred preview with tap-to-reveal. Default. */
    BLUR,
    /** Show sensitive posts without any overlay. */
    SHOW,
}

/**
 * CONTRACT: every Set here MUST be insertion-ordered (LinkedHashSet). Mute entries are
 * appended chronologically (`mutableSetOf` in tag order; `existing.privatePubkeys + new`),
 * so iteration order == chronology, and FiltersScreen displays `reversed()` for newest-first.
 * Insertion order IS the feature — do NOT swap any builder to HashSet/CHM keyset, and do NOT
 * sort by name/alpha. We have no per-entry timestamps; tag order is the only chronology we have.
 */
data class MuteList(
    val pubkeys: Set<String>,
    val hashtags: Set<String>,
    val words: Set<String>,
    val eventIds: Set<String>,
    val privatePubkeys: Set<String> = emptySet(),
    val privateHashtags: Set<String> = emptySet(),
    val privateWords: Set<String> = emptySet(),
    val privateEventIds: Set<String> = emptySet(),
) {
    val totalCount: Int get() =
        pubkeys.size + hashtags.size + words.size + eventIds.size +
        privatePubkeys.size + privateHashtags.size + privateWords.size + privateEventIds.size
}

/** Kind-10002 relay config with marker info (read/write/both). */
data class RelayConfig(val url: String, val marker: String?)

/** Kind-10012 favorite entry — either a relay URL or a set reference (["a", "30002:pubkey:dtag"]). */
data class FavoriteEntry(val url: String?, val setRef: String?)

/** Kind-30002 relay set metadata. One per (ownerPubkey, dTag). */
data class RelaySet(
    val dTag: String,
    val ownerPubkey: String,
    val title: String? = null,
    val description: String? = null,
    val image: String? = null,
    val members: List<String> = emptyList(),
)

/**
 * A single notification item — in-memory notification representation.
 *
 * Built at scan time from eventsById + profile lookups.
 * Carries enough resolved data for the UI to render without additional lookups.
 */
sealed interface NotificationRow {
    /** Stable key for LazyColumn. */
    val key: String
    /** Newest contributing event — drives DESC ordering across groups + singles. */
    val mostRecentAt: Long

    /**
     * Reply or mention — one event, one actor (the old NotificationItem shape).
     */
    data class Single(
        val id: String,
        val notifType: String,   // "reply" | "mention"
        val actorPubkey: String,
        val actorName: String?,
        val actorDisplayName: String?,
        val actorPicture: String?,
        val targetNoteId: String?,
        val targetNoteContent: String,
        val parentNoteContent: String,
        val createdAt: Long,
    ) : NotificationRow {
        override val key get() = id
        override val mostRecentAt get() = createdAt
    }

    /**
     * Reactions / reposts (kind 6 & 16) / zaps folded by (targetNoteId, notifType).
     * Anonymous zaps are collapsed into [anonymousCount]/[anonymousSats] — never
     * shown as distinct LNURL identicons.
     */
    data class Grouped(
        val notifType: String,   // "reaction" | "repost" | "zap"
        val targetNoteId: String?,
        val targetNoteContent: String,
        val actors: List<NotificationActor>,   // named, deduped by pubkey, recency-sorted
        val people: Int,                        // distinct named actors + anonymous zaps
        val sumSats: Long,                      // zaps
        val dominantReaction: ReactionContent?, // reactions
        val anonymousCount: Int,
        val anonymousSats: Long,
        override val mostRecentAt: Long,
    ) : NotificationRow {
        override val key get() = "$notifType|$targetNoteId"
    }
}

/** One actor inside a [NotificationRow.Grouped]. [pubkey] null ⇒ anonymous zap. */
data class NotificationActor(
    val pubkey: String?,
    val name: String?,
    val displayName: String?,
    val picture: String?,
    val sats: Long = 0,
    val reaction: ReactionContent? = null,
    val createdAt: Long,
)

/**
 * Entry in the createdAt-sorted index. Implements comparison for
 * ConcurrentSkipListSet ordering: descending by createdAt, ascending by id
 * for deterministic tie-breaking.
 */
data class EventEntry(val id: String, val createdAt: Long)

/**
 * Feed query filter. Determines which events appear in a feed flow.
 *
 * Structural filters only — these are applied inside the walk so that
 * [limit] counts accepted rows, not scanned rows. Presentation filters
 * (media type, sinceHours, engagement minimums) stay in FeedViewModel.
 *
 * @param contentFilter 0 = all, 1 = notes only (no replies), 2 = replies only
 * @param relayUrls When non-null, only events seen on at least one of these relays pass.
 */
data class FeedFilter(
    val kinds: Set<Int> = setOf(1, 6, 30023),
    val followedPubkeys: Set<String>? = null,
    val contentFilter: Int = 0,
    val relayUrls: Set<String>? = null,
)

// ── Core data types ────────────────────────────

/**
 * Plain data class used throughout the app as the canonical event representation.
 */
@androidx.compose.runtime.Immutable
data class EventEntity(
    val id: String,
    val pubkey: String,
    val kind: Int,
    val content: String,
    val createdAt: Long,
    /** JSON-serialized List<List<String>> */
    val tags: String,
    val sig: String = "",
    val relayUrl: String = "",
    val replyToId: String? = null,
    val rootId: String? = null,
    val hasContentWarning: Boolean = false,
    val contentWarningReason: String? = null,
    val zapTotalSats: Long = 0,
    val firstSeenAt: Long = 0,
)

/**
 * Plain data class for user profile data.
 */
@androidx.compose.runtime.Immutable
data class UserEntity(
    val pubkey: String,
    val name: String? = null,
    val displayName: String? = null,
    val about: String? = null,
    val picture: String? = null,
    val nip05: String? = null,
    val lud16: String? = null,
    val banner: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val followerCount: Long? = null,
    val followerCountUpdatedAt: Long? = null,
)

/**
 * Flattened feed row — event + author + engagement counts.
 * Formerly defined in EventDao with @ColumnInfo; now a plain data class.
 */
@androidx.compose.runtime.Immutable
data class FeedRow(
    val id: String,
    val pubkey: String,
    val kind: Int,
    val content: String,
    val createdAt: Long,
    val tags: String,
    val relayUrl: String,
    val replyToId: String?,
    val rootId: String?,
    val hasContentWarning: Boolean,
    val contentWarningReason: String?,
    val zapTotalSats: Long,
    val authorName: String?,
    val authorDisplayName: String?,
    val authorPicture: String?,
    val authorNip05: String?,
    val reactionCount: Int,
    val replyCount: Int,
    val repostCount: Int,
    val zapCount: Int,
)

/**
 * Relay trust score (kind 30385). Plain data class.
 * Populated by MES handleTrustScore() from kind-30385 events fetched via
 * RelayPool.fetchTrustScores(). UI renders colored dots in Relay Settings.
 */
data class RelayTrustScoreEntity(
    val relayUrl: String,
    val score: Int,
    val reliability: Int,
    val quality: Int,
    val accessibility: Int,
    val confidence: String,
    val observations: Int,
    val policy: String? = null,
    val countryCode: String? = null,
    val operatorVerified: String? = null,
    val updatedAt: Long = 0L,
)

/**
 * Relay liveness monitor (kind 30166 / NIP-66). Populated by MES
 * handleRelayMonitor() from events fetched via RelayPool.fetchRelayMonitors().
 * Source: wss://relay.nostr.watch — operational health, RTT, NIP support.
 */
data class RelayMonitorEntity(
    val relayUrl: String,
    val rttOpen: Int? = null,
    val rttRead: Int? = null,
    val rttWrite: Int? = null,
    val supportedNips: List<Int> = emptyList(),
    val network: String? = null,
    val requirements: List<String> = emptyList(),
    val geohash: String? = null,
    val iconUrl: String? = null,
    val monitorPubkey: String,
    val createdAt: Long,
)

/**
 * Combined relay health: trust quality (kind 30385) + operational liveness (kind 30166).
 * Keyed by normalized relay URL. Either or both sources may be present.
 */
data class RelayHealthInfo(
    val relayUrl: String,
    val trustScore: RelayTrustScoreEntity? = null,
    val monitor: RelayMonitorEntity? = null,
) {
    val score: Int? get() = trustScore?.score
    val ping: Int? get() = monitor?.rttOpen ?: monitor?.rttRead
    val iconUrl: String? get() = monitor?.iconUrl
}

/**
 * Instrumentation result from a paginated fetch session.
 */
data class PaginatedFetchResult(
    val totalEvents: Int,
    val totalPages: Int,
    val oldestCreatedAt: Long,
    val relay: String,
)

// ── NIP-30 Custom Emoji ────────────────────────────────────────────────────

/** Single emoji declaration: shortcode + image URL. */
data class CustomEmoji(val shortcode: String, val url: String)

/** Kind-30030 emoji set: replaceable per (author, setName). */
data class EmojiSetEntity(
    val authorPubkey: String,
    val setName: String,
    val title: String?,
    val emojis: List<CustomEmoji>,
    val updatedAt: Long,
)

/** Kind-10030 user emoji list: references to subscribed sets + inline emoji tags. */
data class UserEmojiListEntity(
    val pubkey: String,
    val setRefs: List<EmojiSetRef>,
    val inlineEmojis: List<CustomEmoji>,
    val updatedAt: Long,
)

data class EmojiSetRef(
    val authorPubkey: String,
    val setName: String,
    val hintRelay: String? = null,
)

// ── NIP-57 Private Zap ────────────────────────────────────────────────────

/**
 * Decrypted contents of a NIP-57 private zap. Populated asynchronously
 * by PrivateZapRepository after decrypt (NIP-44 attempted then NIP-04
 * fallback) succeeds against the kind-9734's anon-tag ciphertext.
 *
 * senderPubkey is the recovered real sender (from the encrypted payload),
 * NOT the kind-9734 signer (which is the one-time anon keypair).
 */
data class DecryptedPrivateZap(
    val senderPubkey: String,
    val comment: String?,
)

/** Emitted by MES to request async decrypt of an anon-tagged kind-9734. */
data class PendingPrivateZapDecrypt(
    val zapReceiptId: String,    // kind-9735 event id (sidecar key)
    val anonCiphertext: String,  // Encrypted ciphertext (NIP-04 wire format)
    val anonSignerPubkey: String, // publicKey_a — pass as peerPubkey to decrypt
)

/** Parse an [EventModel] from a [FeedRow]'s fields. Pure function — equivalent to MES-parsed model. */
fun FeedRow.toEventModel(): com.unsilence.app.data.model.EventModel =
    com.unsilence.app.data.model.ContentParser.parse(
        id = id, pubkey = pubkey, kind = kind,
        content = content, tagsJson = tags,
        createdAt = createdAt, relayUrl = relayUrl,
        replyToId = replyToId, rootId = rootId,
        hasContentWarning = hasContentWarning,
        contentWarningReason = contentWarningReason,
    )
