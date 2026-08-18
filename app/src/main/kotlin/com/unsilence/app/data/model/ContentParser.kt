package com.unsilence.app.data.model

import android.util.Log
import com.unsilence.app.data.relay.ImetaMedia
import com.unsilence.app.data.relay.ImetaParser
import com.unsilence.app.data.relay.Nip19FailureCache
import com.unsilence.app.data.relay.NostrJson
import com.unsilence.app.data.relay.parseRepostInfo
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "ContentParser"

// ── Spam-post DoS bounds (H-spam) ───────────────────────────────────────────
// Two caps, both required, covering both stall mechanisms:
//  1. INPUT: the regex tokenization pass is O(content) — a 500KB string makes
//     findAll itself the freeze before any segment exists. Truncate BEFORE tokenize.
//  2. SEGMENTS: thousands of clickable segments explode composable count +
//     pointerInput modifiers. Cap AFTER tokenize, collapse the tail to one text node.
// Values: observed legit p100 < 60 segments / 4k chars → 150 / 20k is 2.5–5× headroom
// over legit, orders of magnitude below pathology. The bound IS the feature — no
// tap-to-expand (that would re-create the freeze we prevent).
private const val MAX_PARSE_CHARS = 20_000
// Long-form (kind-30023) input cap + the tail marker now live in shared ParseLimits
// (reused by the native markdown parser). The SEGMENT cap still applies to every kind.
private const val MAX_SEGMENTS = 150
private const val MAX_TAG_ONLY_HASHTAGS = 5
private const val MAX_TAG_ONLY_HASHTAG_CHARS = 80

internal fun isNip71VideoKind(kind: Int): Boolean =
    kind == 21 || kind == 22 || kind == 34235 || kind == 34236

internal fun isShortFormVideoKind(kind: Int): Boolean =
    kind == 22 || kind == 34236

/**
 * Single-pass content parser. Produces an [EventModel] from raw event fields.
 *
 * Called by EventProcessor.flushBatch (insert time) and MES.insertFromSnapshot
 * (warm-start). Result is cached in MES.eventModelsByEventId — composables
 * read from cache, never invoke this directly during render.
 *
 * Performance contract:
 *   - O(n) in content length for tokenization (single pass)
 *   - One JSON parse for kind-6 inner event (when content is non-empty)
 *   - One imeta tag parse via ImetaParser
 *   - Bech32 decoding only for nostr: URIs that aren't in Nip19FailureCache
 *
 * Thread safety: pure function. Callers may invoke from any thread.
 */
object ContentParser {

    /** Regex matching nostr:bech32 URIs. */
    private val NOSTR_URI_REGEX = Regex("nostr:[a-z0-9]+", RegexOption.IGNORE_CASE)

    /** YouTube URL recognizer (watch/shorts/youtu.be). */
    private val YOUTUBE_URL_REGEX = Regex(
        """https?://(?:www\.)?(?:youtube\.com/(?:watch\?v=|shorts/)|youtu\.be/)([A-Za-z0-9_-]{11})\S*""",
        RegexOption.IGNORE_CASE,
    )

    /** Direct video file URLs only — same recognizer as VideoRenderModel. */
    private val VIDEO_EXT_REGEX = Regex(
        """https?://\S+\.(?:mp4|mov|webm|m3u8|m4v|avi)(?:\?\S*)?""",
        RegexOption.IGNORE_CASE,
    )

    /** Image URL recognizer. Includes nostr.build CDNs. */
    private val IMAGE_URL_REGEX = Regex(
        """https?://\S+\.(?:jpg|jpeg|png|gif|webp)(?:\?\S*)?|https?://(?:image\.nostr\.build|i\.nostr\.build|nostr\.build|blossom\.primal\.net)/\S+""",
        RegexOption.IGNORE_CASE,
    )

