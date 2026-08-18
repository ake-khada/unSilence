package com.unsilence.app.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.RepostPayload
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.ui.common.rememberWidthImageRequest
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.shared.EventEngagementSnapshot
import com.unsilence.app.ui.shared.SensitiveContentHiddenCard
import com.unsilence.app.ui.shared.ThreadParentCard
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.SurfaceVariant
import com.unsilence.app.data.wallet.ZapRequest
import com.unsilence.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Unified event card — replaces NoteCard + ArticleCard.
 *
 * Consumes a pre-parsed [EventModel] and renders via ContentFlow + primitives.
 * No per-recomposition parsing. Kind-6 repost handling, kind-30023 article
 * layout, and all other kinds share this single entry point.
 */
@Composable
fun EventCard(
    model: EventModel,
    row: FeedRow,
    role: CardRole,
    engagement: EventEngagementSnapshot,
    onNoteClick: (String) -> Unit,
    onComment: () -> Unit = {},
    onAuthorClick: (String) -> Unit,
    onHashtagClick: (String) -> Unit = {},
    onQuote: (String) -> Unit,
    onArticleClick: (FeedRow) -> Unit,
    onReact: () -> Unit,
    onReactLongPress: () -> Unit = {},
    pinnedEmojis: List<com.unsilence.app.data.memory.CustomEmoji> = emptyList(),
    onReactWithEmoji: (com.unsilence.app.data.memory.CustomEmoji) -> Unit = {},
    onRepost: () -> Unit,
    onZap: (ZapRequest) -> Unit,
    onSaveNwcUri: (String) -> Unit,
    lookupProfile: (suspend (String) -> UserEntity?)?,
    lookupEvent: (suspend (String, List<String>) -> EventEntity?)?,
    lookupEventWithAuthor: (suspend (String, List<String>, String?) -> EventEntity?)? = null,
    lookupEventReference: (suspend (EventReferenceTarget) -> EventEntity?)? = null,
    lookupModel: ((String) -> EventModel?)? = null,
    fetchOgMetadata: (suspend (String) -> OgMetadata?)?,
    hasCachedOgMetadata: ((String) -> Boolean)? = null,
    profileFlow: ((String) -> StateFlow<UserEntity?>)?,
    statsFlow: ((String) -> StateFlow<com.unsilence.app.data.memory.EventStats>)? = null,
    zapDetailsForEvent: ((String) -> List<com.unsilence.app.data.memory.ZapDetail>)? = null,
    repostPubkeysForEvent: ((String) -> List<String>)? = null,
    reactionsForEvent: ((String) -> List<com.unsilence.app.data.memory.ReactionInfo>)? = null,
    imageDimensionCache: ImageDimensionCache?,
    thumbnailCache: VideoThumbnailCache?,
    // Video
    exoPlayer: ExoPlayer? = null,
    isMuted: Boolean = true,
    onToggleMute: () -> Unit = {},
    isActiveVideo: Boolean = false,
    activeVideoUrl: String? = null,
    isFullscreen: Boolean = false,
    onOpenFullscreen: () -> Unit = {},
    onVideoModelsResolved: ((List<VideoRenderModel>) -> Unit)? = null,
    // New-post animation
    isNewPost: Boolean = false,
    onNewPostAnimated: () -> Unit = {},
    // Thread focus flash
    isFocused: Boolean = false,
    // Thread parent (Conversations tab)
    parentEvent: EventEntity? = null,
    parentAuthor: UserEntity? = null,
    // Long-press actions
    onLongPress: (() -> Unit)? = null,
    // NIP-36 content warning
    sensitiveMode: SensitiveContentMode = SensitiveContentMode.SHOW,
    isSensitive: Boolean = false,
    contentWarningReason: String? = null,
    wotLookup: ((String) -> WotLookup?)? = null,
    feedWotDisplayMode: FeedWotDisplayMode = FeedWotDisplayMode.NUMBERS,
    onWotSubjectsVisible: (Collection<String>) -> Unit = {},
    pollActions: com.unsilence.app.ui.shared.PollActionCallbacks? = null,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current

    // Card flash animation — new-post arrival or thread focus highlight
    val flashAlpha = remember { Animatable(if (isNewPost || isFocused) 1f else 0f) }
    LaunchedEffect(isNewPost) {
        if (isNewPost) {
            flashAlpha.snapTo(1f)
            flashAlpha.animateTo(0f, tween(durationMillis = 1000))
            onNewPostAnimated()
        }
    }
    LaunchedEffect(isFocused) {
        if (isFocused) {
            flashAlpha.snapTo(1f)
            flashAlpha.animateTo(0f, tween(durationMillis = 1000))
        }
    }

    // NIP-36 blur/hide state — tap to reveal, per-card
    var revealed by remember { mutableStateOf(false) }
    val showBlur = sensitiveMode == SensitiveContentMode.BLUR && isSensitive && !revealed
    val hideWhole = sensitiveMode == SensitiveContentMode.HIDE && isSensitive

    // Resolve source profile for repost header (kind-6 wrapper author).
    val liveSourceProfile = if (model.repost != null) {
        collectProfileAsState(model.sourcePubkey, profileFlow)
    } else null
    val sourceProfile = liveSourceProfile?.takeIf { !it.picture.isNullOrBlank() }
        ?: if (model.repost != null && row.pubkey == model.sourcePubkey) {
            UserEntity(
                pubkey = model.sourcePubkey,
                name = row.authorName,
                displayName = row.authorDisplayName,
                picture = row.authorPicture,
                nip05 = row.authorNip05,
            )
        } else {
            liveSourceProfile
        }

    // Fallback profile fetch for reposts when the source's profile hasn't
    // arrived yet — the existing AvatarImage `lookupProfile` debounce covers
    // most cases; this LaunchedEffect catches the long tail.
    if (model.repost != null && sourceProfile?.picture.isNullOrBlank() && lookupProfile != null) {
        LaunchedEffect(model.sourcePubkey) {
            delay(1500)
            lookupProfile(model.sourcePubkey)
        }
    }

    // Resolve the EFFECTIVE author profile reactively. Previously we only
    // observed profileFlow for repost inner-authors; non-repost cards read
    // from the FeedRow snapshot, which forced a list-wide recompute every
    // time any profile updated. Now every card observes its own author's
    // profile flow — profile X arriving only re-composes cards by X.
    //
    // The FeedRow author fields remain as the initial snapshot — used as
    // a fallback when the flow hasn't emitted yet, so the first frame after
    // mount doesn't flash empty avatars on rows whose profiles MES already
    // has cached.
    val authorProfile = collectProfileAsState(model.pubkey, profileFlow)

    // For repost cards, model.pubkey is the inner (effective) author and
    // model.sourcePubkey is the wrapper author. authorProfile reactively
    // resolves the inner author for both cases.
    val effectiveProfile = authorProfile

    // Live engagement counts. statsFlow re-emits when the reaction/reply-id/
    // repost/zap indexes change for THIS event;
    // distinctUntilChanged inside MES filters out signal bumps caused by
    // unrelated events. Falls back to the FeedRow snapshot when no provider
    // is wired (older surfaces, tests).
    // key(engagementId): forces collectAsState to reseed its remembered
    // State when the slot recycles to a different event. Without this,
    // the State holds the previous event's counts for one or more frames
    // after the slot diffs to a new ID.
    val liveStats = if (statsFlow != null) {
        key(model.engagementId) {
            statsFlow(model.engagementId).collectAsStateWithLifecycle().value
        }
    } else null
    val liveReplyCount    = liveStats?.replyCount    ?: row.replyCount
    val liveRepostCount   = liveStats?.repostCount   ?: row.repostCount
    val liveReactionCount = liveStats?.reactionCount ?: row.reactionCount
    val liveZapTotalSats  = liveStats?.zapTotalSats  ?: row.zapTotalSats

    // Article layout
    if (role == CardRole.Article || model.article != null) {
        ArticleLayout(
            model = model,
            row = row,
            engagement = engagement,
            replyCount = liveReplyCount,
            repostCount = liveRepostCount,
            reactionCount = liveReactionCount,
            zapTotalSats = liveZapTotalSats,
            onNoteClick = onNoteClick,
            onComment = onComment,
            onArticleClick = onArticleClick,
            onReact = onReact,
            onReactLongPress = onReactLongPress,
            pinnedEmojis = pinnedEmojis,
            onReactWithEmoji = onReactWithEmoji,
            onRepost = onRepost,
            onQuote = onQuote,
            onZap = onZap,
            onSaveNwcUri = onSaveNwcUri,
            onAuthorClick = onAuthorClick,
            statsFlow = statsFlow,
            profileFlow = profileFlow,
            lookupProfile = lookupProfile,
            zapDetailsForEvent = zapDetailsForEvent,
            repostPubkeysForEvent = repostPubkeysForEvent,
            reactionsForEvent = reactionsForEvent,
            sourceProfile = sourceProfile,
            wotLookup = wotLookup,
            feedWotDisplayMode = feedWotDisplayMode,
            role = role,
            modifier = modifier,
        )
        return
    }

    // Standard note layout
    Column(
        modifier = modifier
            .fillMaxWidth()
            // drawBehind defers the Animatable read to the draw phase — reading
            // flashAlpha.value in composition recomposed the whole card subtree
            // every animation frame.
            .drawBehind { drawRect(Color.White.copy(alpha = flashAlpha.value * 0.05f)) },
    ) {
        // Content area gestures live here so they don't intercept action-bar
        // gestures (heart long-press -> emoji picker, zap long-press -> amount
        // picker). The Initial-pass detector is not legacy cruft: it is the
        // way card-level long-press coexists with clickable children (links,
        // quotes, media). A Main-pass combinedClickable waits for unconsumed
        // child presses and never starts its timer there. Embedded QuoteCards
        // stay long-press-free to avoid nested gesture ambiguity; tapping
        // their own body still opens the quote.
        val contentInteractionSource = remember { MutableInteractionSource() }
        val longPressModifier = if (onLongPress != null) {
            Modifier.pointerInput(onLongPress) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = event.changes.firstOrNull { it.id == down.id }
                            if (ch == null || ch.changedToUp()) return@withTimeoutOrNull true
                            val dist = (ch.position - down.position).getDistance()
                            if (dist > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                        }
                        @Suppress("UNREACHABLE_CODE") true
                    }
                    if (cancelled == null) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            ev.changes.forEach { it.consume() }
                        } while (ev.changes.any { it.pressed })
                    }
                }
            }
        } else {
            Modifier
        }
        Column(
            modifier = longPressModifier.clickable(
                interactionSource = contentInteractionSource,
                indication = null,
            ) { onNoteClick(model.navigateId) },
        ) {
        // Author header. Picture/displayName/nip05 read live from authorProfile
        // (profileFlow) when present, falling back to the FeedRow snapshot for
        // first-frame stability. authorProfile reactively updates when MES
        // profile data changes — eliminates list-wide feedRows recompute on
        // every kind-0 arrival.
        // For reposts (kind-6), row.author* fields belong to the REPOSTER
        // (toFeedRow uses event.pubkey = wrapper pubkey), so skip them to
        // avoid briefly showing the reposter's identity on the inner author.
        val isRepost = model.repost != null
        val repostTimestamp = if (isRepost && role != CardRole.Thread && role != CardRole.Reply) {
            model.sourceCreatedAt
        } else {
            null
        }
        AuthorHeader(
            pubkey      = model.pubkey,
            picture     = authorProfile?.picture?.takeIf { it.isNotBlank() }
                ?: if (isRepost) null else row.authorPicture,
            displayName = authorProfile?.displayName?.takeIf { it.isNotBlank() }
                ?: authorProfile?.name?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }
                ?: (if (isRepost) null else row.displayName)
                ?: "${model.pubkey.take(6)}…${model.pubkey.takeLast(4)}",
            nip05       = authorProfile?.nip05 ?: if (isRepost) null else row.authorNip05,
            createdAt   = model.createdAt,
            onAuthorClick = onAuthorClick,
            onNoteClick = { onNoteClick(model.navigateId) },
            lookupProfile = lookupProfile,
            profileFlow   = profileFlow,
            wotLookup     = wotLookup,
            feedWotDisplayMode = feedWotDisplayMode,
            repostSourcePubkey = if (isRepost) model.sourcePubkey else null,
            repostSourceProfile = if (isRepost) sourceProfile else null,
            repostSourceCreatedAt = repostTimestamp,
            referenceRepost = model.repost?.payload is RepostPayload.ReferenceOnly,
        )

        // Thread parent card (Conversations tab)
        if (parentEvent != null) {
            ThreadParentCard(
                event               = parentEvent,
                author              = parentAuthor,
                onNoteClick         = onNoteClick,
                lookupProfile       = lookupProfile,
                profileFlow         = profileFlow,
                lookupModel         = lookupModel,
                lookupEvent         = lookupEvent,
                onAuthorClick       = onAuthorClick,
                fetchOgMetadata     = fetchOgMetadata,
                imageDimensionCache = imageDimensionCache,
                thumbnailCache      = thumbnailCache,
                exoPlayer           = exoPlayer,
                isActiveVideo       = isActiveVideo,
                activeVideoUrl      = activeVideoUrl,
                isFullscreen        = isFullscreen,
                onOpenFullscreen    = onOpenFullscreen,
                isMuted             = isMuted,
                onToggleMute        = onToggleMute,
                onVideoModelsResolved = onVideoModelsResolved,
                sensitiveMode       = sensitiveMode,
                wotLookup           = wotLookup,
                feedWotDisplayMode  = feedWotDisplayMode,
                onWotSubjectsVisible = onWotSubjectsVisible,
                modifier            = Modifier.padding(bottom = Spacing.small),
            )
        }

        // NIP-36 content warning blur/hide overlay. HIDE shows a compact
        // placeholder (feed already drops these via its filter; this covers
        // non-feed surfaces — profile/thread — and preserves thread structure).
        if (hideWhole) {
            SensitiveContentHiddenCard(
                reason = contentWarningReason,
                modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
            )
        } else
        Box {
            Column(modifier = if (showBlur) Modifier.blur(24.dp) else Modifier) {
                // Content flow — walks segments and renders primitives
                ContentFlow(
                    model               = model,
                    role                = role,
                    onNoteClick         = onNoteClick,
                    onAuthorClick       = onAuthorClick,
                    onHashtagClick      = onHashtagClick,
                    lookupProfile       = lookupProfile,
                    profileFlow         = profileFlow,
                    lookupEvent         = lookupEvent,
                    lookupEventWithAuthor = lookupEventWithAuthor,
                    lookupModel         = lookupModel,
                    fetchOgMetadata     = fetchOgMetadata,
                    hasCachedOgMetadata = hasCachedOgMetadata,
                    imageDimensionCache = imageDimensionCache,
                    isActiveVideo       = isActiveVideo,
                    activeVideoUrl      = activeVideoUrl,
                    isFullscreen        = isFullscreen,
                    onOpenFullscreen    = onOpenFullscreen,
                    exoPlayer           = exoPlayer,
                    isMuted             = isMuted,
                    onToggleMute        = onToggleMute,
                    thumbnailCache      = thumbnailCache,
                    onVideoModelsResolved = onVideoModelsResolved,
                    sensitiveMode       = sensitiveMode,
                    wotLookup           = wotLookup,
                    feedWotDisplayMode  = feedWotDisplayMode,
                    onWotSubjectsVisible = onWotSubjectsVisible,
                    knownLightningAddress = authorProfile?.lud16,
                )

                if (model.poll != null) {
                    PollCard(
                        pollId = model.engagementId,
                        pollAuthorPubkey = model.pubkey,
                        pollCreatedAt = model.createdAt,
                        poll = model.poll,
                        sourceRelay = model.relayUrl,
                        callbacks = pollActions,
                        lookupProfile = lookupProfile,
                        profileFlow = profileFlow,
                        wotLookup = wotLookup,
                        onVoterClick = onAuthorClick,
                        onWotSubjectsVisible = onWotSubjectsVisible,
                        modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                    )
                }

                // Reference-only repost fallback (empty, id-only, malformed,
                // oversized, mismatched, or bad-signature envelope). Protocol
                // JSON is never rendered; resolve the independently verified
                // target or show a stable unavailable placeholder.
                if (model.repost != null &&
                    model.repost.payload is RepostPayload.ReferenceOnly
                ) {
                    EmptyRepostBody(
                        targetId = model.repost.targetId,
                        addressCoordinate = model.repost.addressCoordinate,
                        relayHints = listOfNotNull(
                            model.repost.relayHint,
                            model.repost.addressRelayHint,
                            model.relayUrl,
                        ).distinct(),
                        targetAuthorPubkey = model.repost.targetAuthorHint,
                        proxyUrl = model.repost.proxyUrl,
                        lookupEventWithAuthor = lookupEventWithAuthor,
                        lookupEventReference = lookupEventReference,
                        lookupProfile = lookupProfile,
                        profileFlow = profileFlow,
                        lookupModel = lookupModel,
                        fetchOgMetadata = fetchOgMetadata,
                        hasCachedOgMetadata = hasCachedOgMetadata,
                        imageDimensionCache = imageDimensionCache,
                        onNoteClick = onNoteClick,
                        onAuthorClick = onAuthorClick,
                        thumbnailCache = thumbnailCache,
                        exoPlayer = exoPlayer,
                        isActiveVideo = isActiveVideo,
                        activeVideoUrl = activeVideoUrl,
                        isFullscreen = isFullscreen,
                        onOpenFullscreen = onOpenFullscreen,
                        isMuted = isMuted,
                        onToggleMute = onToggleMute,
                        onVideoModelsResolved = onVideoModelsResolved,
                        sensitiveMode = sensitiveMode,
                        wotLookup = wotLookup,
                        feedWotDisplayMode = feedWotDisplayMode,
                        onWotSubjectsVisible = onWotSubjectsVisible,
                    )
                }
            }

            // Tap-to-reveal overlay
            if (showBlur) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { revealed = true }
                        .padding(vertical = Spacing.xl)
                        .align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = contentWarningReason?.takeIf { it.isNotBlank() }
                            ?: "Sensitive content",
                        fontSize = AppType.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(Spacing.small))
                    Text(
                        text = "Tap to reveal",
                        fontSize = AppType.caption,
                        color = TextSecondary.copy(alpha = 0.6f),
                    )
                }
            }
        }
        } // end content-area Column (card-level long-press scope)

        // Action bar + inline engagement drawer
        // Optimistic: enabled while profile not yet resolved (null).
        // Disabled only when we know the author has no Lightning address.
        val zapEnabled = authorProfile == null || !authorProfile.lud16.isNullOrBlank()
        var drawerOpen by remember { mutableStateOf(false) }
        EventActionBar(
            noteId          = row.id,
            // Zap state is written under engagementId (inner note for reposts);
            // key flash/loading/optimistic sats off it so reposts animate + show
            // the optimistic amount, not just flip the bolt amber via hasZapped.
            zapTargetId     = model.engagementId,
            replyCount      = liveReplyCount,
            repostCount     = liveRepostCount,
            reactionCount   = liveReactionCount,
            zapTotalSats    = liveZapTotalSats,
            hasReacted      = engagement.hasReacted,
            hasReposted     = engagement.hasReposted,
            hasZapped       = engagement.hasZapped,
            isNwcConfigured = engagement.isNwcConfigured,
            isZapLoading    = engagement.isZapLoading,
            extraZapSats    = engagement.extraZapSats,
            zapFlash        = engagement.zapFlash,
            zapEnabled      = zapEnabled,
            drawerOpen      = drawerOpen,
            onChevronTap    = { drawerOpen = !drawerOpen },
            onNoteClick     = { onNoteClick(model.navigateId) },
            onComment          = onComment,
            onReact            = onReact,
            onReactLongPress   = onReactLongPress,
            pinnedEmojis       = pinnedEmojis,
            onReactWithEmoji   = onReactWithEmoji,
            onRepost           = onRepost,
            onQuote            = onQuote,
            onZap              = onZap,
            onSaveNwcUri       = onSaveNwcUri,
        )

        AnimatedVisibility(
            visible = drawerOpen,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            EngagementDrawer(
                eventId               = model.engagementId,
                statsFlow             = statsFlow,
                zapDetailsForEvent    = zapDetailsForEvent,
                repostPubkeysForEvent = repostPubkeysForEvent,
                reactionsForEvent     = reactionsForEvent,
                profileFlow           = profileFlow,
                lookupProfile         = lookupProfile,
                onProfileTap          = onAuthorClick,
            )
        }
    }
}

