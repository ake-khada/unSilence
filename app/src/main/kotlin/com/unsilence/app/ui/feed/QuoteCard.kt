package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
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
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.model.ContentParser
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.data.model.resolveDisplayModel
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.shared.WotFeedMetaTimestamp
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.SurfaceVariant
import com.unsilence.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.StateFlow

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
 * The first two quote levels render full content. The third renders a compact
 * author-and-text summary, while deeper content stops at a continuation chip.
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
    profileFlow: ((String) -> StateFlow<UserEntity?>)? = null,
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
    wotLookup: ((String) -> WotLookup?)? = null,
    feedWotDisplayMode: FeedWotDisplayMode = FeedWotDisplayMode.NUMBERS,
    onWotSubjectsVisible: (Collection<String>) -> Unit = {},
    modifier: Modifier = Modifier,
    nestDepth: Int = 0,
) {
    val renderMode = quoteRenderMode(nestDepth)
    if (renderMode == QuoteRenderMode.CONTINUATION) {
        QuoteChainContinuationChip(
            onClick = { onNoteClick(segment.eventId) },
            modifier = modifier,
        )
        return
    }

    val quoteData by produceState(QuoteResolution(), segment.eventId, segment.hints) {
        val ev = lookupEvent?.invoke(segment.eventId, segment.hints)
        if (ev == null) {
            value = QuoteResolution(unresolved = true)
            return@produceState
        }
        // Try cached EventModel first, fall back to on-the-fly parse
        val cachedModel = lookupModel?.invoke(ev.id)
        val sourceModel = cachedModel ?: runCatching {
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
        // A quoted event may itself be a reference-only repost. Resolve only
        // independently verified models already in MES; cycles/missing targets
        // become the existing unavailable state, never blank protocol cards.
        val model = sourceModel?.resolveDisplayModel { id -> lookupModel?.invoke(id) }
        val auth = model?.pubkey?.let { lookupProfile?.invoke(it) }
            ?: lookupProfile?.invoke(ev.pubkey)
        value = QuoteResolution(ev, auth, model)
    }

    // NIP-36: the quoted TARGET's own content-warning (separate resolved event).
    val targetSensitive = quoteData.event?.hasContentWarning == true ||
        quoteData.model?.warnings?.hasContentWarning == true
    val targetReason = quoteData.event?.contentWarningReason
        ?: quoteData.model?.warnings?.reason

    // A quoted event that resolves to a long-form → render the canonical article
    // card (not the embedded markdown body). Same component as feed/naddr quotes.
    val resolvedModel = quoteData.model
    val quoteNavigationId = resolvedModel?.navigateId ?: segment.eventId
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

    val quotePadding = when (nestDepth) {
        0 -> Spacing.medium
        1 -> Spacing.small
        else -> Spacing.micro
    }
    val quoteCornerRadius = when (nestDepth) {
        0 -> 12.dp
        1 -> 8.dp
        else -> 5.dp
    }
    val avatarSize = when (nestDepth) {
        0 -> 24.dp
        1 -> 20.dp
        else -> 18.dp
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(quoteCornerRadius))
            .clip(RoundedCornerShape(quoteCornerRadius))
            .pointerInput(quoteNavigationId) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val up = waitForUpOrCancellation()
                    if (up != null && !up.isConsumed) {
                        up.consume()
                        onNoteClick(quoteNavigationId)
                    }
                }
            }
            .padding(quotePadding),
    ) {
        val loadedEvent = quoteData.event
        val eventModel = quoteData.model
        val resolvedPubkey = eventModel?.pubkey ?: loadedEvent?.pubkey
        val liveAuthor = resolvedPubkey?.let { pubkey ->
            collectProfileAsState(pubkey, profileFlow)
        }
        val author = liveAuthor ?: quoteData.author
        if (loadedEvent != null) {
            val displayPubkey = requireNotNull(resolvedPubkey)
            val displayCreatedAt = eventModel?.createdAt ?: loadedEvent.createdAt
            LaunchedEffect(displayPubkey) {
                onWotSubjectsVisible(listOf(displayPubkey))
            }
            Column {
                // Header: avatar + name + timestamp
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarImage(
                        pubkey        = displayPubkey,
                        picture       = author?.picture,
                        modifier      = Modifier.size(avatarSize),
                        sizeDp        = avatarSize,
                        lookupProfile = lookupProfile,
                        profileFlow   = profileFlow,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text     = author?.displayName?.takeIf { it.isNotBlank() }
                            ?: author?.name?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }
                            ?: "${displayPubkey.take(6)}…${displayPubkey.takeLast(4)}",
                        color    = Color.White.copy(alpha = 0.7f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = AppType.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    WotFeedMetaTimestamp(
                        lookup = wotLookup?.invoke(displayPubkey),
                        mode = feedWotDisplayMode,
                        timestamp = relativeTime(displayCreatedAt),
                        timestampColor = TextSecondary,
                    )
                }
                Spacer(Modifier.height(4.dp))

                com.unsilence.app.ui.shared.EmbeddedSensitiveGate(
                    mode = sensitiveMode, sensitive = targetSensitive, reason = targetReason,
                ) {
                if (eventModel != null && renderMode == QuoteRenderMode.FULL) {
                    // Full source-order rendering via ContentFlow (same pipeline as top-level cards)
                    ContentFlow(
                        model               = eventModel,
                        role                = CardRole.Embedded,
                        onNoteClick         = onNoteClick,
                        onAuthorClick       = onAuthorClick,
                        onHashtagClick      = onHashtagClick,
                        lookupProfile       = lookupProfile,
                        profileFlow         = profileFlow,
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
                        onOpenFullscreen    = { onNoteClick(eventModel.navigateId) },
                        isMuted             = isMuted,
                        onToggleMute        = onToggleMute,
                        thumbnailCache      = thumbnailCache,
                        onVideoModelsResolved = onVideoModelsResolved,
                        wotLookup           = wotLookup,
                        feedWotDisplayMode  = feedWotDisplayMode,
                        onWotSubjectsVisible = onWotSubjectsVisible,
                        nestDepth           = nestDepth + 1,
                    )
                } else if (eventModel != null) {
                    // Compact third level: text only. A deeper quote is represented
                    // locally so its event is never resolved or fetched here.
                    val textSegments = remember(eventModel.segments) {
                        eventModel.segments.filter {
                            it is Segment.Text ||
                                it is Segment.MentionPubkey ||
                                it is Segment.Link ||
                                it is Segment.Hashtag
                        }
                    }
                    val hasDeeperQuote = remember(eventModel.segments) {
                        eventModel.segments.any {
                            it is Segment.QuoteEvent || it is Segment.QuoteAddress
                        }
                    }
                    if (textSegments.isNotEmpty()) {
                        InlineText(
                            segments      = textSegments,
                            lookupProfile = lookupProfile,
                            onAuthorClick = onAuthorClick,
                            onTextClick   = { onNoteClick(eventModel.navigateId) },
                            maxLines      = 2,
                            overflow      = TextOverflow.Ellipsis,
                        )
                    }
                    if (hasDeeperQuote) {
                        Spacer(Modifier.height(Spacing.micro))
                        QuoteChainContinuationChip(
                            onClick = { onNoteClick(segment.eventId) },
                        )
                    }
                } else if (loadedEvent.kind != 6 && loadedEvent.kind != 16) {
                    // A verified native event may safely fall back to its signed
                    // body if model construction failed. A repost envelope is
                    // protocol JSON, never user-visible content.
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
                } else {
                    Text(
                        text = "Reposted post unavailable",
                        color = TextSecondary,
                        fontSize = AppType.footnote,
                    )
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
                    Box(Modifier.size(avatarSize).clip(CircleShape).background(Surface1))
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

@Composable
private fun QuoteChainContinuationChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(SurfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.small, vertical = Spacing.micro),
    ) {
        Text(
            text = "Quote chain continues",
            color = TextSecondary,
            fontSize = AppType.footnote,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Spacing.micro))
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(14.dp),
        )
    }
}