    /** Generic http(s) URL recognizer. */
    private val URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    fun parse(
        id: String,
        pubkey: String,
        kind: Int,
        content: String,
        tagsJson: String,
        createdAt: Long,
        relayUrl: String,
        replyToId: String?,
        rootId: String?,
        hasContentWarning: Boolean,
        contentWarningReason: String?,
        preparsedImeta: List<ImetaMedia>? = null,
        preparsedRepost: RepostInfo? = null,
    ): EventModel {
        val parsedTags = parseTagLists(tagsJson)
        // This overload is used by flattened UI projections. They no longer
        // carry the outer event's verified-ingest context, so fail closed to a
        // reference rather than performing native Schnorr work during render.
        val safeRepost = preparsedRepost ?: if (kind == 6 || kind == 16) {
            parseRepostInfo(kind, content, parsedTags, verifyEmbedded = { false })
        } else {
            null
        }
        return parse(
            id = id,
            pubkey = pubkey,
            kind = kind,
            content = content,
            tags = parsedTags,
            createdAt = createdAt,
            relayUrl = relayUrl,
            replyToId = replyToId,
            rootId = rootId,
            hasContentWarning = hasContentWarning,
            contentWarningReason = contentWarningReason,
            preparsedImeta = preparsedImeta,
            preparsedRepost = safeRepost,
        )
    }

