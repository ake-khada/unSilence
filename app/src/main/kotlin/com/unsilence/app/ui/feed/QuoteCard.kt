package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.model.ContentParser
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.SurfaceVariant
import com.unsilence.app.ui.theme.TextSecondary

/** Single-phase embedded quote resolution data. */
private data class QuoteResolution(
    val event: EventEntity? = null,
    val author: UserEntity? = null,
    val model: EventModel? = null,
    val unresolved: Boolean = false,
)

/**
 * Tappable inline card for a quoted nostr event ([Segment.QuoteEvent]).
 *
 * Uses the same EventModel + ContentFlow pipeline as top-level cards.
 * When the quoted event has a cached EventModel in MES, it is reused;
 * otherwise ContentParser.parse() is called on-the-fly from EventEntity fields.
 *
 * At [nestDepth] >= 1, renders text-only (no media, no further nested quotes)
 * to prevent infinite recursion and keep deeply nested quotes compact.
 *
 * Tap handling: a [pointerInput] gesture waits for an unconsumed UP event.
 * Interactive children (LinkAnnotation for mentions/links/hashtags) consume
 * their own taps; everything else falls through to [onNoteClick].
 */
@Composable
internal fun QuoteCard(
    segment: Segment.QuoteEvent,
    onNoteClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {},
    lookupEvent: (suspend (String, List<String>) -> EventEntity?)? = null,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    lookupModel: ((String) -> EventModel?)? = null,
    fetchOgMetadata: (suspend (String) -> OgMetadata?)? = null,
    hasCachedOgMetadata: ((String) -> Boolean)? = null,
    imageDimensionCache: ImageDimensionCache? = null,
    exoPlayer: ExoPlayer? = null,
    isActiveVideo: Boolean = false,
    activeVideoUrl: String? = null,
    isMuted: Boolean = true,
    onToggleMute: () -> Unit = {},
    thumbnailCache: VideoThumbnailCache? = null,
    onVideoModelsResolved: ((List<VideoRenderModel>) -> Unit)? = null,
    sensitiveMode: com.unsilence.app.data.memory.SensitiveContentMode =
        com.unsilence.app.data.memory.SensitiveContentMode.SHOW,
    modifier: Modifier = Modifier,
    nestDepth: Int = 0,
) {
    val quoteData by produceState(QuoteResolution(), segment.eventId, segment.hints) {
        val ev = lookupEvent?.invoke(segment.eventId, segment.hints)
        if (ev == null) {
            value = QuoteResolution(unresolved = true)
            return@produceState
        }
        val auth = lookupProfile?.invoke(ev.pubkey)
        // Try cached EventModel first, fall back to on-the-fly parse
        val cachedModel = lookupModel?.invoke(ev.id)
        val model = cachedModel ?: runCatching {
            ContentParser.parse(
                id = ev.id,
                pubkey = ev.pubkey,
                kind = ev.kind,
                content = ev.content,
                tagsJson = ev.tags,
                createdAt = ev.createdAt,
                relayUrl = ev.relayUrl,
                replyToId = ev.replyToId,
                rootId = ev.rootId,
                hasContentWarning = ev.hasContentWarning,
                contentWarningReason = ev.contentWarningReason,
            )
        }.getOrNull()
        value = QuoteResolution(ev, auth, model)
    }

    // NIP-36: the quoted TARGET's own content-warning (separate resolved event).
    val targetSensitive = quoteData.event?.hasContentWarning == true
    val targetReason = quoteData.event?.contentWarningReason

    // A quoted event that resolves to a long-form → render the canonical article
    // card (not the embedded markdown body). Same component as feed/naddr quotes.
    val resolvedModel = quoteData.model
    val resolvedDTag = resolvedModel?.article?.dTag
    if (resolvedModel?.effectiveKind == 30023 && resolvedDTag != null && nestDepth < 1) {
        // EmbeddedArticleCard self-gates via its own EventCard (using the
        // resolved article's content-warning), so no outer gate here.
        EmbeddedArticleCard(
            coord         = "30023:${resolvedModel.pubkey}:$resolvedDTag",
            author        = resolvedModel.pubkey,
            dTag          = resolvedDTag,
            hints         = segment.hints,
            onNoteClick   = onNoteClick,
            onAuthorClick = onAuthorClick,
            nestDepth     = nestDepth,
            sensitiveMode = sensitiveMode,
            modifier      = modifier,
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(segment.eventId) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val up = waitForUpOrCancellation()
                    if (up != null && !up.isConsumed) {
                        up.consume()
                        onNoteClick(segment.eventId)
                    }
                }
            }
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        val loadedEvent = quoteData.event
        val author = quoteData.author
        val eventModel = quoteData.model
        if (loadedEvent != null) {
            Column {
                // Header: avatar + name + timestamp
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarImage(
                        pubkey        = loadedEvent.pubkey,
                        picture       = author?.picture,
                        modifier      = Modifier.size(24.dp),
                        sizeDp        = 24.dp,
                        lookupProfile = lookupProfile,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text     = author?.displayName?.takeIf { it.isNotBlank() }
                            ?: author?.name?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }
                            ?: "${loadedEvent.pubkey.take(6)}…${loadedEvent.pubkey.takeLast(4)}",
                        color    = Color.White.copy(alpha = 0.7f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = AppType.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text     = relativeTime(loadedEvent.createdAt),
                        color    = TextSecondary,
                        fontSize = AppType.caption,
                    )
                }
                Spacer(Modifier.height(4.dp))

                com.unsilence.app.ui.shared.EmbeddedSensitiveGate(
                    mode = sensitiveMode, sensitive = targetSensitive, reason = targetReason,
                ) {
                if (eventModel != null && nestDepth < 1) {
                    // Full source-order rendering via ContentFlow (same pipeline as top-level cards)
                    ContentFlow(
                        model               = eventModel,
                        role                = CardRole.Embedded,
                        onNoteClick         = onNoteClick,
                        onAuthorClick       = onAuthorClick,
                        onHashtagClick      = onHashtagClick,
                        lookupProfile       = lookupProfile,
                        lookupEvent         = lookupEvent,
                        lookupModel         = lookupModel,
                        fetchOgMetadata     = fetchOgMetadata,
                        hasCachedOgMetadata = hasCachedOgMetadata,
                        imageDimensionCache = imageDimensionCache,
                        exoPlayer           = exoPlayer,
                        isActiveVideo       = isActiveVideo,
                        activeVideoUrl      = activeVideoUrl,
                        // Tap on a quoted video NAVIGATES to the quoted note (unchanged).
                        // Inline autoplay is wired, but fullscreen is intentionally not:
                        // the parent row's openFullscreen(row.id) would bind to the
                        // parent's own URL for own+quote rows. Documented, not "fixed".
                        onOpenFullscreen    = { onNoteClick(segment.eventId) },
                        isMuted             = isMuted,
                        onToggleMute        = onToggleMute,
                        thumbnailCache      = thumbnailCache,
                        onVideoModelsResolved = onVideoModelsResolved,
                        nestDepth           = nestDepth + 1,
                    )
                } else if (eventModel != null) {
                    // Max nesting reached: text-only from segments (no media, no nested quotes)
                    val textSegments = remember(eventModel.segments) {
                        eventModel.segments.filter {
                            it is Segment.Text || it is Segment.MentionPubkey
                        }
                    }
                    if (textSegments.isNotEmpty()) {
                        InlineText(
                            segments      = textSegments,
                            lookupProfile = lookupProfile,
                            onAuthorClick = onAuthorClick,
                            onTextClick   = { onNoteClick(segment.eventId) },
                            maxLines      = 4,
                            overflow      = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    // EventModel parse failed — show raw content as fallback
                    if (loadedEvent.content.isNotBlank()) {
                        NostrRichText(
                            content       = loadedEvent.content,
                            lookupProfile = lookupProfile,
                            onAuthorClick = onAuthorClick,
                            onTextClick   = { onNoteClick(segment.eventId) },
                            maxLines      = 6,
                            overflow      = TextOverflow.Ellipsis,
                        )
                    }
                }
                }
            }
        } else if (quoteData.unresolved) {
            // Terminal failure — compact fallback chip (tap opens thread via outer pointerInput)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            ) {
                Text(
                    text = "Quoted note unavailable",
                    color = TextSecondary,
                    fontSize = AppType.footnote,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = "Tap to open",
                    tint = TextSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else {
            // Loading skeleton (bounded ≤5s by lookupEvent timeout)
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Surface1))
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.width(100.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(Surface1))
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(2.dp)).background(Surface1))
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(0.7f).height(14.dp).clip(RoundedCornerShape(2.dp)).background(Surface1))
            }
        }
    }
}
