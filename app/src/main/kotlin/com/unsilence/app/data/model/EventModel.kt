package com.unsilence.app.data.model

import androidx.compose.runtime.Immutable

/**
 * Pre-parsed feed event ready for direct rendering.
 *
 * Computed once at insert time by [ContentParser] and stored in the MES sidecar
 * cache. Composables read this directly — no per-recomposition parsing.
 *
 * The pubkey/createdAt fields are EFFECTIVE values:
 *   - For kind != 6/16: same as the raw event
 *   - For a verified embedded repost: the signed inner event's values
 *   - For a reference-only repost: the wrapper values until the independently
 *     fetched target is available. Unverified `p` tags are routing hints, never
 *     author identity.
 *
 * sourcePubkey/sourceCreatedAt preserve the wrapper values for displaying the
 * "X boosted · 2h ago" header on reposts.
 */
@Immutable
data class EventModel(
    val id: String,
    val pubkey: String,                  // EFFECTIVE: verified 6/16 unwraps to inner author
    val sourcePubkey: String,            // RAW: kind-6 = the reposter
    val kind: Int,                       // RAW outer kind (provenance: 6/16 for reposts)
    val effectiveKind: Int,              // wrapped target's kind for 6/16; else == kind. Drives detection/routing
    /**
     * Authenticated user-visible body. For ordinary events this is the outer
     * content; for a verified repost it is the signed inner content; for a
     * reference-only repost it is empty until the independently verified
     * target is resolved. This is the single seam for copy/preview/summary UI:
     * callers must never fall back to a kind-6/16 envelope's raw JSON.
     *
     * This retains the same String instance already owned by the source event;
     * it does not allocate a second copy of note bodies.
     */
    val displayContent: String,
    val articleContent: String?,         // unwrapped inner markdown for the article reader body; null for non-articles (avoids a 3rd copy of every note's text)
    val createdAt: Long,                 // EFFECTIVE
    val sourceCreatedAt: Long,           // RAW: kind-6 wrapper createdAt
    val relayUrl: String,
    val engagementId: String,            // rootId ?: id (for kind-6 stats targeting)
    val navigateId: String,              // kind-6 → targetId; else id
    val segments: List<Segment>,         // parsed content in source order
    val media: MediaManifest,            // grouped from segments
    val thread: ThreadRefs,
    val repost: RepostInfo?,             // null unless kind == 6 or 16
    val article: ArticleInfo?,           // null unless effectiveKind == 30023
    val warnings: ContentWarnings,
    val shortForm: Boolean = false,      // NIP-71 kind 22/34236, including embedded repost targets
    val customEmojis: Map<String, String> = emptyMap(), // NIP-30: shortcode → url
    val poll: PollInfo? = null,
    val truncated: Boolean = false,      // content/segments capped (spam-post DoS bound) → show chip
)

/**
 * Follows reference-only reposts through independently verified MES models.
 * A cycle, missing target, or excessive nesting fails closed instead of ever
 * exposing the wrapper envelope as content.
 */
fun EventModel.resolveDisplayModel(
    maxDepth: Int = 8,
    modelProvider: (String) -> EventModel?,
): EventModel? {
    var current = this
    val visited = HashSet<String>(maxDepth.coerceAtLeast(1))
    repeat(maxDepth.coerceAtLeast(1)) {
        if (!visited.add(current.id)) return null
        val repostInfo = current.repost
        if (repostInfo?.payload !is RepostPayload.ReferenceOnly) return current
        val targetId = repostInfo.targetId ?: return null
        current = modelProvider(targetId) ?: return null
    }
    return null
}

@Immutable
data class PollOption(
    val id: String,
    val label: String,
)

@Immutable
data class PollInfo(
    val options: List<PollOption>,
    val multipleChoice: Boolean,
    val endsAt: Long?,
    val responseRelays: List<String>,
)

/**
 * A piece of parsed content. Renderers walk segments in order.
 *
 * Image and Video segments are duplicated in [MediaManifest] for grid rendering;
 * keeping them in [segments] preserves source order so inline references render
 * in place (fixes "text nostr:nevent text https://x.com" ordering).
 */