    /**
     * Structured-tag entry point for [com.unsilence.app.data.memory.NostrEvent].
     * Avoids serializing and reparsing a representation MES already owns.
     */
    fun parse(
        id: String,
        pubkey: String,
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long,
        relayUrl: String,
        replyToId: String?,
        rootId: String?,
        hasContentWarning: Boolean,
        contentWarningReason: String?,
        preparsedImeta: List<ImetaMedia>? = null,
        preparsedRepost: RepostInfo? = null,
    ): EventModel {
        // ── Step 1: Repost unwrap ─────────────────────────────────────────
        // Both kind-6 (note repost) and kind-16 (NIP-18 generic repost) wrap a
        // target event; parseRepostInfo is generic over embedded-JSON + e-tag.
        val repost = if (kind == 6 || kind == 16) {
            preparsedRepost ?: parseRepostInfo(kind, content, tags)
        } else null
        val verifiedInner = (repost?.payload as? RepostPayload.VerifiedEmbedded)?.event

        // Effective kind drives detection/routing: a 6/16 wrapping a 30023 must be
        // detected and rendered as an article, not raw markdown. Resolved from the
        // wrapped target's kind (embedded JSON, kind-16 `k` tag, else note=1).
        val effectiveKind = resolveEffectiveKind(kind, verifiedInner, tags)

        // Protocol JSON is never user-authored text. A reference-only repost
        // stays empty until its independently verified target is hydrated.
        val effectiveContent = when {
            verifiedInner != null -> verifiedInner.content
            repost != null -> ""
            else -> content
        }
        val effectiveTags = when {
            verifiedInner != null -> verifiedInner.tags
            repost != null -> emptyList()
            else -> tags
        }
        val effectivePubkey = verifiedInner?.pubkey ?: pubkey
        val effectiveCreatedAt = verifiedInner?.createdAt ?: createdAt

        // ── Step 2: Imeta + q-tag relay hints ────────────────────────────
        // Reuse preparsed imeta when available — but for kind-6 reposts,
        // always reparse from inner tags (preparsed was the wrapper's tags).
        val imeta = when {
            repost != null -> ImetaParser.parseFromList(effectiveTags)
            preparsedImeta != null -> preparsedImeta
            else -> ImetaParser.parseFromList(effectiveTags)
        }
        val qHints = extractQTagHints(effectiveTags)

        // ── Step 3: Bounded single-pass tokenization (spam-post DoS bound) ─
        // Cap 1: truncate INPUT before the O(content) regex pass. Long-form gets a
        // far larger cap (legit long prose); the segment cap below still bounds all kinds.
        val maxChars = if (effectiveKind == 30023) ParseLimits.MAX_ARTICLE_PARSE_CHARS else MAX_PARSE_CHARS
        val rawLen = effectiveContent.length
        val inputTruncated = rawLen > maxChars
        val parseInput = if (inputTruncated) effectiveContent.take(maxChars) else effectiveContent
        // kind-1 notes and kind-1111 comments (incl. reposted/wrapped — effectiveKind,
        // effectiveContent) get render-only leading-`>` blockquotes; else tokenize flat.
        // Pass effectiveKind (not raw kind) so native picture/video kinds wrapped
        // by a kind-6/16 repost prepend their imeta-only media. For native
        // events effectiveKind == kind, so this is a no-op there.
        val tokenized = if (effectiveKind == 1 || effectiveKind == 1111) {
            tokenizeWithBlockquotes(parseInput, imeta, qHints, effectiveKind)
        } else {
            tokenize(parseInput, imeta, qHints, effectiveKind)
        }
        // Cap 2: bound SEGMENT count; collapse the tail into one plain-text marker.
        // Flat count includes BlockQuote inner segments, so a wall of `>` lines can't
        // collapse into one top-level segment and bypass the draw-bound (H-spam).
        val (capped, segmentTruncated) = capSegmentsFlat(tokenized, MAX_SEGMENTS)
        val truncated = inputTruncated || segmentTruncated
        val parsedSegments =
            if (truncated) capped + Segment.Text(ParseLimits.TRUNCATION_MARKER) else tokenized
        // Some bot/protocol events abuse kind 1 as a tag-only envelope. Raw mode must
        // remain inspectable: expose bounded `t` tags as ordinary hashtags so users can
        // identify and mute the pattern even when the sender rotates pubkeys.
        val segments = if (kind == 1 && effectiveContent.isBlank() && parsedSegments.isEmpty()) {
            tagOnlyHashtags(effectiveTags).map { Segment.Hashtag(it) }
        } else {
            parsedSegments
        }

        // Permanent field probe — fires JUST UNDER the caps (and whenever truncation
        // actually triggers) so we keep seeing near-pathological content and can tune
        // thresholds from release logs. Log.w survives R8. Once per event (memoized).
        if (truncated || tokenized.size > MAX_SEGMENTS * 3 / 4 || rawLen > maxChars * 3 / 4) {
            Log.w(TAG, "PARSE-HEAVY: id=$id pubkey=$effectivePubkey kind=$kind " +
                "rawLen=$rawLen tokenized=${tokenized.size} truncated=$truncated " +
                "preview='${effectiveContent.take(140).replace("\n", " ")}'")
        }

        // ── Step 4: Group media for grid rendering ────────────────────────
        val manifest = buildManifest(segments)

        // ── Step 5: kind-30023 article info from tags (effective-kind aware) ─
        // Only emit an article when there is REAL article data to show. A bare-
        // coordinate kind-16 (k=30023, no embedded JSON, no inner tags) yields an
        // empty ArticleInfo — that must NOT route a blank shell to ArticleLayout;
        // it falls through to a note stub until the a-tag/naddr resolver (#5).
        val article = if (effectiveKind == 30023) {
            parseArticleInfo(effectiveTags).takeIf {
                it.title != null || it.summary != null || it.image != null || effectiveContent.isNotBlank()
            }
        } else null

        // ── Step 6: NIP-30 custom emoji tags ─────────────────────────────
        val customEmojis = parseCustomEmojis(effectiveTags)
        val poll = if (effectiveKind == 1068) parsePollInfo(effectiveTags) else null

        return EventModel(
            id = id,
            pubkey = effectivePubkey,
            sourcePubkey = pubkey,
            kind = kind,
            effectiveKind = effectiveKind,
            displayContent = effectiveContent,
            // Only articles carry the body string — notes leave it null so we don't
            // duplicate every note's content (MES already holds raw; model holds segments).
            articleContent = if (article != null) effectiveContent else null,
            createdAt = effectiveCreatedAt,
            sourceCreatedAt = createdAt,
            relayUrl = relayUrl,
            engagementId = if (kind == 6 || kind == 16) (repost?.targetId ?: rootId ?: id) else id,
            navigateId = if (kind == 6 || kind == 16) (repost?.targetId ?: id) else id,
            segments = segments,
            media = manifest,
            thread = ThreadRefs(replyToId, rootId),
            repost = repost,
            article = article,
            warnings = effectiveWarnings(repost, hasContentWarning, contentWarningReason, effectiveTags),
            shortForm = isShortFormVideoKind(effectiveKind),
            customEmojis = customEmojis,
            poll = poll,
            truncated = truncated,
        )
    }