/**
 * Article card layout for kind-30023 (NIP-23 long-form).
 * Author row + hero image + title + summary + action bar.
 */
@Composable
private fun ArticleLayout(
    model: EventModel,
    row: FeedRow,
    engagement: EventEngagementSnapshot,
    replyCount: Int,
    repostCount: Int,
    reactionCount: Int,
    zapTotalSats: Long,
    onNoteClick: (String) -> Unit,
    onComment: () -> Unit = {},
    onArticleClick: (FeedRow) -> Unit,
    onReact: () -> Unit,
    onReactLongPress: () -> Unit = {},
    pinnedEmojis: List<com.unsilence.app.data.memory.CustomEmoji> = emptyList(),
    onReactWithEmoji: (com.unsilence.app.data.memory.CustomEmoji) -> Unit = {},
    onRepost: () -> Unit,
    onQuote: (String) -> Unit,
    onZap: (ZapRequest) -> Unit,
    onSaveNwcUri: (String) -> Unit,
    onAuthorClick: (String) -> Unit = {},
    statsFlow: ((String) -> StateFlow<com.unsilence.app.data.memory.EventStats>)? = null,
    profileFlow: ((String) -> StateFlow<UserEntity?>)? = null,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    zapDetailsForEvent: ((String) -> List<com.unsilence.app.data.memory.ZapDetail>)? = null,
    repostPubkeysForEvent: ((String) -> List<String>)? = null,
    reactionsForEvent: ((String) -> List<com.unsilence.app.data.memory.ReactionInfo>)? = null,
    sourceProfile: UserEntity? = null,
    wotLookup: ((String) -> WotLookup?)? = null,
    feedWotDisplayMode: FeedWotDisplayMode = FeedWotDisplayMode.NUMBERS,
    role: CardRole = CardRole.Article,
    modifier: Modifier = Modifier,
) {
    val article = model.article

    // Resolve the EFFECTIVE author reactively (model.pubkey = inner author for a
    // 6/16 repost). For reposts the FeedRow author.* fields belong to the REPOSTER
    // (toFeedRow uses the wrapper pubkey), so skip them — same rule as the note
    // path (see the standard-layout AuthorHeader) so a reposted article never shows
    // the reposter's identity.
    val isRepost = model.repost != null
    val articleAuthorProfile = collectProfileAsState(model.pubkey, profileFlow)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small)
            .clickable { onArticleClick(row) },
    ) {
        // Author row
        val repostTimestamp = if (isRepost && role != CardRole.Thread && role != CardRole.Reply) {
            model.sourceCreatedAt
        } else {
            null
        }
        AuthorHeader(
            pubkey      = model.pubkey,
            picture     = articleAuthorProfile?.picture?.takeIf { it.isNotBlank() }
                ?: if (isRepost) null else row.authorPicture,
            displayName = articleAuthorProfile?.displayName?.takeIf { it.isNotBlank() }
                ?: articleAuthorProfile?.name?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }
                ?: (if (isRepost) null else row.displayName)
                ?: "${model.pubkey.take(6)}…${model.pubkey.takeLast(4)}",
            nip05       = articleAuthorProfile?.nip05 ?: if (isRepost) null else row.authorNip05,
            createdAt   = model.createdAt,
            onAuthorClick = onAuthorClick,
            onNoteClick = { onArticleClick(row) },
            lookupProfile = lookupProfile,
            profileFlow   = profileFlow,
            wotLookup     = wotLookup,
            feedWotDisplayMode = feedWotDisplayMode,
            repostSourcePubkey = if (isRepost) model.sourcePubkey else null,
            repostSourceProfile = if (isRepost) sourceProfile else null,
            repostSourceCreatedAt = repostTimestamp,
        )

        // Card body (grey background with rounded corners)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Sizing.mediaCornerRadius))
                .background(SurfaceVariant),
        ) {
            // Banner image (16:9)
            if (!article?.image.isNullOrBlank()) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                ) {
                    AsyncImage(
                        model              = rememberWidthImageRequest(article.image, maxWidth, aspectRatio = 16f / 9f),
                        contentDescription = null,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.fillMaxSize(),
                    )
                }
            }

            // Title
            if (!article?.title.isNullOrBlank()) {
                Text(
                    text       = article.title,
                    color      = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize   = AppType.subheading,
                    lineHeight = 22.sp,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium)
                        .padding(top = Spacing.small, bottom = Spacing.micro),
                )
            }

            // Summary
            val summary = article?.summary
                ?: model.displayContent.take(150).replace('\n', ' ').ifBlank { null }
            if (!summary.isNullOrBlank()) {
                Text(
                    text     = summary,
                    color    = TextSecondary,
                    fontSize = AppType.body,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium)
                        .padding(bottom = Spacing.small),
                )
            }

            // Action bar + inline engagement drawer
            var drawerOpen by remember { mutableStateOf(false) }
            EventActionBar(
                // navigateId targets the article itself (inner event for a repost);
                // EventActionBar keys the Quote action off noteId.
                noteId          = model.navigateId,
                zapTargetId     = model.engagementId,
                replyCount      = replyCount,
                repostCount     = repostCount,
                reactionCount   = reactionCount,
                zapTotalSats    = zapTotalSats,
                hasReacted      = engagement.hasReacted,
                hasReposted     = engagement.hasReposted,
                hasZapped       = engagement.hasZapped,
                isNwcConfigured = engagement.isNwcConfigured,
                isZapLoading    = engagement.isZapLoading,
                extraZapSats    = engagement.extraZapSats,
                zapFlash        = engagement.zapFlash,
                drawerOpen      = drawerOpen,
                onChevronTap    = { drawerOpen = !drawerOpen },
                onNoteClick     = { onNoteClick(row.id) },
                onComment          = onComment,
                onReact            = onReact,
                onReactLongPress   = onReactLongPress,
                pinnedEmojis       = pinnedEmojis,
                onReactWithEmoji   = onReactWithEmoji,
                onRepost           = onRepost,
                onQuote            = onQuote,
                onZap              = onZap,
                onSaveNwcUri       = onSaveNwcUri,
            )

            AnimatedVisibility(
                visible = drawerOpen,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                EngagementDrawer(
                    eventId               = model.engagementId,
                    statsFlow             = statsFlow,
                    zapDetailsForEvent    = zapDetailsForEvent,
                    repostPubkeysForEvent = repostPubkeysForEvent,
                    reactionsForEvent     = reactionsForEvent,
                    profileFlow           = profileFlow,
                    lookupProfile         = lookupProfile,
                    onProfileTap          = onAuthorClick,
                )
            }
        }
    }
}
