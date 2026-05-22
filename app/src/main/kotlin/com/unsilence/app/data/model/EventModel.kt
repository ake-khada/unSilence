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
    val kind: Int,
    val createdAt: Long,                 // EFFECTIVE
    val sourceCreatedAt: Long,           // RAW: kind-6 wrapper createdAt
    val relayUrl: String,
    val engagementId: String,            // rootId ?: id (for kind-6 stats targeting)
    val navigateId: String,              // kind-6 → targetId; else id
    val segments: List<Segment>,         // parsed content in source order
    val media: MediaManifest,            // grouped from segments
    val thread: ThreadRefs,
    val repost: RepostInfo?,             // null unless kind == 6
    val article: ArticleInfo?,           // null unless kind == 30023
    val warnings: ContentWarnings,
    val customEmojis: Map<String, String> = emptyMap(), // NIP-30: shortcode → url
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
    ) : Segment()

    @Immutable
    data class QuoteAddress(
        val kind: Int,
        val author: String,
        val dTag: String,
        val hints: List<String>,
    ) : Segment()
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
)

@Immutable
data class ContentWarnings(
    val hasContentWarning: Boolean,
    val reason: String?,
)