    private fun parsePollInfo(tags: List<List<String>>): PollInfo? = runCatching {
        val seenIds = mutableSetOf<String>()
        val options = tags.asSequence()
            .filter { it.getOrNull(0) == "option" }
            .mapNotNull { tag ->
                val id = tag.getOrNull(1)?.take(128)?.takeIf {
                    it.isNotBlank() && it.none(Char::isISOControl)
                } ?: return@mapNotNull null
                val label = tag.getOrNull(2)?.trim()?.take(300)
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (!seenIds.add(id)) return@mapNotNull null
                PollOption(id, label)
            }
            .take(32)
            .toList()
        if (options.size < 2) return@runCatching null

        val pollType = tags.firstOrNull { it.getOrNull(0) == "polltype" }
            ?.getOrNull(1)
        val endsAt = tags.firstOrNull {
            val name = it.getOrNull(0)
            name == "endsAt" || name == "closed_at"
        }
            ?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
        val relays = tags.asSequence()
            .filter { it.getOrNull(0) == "relay" }
            .mapNotNull { it.getOrNull(1) }
            .filter { it.startsWith("wss://") || it.startsWith("ws://") }
            .distinct()
            .take(6)
            .toList()
        PollInfo(options, pollType == "multiplechoice", endsAt, relays)
    }.getOrNull()

    /**
     * Effective NIP-36 warning for the model. For kind-6/16 reposts the inner
     * (effective) tags carry the target's content-warning, which the wrapper's
     * passed-in flag misses. ORs them so [EventModel.warnings] is honest for any
     * consumer. Mirrors the verified-ingest effectiveContentWarning helper
     * (which sets the FeedRow flag that gates feed hide + card blur/hide).
     */
    private fun effectiveWarnings(
        repost: RepostInfo?,
        wrapperHasCw: Boolean,
        wrapperReason: String?,
        effectiveTags: List<List<String>>,
    ): ContentWarnings {
        if (repost == null) return ContentWarnings(wrapperHasCw, wrapperReason)
        val cw = effectiveTags.firstOrNull { it.getOrNull(0) == "content-warning" }
        val inner = if (cw == null) false to null
            else true to cw.getOrNull(1)?.takeIf { it.isNotBlank() }
        return ContentWarnings(wrapperHasCw || inner.first, wrapperReason ?: inner.second)
    }

    /**
     * The kind that drives detection/routing. A kind-6/16 repost wraps a target
     * event; resolve the target's kind so a reposted long-form (30023) is parsed
     * and rendered as an article rather than raw markdown. Resolution order:
     *  - embedded JSON `kind` (NIP-18 quote/generic repost — both 6 and 16),
     *  - kind-16 `k` tag (generic repost without embedded JSON),
     *  - else 1 (kind-6 bridge reposts are note reposts by convention).
     * A kind-16 article-repost with neither embedded JSON nor a 30023 `k` tag
     * resolves to 1 and renders as a note stub until a-tag/naddr resolution lands.
     * Non-reposts return their own kind.
     */
    private fun resolveEffectiveKind(
        rawKind: Int,
        verifiedInner: VerifiedRepostEvent?,
        wrapperTags: List<List<String>>,
    ): Int {
        if (rawKind != 6 && rawKind != 16) return rawKind
        verifiedInner?.let { return it.kind }
        if (rawKind == 16) extractKTagKind(wrapperTags)?.let { return it }
        return 1
    }