@Immutable
sealed class Segment {
    @Immutable
    data class Text(val text: String) : Segment()

    @Immutable
    data class Link(val url: String) : Segment()

    @Immutable
    data class Image(
        val url: String,
        val imetaAspect: Float?,         // width/height, null if not in imeta
    ) : Segment()

    @Immutable
    data class Video(val model: VideoRenderModel) : Segment()

    @Immutable
    data class YouTube(val url: String, val videoId: String) : Segment()

    @Immutable
    data class MentionPubkey(
        val pubkeyHex: String,
        val hints: List<String>,         // relay hints from nprofile1
    ) : Segment()

    @Immutable
    data class QuoteEvent(
        val eventId: String,
        val hints: List<String>,         // relay hints from nevent1 + q-tags
        val author: String? = null,      // nevent author pubkey (hex) — enables outbox fetch
    ) : Segment()

    @Immutable
    data class QuoteAddress(
        val kind: Int,
        val author: String,
        val dTag: String,
        val hints: List<String>,
    ) : Segment()

    @Immutable
    data class Hashtag(val tag: String) : Segment()

    /** Checksum-validated Bitcoin/Lightning destination rendered as a payment card. */
    @Immutable
    data class Payment(val target: PaymentTarget) : Segment()

    /** Render-only NIP-nothing: consecutive leading-`>` lines in a kind-1 note,
     *  rendered with a quote rail + muted text. Inner segments are inline-only
     *  (Text/Link/Mention/Hashtag) — media is flattened to Links, no grids. */
    @Immutable
    data class BlockQuote(val segments: List<Segment>) : Segment()
}

/**
 * Media grouped from segments for grid rendering.
 *
 * Composables consume these directly instead of iterating segments to find media.
 * [ogCandidate] is the first non-media http(s) URL — used by OgChip.
 */
@Immutable
data class MediaManifest(
    val images: List<Segment.Image>,
    val videos: List<Segment.Video>,
    val ogCandidate: Segment.Link?,
    val youtubes: List<Segment.YouTube>,
)

@Immutable
data class ThreadRefs(
    val replyToId: String?,
    val rootId: String?,
)

/** A complete inner event whose canonical id and Schnorr signature were verified. */
@Immutable
data class VerifiedRepostEvent(
    val id: String,
    val pubkey: String,
    val kind: Int,
    val content: String,
    val createdAt: Long,
    val tags: List<List<String>>,
)

/**
 * The trust state of a NIP-18 payload. This sealed shape prevents the previous
 * impossible state where arbitrary JSON was marked both "embedded" and
 * "resolved" even though required event fields were absent.
 */
@Immutable
sealed interface RepostPayload {
    @Immutable
    data class VerifiedEmbedded(val event: VerifiedRepostEvent) : RepostPayload

    /** Empty, malformed, oversized, mismatched, or cryptographically invalid content. */
    data object ReferenceOnly : RepostPayload
}

/**
 * Metadata about a kind-6/16 repost.
 *
 * [targetAuthorHint] may come from a wrapper `p`/`a` tag. It is useful only for
 * outbox routing and target lookup; it must never be displayed or scored as a
 * verified identity. A trusted author is available only through
 * [RepostPayload.VerifiedEmbedded.event].
 */
@Immutable
data class RepostInfo(
    val targetId: String?,
    val relayHint: String?,
    val addressCoordinate: String?,
    val addressRelayHint: String?,
    val targetAuthorHint: String?,
    val proxyUrl: String?,
    val payload: RepostPayload,
)

/** kind-30023 (NIP-23 long-form article) metadata extracted from tags. */
@Immutable
data class ArticleInfo(
    val title: String?,
    val summary: String?,
    val image: String?,
    val publishedAt: Long?,
    val dTag: String?,
    /** Topic tags (`t`) — longform hashtags are a separate field, not body text. */
    val hashtags: List<String> = emptyList(),
)

@Immutable
data class ContentWarnings(
    val hasContentWarning: Boolean,
    val reason: String?,
)
