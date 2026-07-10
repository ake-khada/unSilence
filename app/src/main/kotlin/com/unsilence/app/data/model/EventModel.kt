package com.unsilence.app.data.model

import androidx.compose.runtime.Immutable

/**
 * Pre-parsed feed event ready for direct rendering.
 *
 * Computed once at insert time by [ContentParser] and stored in the MES sidecar
 * cache. Composables read this directly — no per-recomposition parsing.
 *
 * The pubkey/createdAt fields are EFFECTIVE values:
 *   - For kind != 6: same as the raw event
 *   - For kind == 6: the boosted author's pubkey/createdAt (from embedded JSON
 *     or fetched target event)
 *
 * sourcePubkey/sourceCreatedAt preserve the wrapper values for displaying the
 * "X boosted · 2h ago" header on reposts.
 */
@Immutable
data class EventModel(
    val id: String,
    val pubkey: String,                  // EFFECTIVE: kind-6 unwraps to inner author
    val sourcePubkey: String,            // RAW: kind-6 = the reposter
    val kind: Int,                       // RAW outer kind (provenance: 6/16 for reposts)
    val effectiveKind: Int,              // wrapped target's kind for 6/16; else == kind. Drives detection/routing
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
    val customEmojis: Map<String, String> = emptyMap(), // NIP-30: shortcode → url
    val poll: PollInfo? = null,
    val truncated: Boolean = false,      // content/segments capped (spam-post DoS bound) → show chip
)

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

/**
 * Metadata about a kind-6 repost.
 *
 * - [embeddedJson]: present when the repost includes the inner event's JSON in
 *   `content` (NIP-18 standard). null for bridge events (mostr.pub) that use
 *   only an e-tag reference.
 * - [resolvedFromInner]: true when pubkey/createdAt came from the embedded
 *   JSON. False means we fell back to the wrapper's pubkey (rare, shouldn't
 *   happen for properly-formed reposts).
 */
@Immutable
data class RepostInfo(
    val targetId: String?,
    val relayHint: String?,
    val targetAuthorPubkey: String?,
    val proxyUrl: String?,
    val embeddedJson: String?,
    val resolvedFromInner: Boolean,
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