    /** NIP-18 generic repost (kind-16) tags the reposted event's kind as `k`. */
    private fun extractKTagKind(tags: List<List<String>>): Int? =
        tags.firstOrNull { it.getOrNull(0) == "k" }
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it in 0..65_535 }

    // ── Tokenization ─────────────────────────────────────────────────────

    /**
     * Walk content once, classifying each text run. Order is preserved.
     *
     * Token precedence at each cursor position:
     *   1. nostr: URIs   (highest — they may contain other URLs as text)
     *   2. YouTube       (matched before generic URL)
     *   3. video files   (.mp4 etc — distinct from generic Link)
     *   4. image files   (matched before generic URL)
     *   5. http(s) URLs  (generic Link)
     *   6. validated Bitcoin / Lightning payment targets
     *   7. plain text    (everything else)
     *
     * For native picture/video kinds, we ALSO surface imeta entries
     * as Image/Video segments at the head — these kinds put media in tags,
     * not content.
     */
    private fun tokenize(
        content: String,
        imeta: List<ImetaMedia>,
        qHints: Map<String, List<String>>,
        kind: Int,
    ): List<Segment> {
        val out = mutableListOf<Segment>()

        // Native picture/video kinds put their primary media in imeta tags.
        if (kind == 20 || isNip71VideoKind(kind)) {
            for (m in imeta) {
                val mime = m.mimeType?.lowercase() ?: ""
                val cleanUrl = cleanMediaUrl(m.url)
                val youtube = YOUTUBE_URL_REGEX.matchEntire(cleanUrl)
                when {
                    youtube != null && isNip71VideoKind(kind) -> {
                        if (out.none { it is Segment.YouTube && it.videoId == youtube.groupValues[1] }) {
                            out.add(Segment.YouTube(cleanUrl, youtube.groupValues[1]))
                        }
                    }
                    kind == 20 || mime.startsWith("image/") -> {
                        if (out.none { it is Segment.Image && mediaUrlMatches(it.url, m.url) }) {
                            out.add(Segment.Image(
                                url = cleanUrl,
                                imetaAspect = if (m.width != null && m.height != null && m.height > 0)
                                    m.width.toFloat() / m.height else null,
                            ))
                        }
                    }
                    (isNip71VideoKind(kind) && !mime.startsWith("audio/")) ||
                        mime.startsWith("video/") -> {
                        val model = buildVideoRenderModelForUrl(
                            url = m.url,
                            imeta = imeta,
                            allowImetaVideo = true,
                            shortForm = isShortFormVideoKind(kind),
                        )
                        if (model != null && out.none { it is Segment.Video && mediaUrlMatches(it.model.videoUrl, m.url) }) {
                            out.add(Segment.Video(model))
                        }
                    }
                }
            }
        }

        if (content.isBlank()) return out

        // Build a sorted list of all token matches, then walk left-to-right.
        data class Match(val start: Int, val end: Int, val precedence: Int, val build: () -> Segment?)

        val matches = mutableListOf<Match>()

        // Precedence 1: nostr: URIs
        for (m in NOSTR_URI_REGEX.findAll(content)) {
            matches.add(Match(m.range.first, m.range.last + 1, 1) {
                buildNostrSegment(m.value, qHints)
            })
        }
        // Precedence 2: YouTube
        for (m in YOUTUBE_URL_REGEX.findAll(content)) {
            matches.add(Match(m.range.first, m.range.last + 1, 2) {
                Segment.YouTube(m.value, m.groupValues[1])
            })
        }
        // Precedence 3: video files
        for (m in VIDEO_EXT_REGEX.findAll(content)) {
            matches.add(Match(m.range.first, m.range.last + 1, 3) {
                val model = buildVideoRenderModelForUrl(
                    url = m.value,
                    imeta = imeta,
                    shortForm = isShortFormVideoKind(kind),
                ) ?: return@Match null
                Segment.Video(model)
            })
        }
        // Precedence 4: images
        for (m in IMAGE_URL_REGEX.findAll(content)) {
            matches.add(Match(m.range.first, m.range.last + 1, 4) {
                val cleanUrl = cleanMediaUrl(m.value)
                val imetaAspect = imeta.firstOrNull { mediaUrlMatches(it.url, cleanUrl) }
                    ?.let { im ->
                        if (im.width != null && im.height != null && im.height > 0)
                            im.width.toFloat() / im.height else null
                    }
                Segment.Image(cleanUrl, imetaAspect)
            })
        }
        // Precedence 5: generic URLs
        for (m in URL_REGEX.findAll(content)) {
            // Trim punctuation before constructing the match itself. Otherwise
            // the longer generic match wins overlap resolution over a media
            // match and either swallows punctuation or downgrades the media.
            val cleanUrl = cleanMediaUrl(m.value)
            val end = m.range.first + cleanUrl.length
            val meta = imeta.firstOrNull { mediaUrlMatches(it.url, cleanUrl) }
            val mime = meta?.mimeType?.substringBefore(';')?.trim()?.lowercase()
            val width = meta?.width
            val height = meta?.height
            matches.add(Match(m.range.first, end, 5) {
                when {
                    mime?.startsWith("image/") == true -> Segment.Image(
                        url = cleanUrl,
                        imetaAspect = if (width != null && height != null && height > 0) {
                            width.toFloat() / height
                        } else null,
                    )
                    mime?.startsWith("video/") == true -> buildVideoRenderModelForUrl(
                        url = cleanUrl,
                        imeta = imeta,
                        allowImetaVideo = true,
                        shortForm = isShortFormVideoKind(kind),
                    )?.let { Segment.Video(it) }
                    else -> Segment.Link(cleanUrl)
                }
            })
        }
        // Precedence 6: checksum-validated payment destinations. LUD-16 is the
        // one syntax-only format because it is intentionally email-shaped;
        // rendering never contacts its domain merely to classify content.
        for (located in PaymentTargetParser.findAll(content)) {
            matches.add(Match(located.start, located.endExclusive, 6) {
                Segment.Payment(located.target)
            })
        }
        // Precedence 7: hashtags (structural walk — not regex)
        for ((start, end, tag) in findHashtags(content)) {
            matches.add(Match(start, end, 7) { Segment.Hashtag(tag) })
        }

        // Resolve overlaps: sort by start ASC, length DESC, precedence ASC.
        matches.sortWith(
            compareBy<Match> { it.start }
                .thenByDescending { it.end - it.start }
                .thenBy { it.precedence }
        )

        var cursor = 0
        for (m in matches) {
            if (m.start < cursor) continue
            if (m.start > cursor) {
                val text = content.substring(cursor, m.start)
                if (text.isNotEmpty()) out.add(Segment.Text(text))
            }
            val seg = m.build()
            if (seg != null) {
                out.add(seg)
                cursor = m.end
            } else {
                // build() returned null — emit as text so it stays visible
                out.add(Segment.Text(content.substring(m.start, m.end)))
                cursor = m.end
            }
        }
        if (cursor < content.length) {
            val tail = content.substring(cursor)
            if (tail.isNotEmpty()) out.add(Segment.Text(tail))
        }

        return out
    }

    // ── Blockquotes (render-only, kind-1) ────────────────────────────────────

    /**
     * kind-1 only: split content into line-groups, emitting [Segment.BlockQuote] for
     * runs of leading-`>` lines and delegating every other chunk to [tokenize]. Order
     * is preserved by processing groups left-to-right; no token type spans a newline,
     * so per-chunk tokenization is equivalent to tokenizing the whole string.
     */
    private fun tokenizeWithBlockquotes(
        content: String,
        imeta: List<ImetaMedia>,
        qHints: Map<String, List<String>>,
        kind: Int,
    ): List<Segment> {
        if ('>' !in content) return tokenize(content, imeta, qHints, kind) // no quotes: fast path
        val lines = content.split("\n")
        val out = mutableListOf<Segment>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].startsWith(">")) {
                val start = i
                while (i < lines.size && lines[i].startsWith(">")) i++
                val body = lines.subList(start, i).joinToString("\n") { stripQuotePrefix(it) }
                val inner = tokenize(body, imeta, qHints, kind).map(::flattenMediaToLink)
                out.add(Segment.BlockQuote(inner))
            } else {
                val start = i
                while (i < lines.size && !lines[i].startsWith(">")) i++
                val body = lines.subList(start, i).joinToString("\n")
                out.addAll(tokenize(body, imeta, qHints, kind))
            }
        }
        return out
    }

    /** Strip exactly one leading `>` then one optional following space. */
    private fun stripQuotePrefix(line: String): String {
        val afterGt = line.removePrefix(">")
        return if (afterGt.startsWith(" ")) afterGt.substring(1) else afterGt
    }

    /** Blockquotes stay inline-only: retain media URLs as Links and payment
     *  destinations as text rather than silently dropping either type. */
    private fun flattenMediaToLink(seg: Segment): Segment = when (seg) {
        is Segment.Image   -> Segment.Link(seg.url)
        is Segment.Video   -> Segment.Link(seg.model.videoUrl)
        is Segment.YouTube -> Segment.Link(seg.url)
        is Segment.Payment -> Segment.Text(seg.target.copyText)
        else               -> seg
    }

    /**
     * Bound total segments (counting [Segment.BlockQuote] inner segments) at [max].
     * Returns (capped list, wasTruncated). Counting nested segments keeps the H-spam
     * draw-bound intact: a wall of `>` lines can't hide thousands of segments inside
     * one top-level BlockQuote.
     */
    private fun capSegmentsFlat(segments: List<Segment>, max: Int): Pair<List<Segment>, Boolean> {
        var budget = max
        val out = ArrayList<Segment>(minOf(segments.size, max))
        for (seg in segments) {
            if (budget <= 0) return out to true
            if (seg is Segment.BlockQuote) {
                if (seg.segments.size > budget) {
                    out.add(Segment.BlockQuote(seg.segments.take(budget)))
                    return out to true
                }
                budget -= seg.segments.size
                out.add(seg)
            } else {
                out.add(seg)
                budget -= 1
            }
        }
        return out to false
    }

    /** Decode a nostr:bech32 URI into the appropriate Segment. */
    private fun buildNostrSegment(uri: String, qHints: Map<String, List<String>>): Segment? {
        if (Nip19FailureCache.isKnownBad(uri)) return null
        val entity = runCatching { Nip19Parser.uriToRoute(uri)?.entity }
            .onFailure { Nip19FailureCache.markBad(uri) }
            .getOrNull()
        return when (entity) {
            is NPub -> Segment.MentionPubkey(entity.hex, emptyList())
            is NProfile -> Segment.MentionPubkey(entity.hex, entity.relay.map { it.url })
            is NEvent -> {
                val extraHints = qHints[entity.hex].orEmpty()
                Segment.QuoteEvent(
                    eventId = entity.hex,
                    hints = (entity.relay.map { it.url } + extraHints).distinct(),
                    author = entity.author,
                )
            }
            is NNote -> {
                val extraHints = qHints[entity.hex].orEmpty()
                Segment.QuoteEvent(
                    eventId = entity.hex,
                    hints = extraHints,
                )
            }
            is NAddress -> Segment.QuoteAddress(
                kind = entity.kind,
                author = entity.author,
                dTag = entity.dTag,
                hints = entity.relay.map { it.url },
            )
            else -> {
                Nip19FailureCache.markBad(uri)
                null
            }
        }
    }

    // ── Hashtag detection (structural walk) ────────────────────────────

    /**
     * Walk [content] and return (start, end, tag) triples for valid hashtags.
     *
     * Rules:
     *   - `#` must be at start-of-content or preceded by whitespace
     *   - NOT after `/`, letter, or digit (avoids URL fragments and `id#123`)
     *   - Tag body: 1+ chars that are Unicode letters, digits, or underscore
     *   - Trailing punctuation excluded from the segment
     *
     * Overlap resolution with URLs is handled by the caller (precedence 6 loses
     * to precedence 1-5 URLs), so a `#section` inside a URL won't be emitted.
     */
    internal fun findHashtags(content: String): List<Triple<Int, Int, String>> {
        val results = mutableListOf<Triple<Int, Int, String>>()
        var i = 0
        while (i < content.length) {
            if (content[i] == '#') {
                // Check preceding character for valid word boundary
                val valid = if (i == 0) true else {
                    val prev = content[i - 1]
                    prev.isWhitespace() || prev == '\n'
                }
                if (valid && i + 1 < content.length) {
                    // Walk the tag body: letters, digits, underscores
                    var j = i + 1
                    while (j < content.length) {
                        val c = content[j]
                        if (c.isLetterOrDigit() || c == '_') j++ else break
                    }
                    if (j > i + 1) {
                        val tag = content.substring(i + 1, j)
                        results.add(Triple(i, j, tag))
                        i = j
                        continue
                    }
                }
            }
            i++
        }
        return results
    }

    /**
     * Build a VideoRenderModel for [url] using [imeta]. Returns null if the URL
     * is neither a direct video URL nor an imeta-proven video.
     */
    private fun buildVideoRenderModelForUrl(
        url: String,
        imeta: List<ImetaMedia>,
        allowImetaVideo: Boolean = false,
        shortForm: Boolean = false,
    ): VideoRenderModel? {
        val cleanUrl = cleanMediaUrl(url)
        if (cleanUrl.isBlank()) return null
        val meta = imeta.firstOrNull { mediaUrlMatches(it.url, cleanUrl) }
        if (!isDirectVideoUrl(cleanUrl) && !allowImetaVideo && meta?.mimeType?.startsWith("video/") != true) {
            return null
        }
        val aspect = if (meta?.width != null && meta.height != null && meta.height > 0)
            meta.width.toFloat() / meta.height
        else 16f / 9f
        return VideoRenderModel(
            videoUrl = cleanUrl,
            aspectRatio = aspect,
            posterUrl = meta?.thumb ?: meta?.image,
            widthPx = meta?.width,
            heightPx = meta?.height,
            shortForm = shortForm,
            fallbackUrls = meta?.fallbacks.orEmpty(),
            durationSeconds = meta?.durationSeconds,
            mimeType = meta?.mimeType,
            sizeBytes = meta?.sizeBytes,
        )
    }

    private fun isDirectVideoUrl(url: String): Boolean =
        cleanMediaUrl(url).let { clean ->
            clean.contains(".mp4", ignoreCase = true) ||
                clean.contains(".mov", ignoreCase = true) ||
                clean.contains(".webm", ignoreCase = true) ||
                clean.contains(".m3u8", ignoreCase = true) ||
                clean.contains(".m4v", ignoreCase = true) ||
                clean.contains(".avi", ignoreCase = true)
        }

    // ── Manifest grouping ────────────────────────────────────────────────

    private fun buildManifest(segments: List<Segment>): MediaManifest {
        val images = segments.filterIsInstance<Segment.Image>()
        val videos = segments.filterIsInstance<Segment.Video>()
        val youtubes = segments.filterIsInstance<Segment.YouTube>()
        val ogCandidate = segments.filterIsInstance<Segment.Link>().firstOrNull()
        return MediaManifest(images, videos, ogCandidate, youtubes)
    }

    // ── Article info ─────────────────────────────────────────────────────

    private fun parseArticleInfo(tags: List<List<String>>): ArticleInfo {
        var title: String? = null
        var summary: String? = null
        var image: String? = null
        var publishedAt: Long? = null
        var dTag: String? = null
        val hashtags = mutableListOf<String>()
        for (tag in tags) {
            val key = tag.getOrNull(0) ?: continue
            val value = tag.getOrNull(1) ?: continue
            when (key) {
                "title" -> title = value
                "summary" -> summary = value
                "image" -> image = value
                "published_at" -> publishedAt = value.toLongOrNull()
                "d" -> dTag = value
                "t" -> value.trim().removePrefix("#").takeIf { it.isNotBlank() }
                    ?.let { if (it !in hashtags) hashtags.add(it) }
            }
        }
        return ArticleInfo(title, summary, image, publishedAt, dTag, hashtags)
    }

    // ── NIP-30 emoji tags ───────────────────────────────────────────────

    private fun parseCustomEmojis(tags: List<List<String>>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (tag in tags) {
            if (tag.size < 3 || tag[0] != "emoji") continue
            val shortcode = tag[1].takeIf { it.isNotBlank() } ?: continue
            val url = tag[2].takeIf { it.isNotBlank() } ?: continue
            out.putIfAbsent(shortcode, url)
        }
        return out
    }

    // ── Tag helpers ──────────────────────────────────────────────────────

    internal fun tagOnlyHashtags(tagsJson: String): List<String> {
        return tagOnlyHashtags(parseTagLists(tagsJson))
    }

    internal fun tagOnlyHashtags(tags: List<List<String>>): List<String> {
        val seen = HashSet<String>()
        return buildList {
            for (tag in tags) {
                if (tag.getOrNull(0) != "t") continue
                val value = tag.getOrNull(1)
                    ?.trim()
                    ?.trimStart('#')
                    ?.takeIf { candidate ->
                        candidate.isNotEmpty() &&
                            candidate.length <= MAX_TAG_ONLY_HASHTAG_CHARS &&
                            candidate.none { it.isWhitespace() || it.isISOControl() }
                    }
                    ?: continue
                if (seen.add(value.lowercase())) add(value)
                if (size >= MAX_TAG_ONLY_HASHTAGS) break
            }
        }
    }

    private fun extractQTagHints(tags: List<List<String>>): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        for (tag in tags) {
            if (tag.getOrNull(0) == "q") {
                val id = tag.getOrNull(1) ?: continue
                val relay = tag.getOrNull(2)?.takeIf { it.isNotBlank() } ?: continue
                result.getOrPut(id) { mutableListOf() }.add(relay)
            }
        }
        return result
    }

    private fun parseTagLists(tagsJson: String): List<List<String>> = runCatching {
        parseTagLists(NostrJson.parseToJsonElement(tagsJson))
    }.getOrDefault(emptyList())

    private fun parseTagLists(element: kotlinx.serialization.json.JsonElement): List<List<String>> =
        element.jsonArray.mapNotNull { row ->
            runCatching { row.jsonArray.map { it.jsonPrimitive.content } }.getOrNull()
        }
}
