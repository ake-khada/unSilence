package com.unsilence.app.ui.feed

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.SubcomposeAsyncImage
import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.ui.common.rememberFullWidthImageRequest
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.shared.EngagementSnapshot
import com.unsilence.app.ui.shared.ThreadParentCard
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.SurfaceVariant
import com.unsilence.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

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
    engagement: EngagementSnapshot,
    onNoteClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    onQuote: (String) -> Unit,
    onArticleClick: (FeedRow) -> Unit,
    onReact: () -> Unit,
    onRepost: () -> Unit,
    onZap: (Long) -> Unit,
    onSaveNwcUri: (String) -> Unit,
    lookupProfile: (suspend (String) -> UserEntity?)?,
    lookupEvent: (suspend (String, List<String>) -> EventEntity?)?,
    lookupEventWithAuthor: (suspend (String, List<String>, String?) -> EventEntity?)? = null,
    lookupModel: ((String) -> EventModel?)? = null,
    fetchOgMetadata: (suspend (String) -> OgMetadata?)?,
    profileFlow: ((String) -> StateFlow<UserEntity?>)?,
    imageDimensionCache: ImageDimensionCache?,
    thumbnailCache: VideoThumbnailCache?,
    // Video
    exoPlayer: ExoPlayer? = null,
    isMuted: Boolean = true,
    onToggleMute: () -> Unit = {},
    isActiveVideo: Boolean = false,
    isFullscreen: Boolean = false,
    onOpenFullscreen: () -> Unit = {},
    // New-post animation
    isNewPost: Boolean = false,
    onNewPostAnimated: () -> Unit = {},
    // Thread parent (Conversations tab)
    parentEvent: EventEntity? = null,
    parentAuthor: UserEntity? = null,
    modifier: Modifier = Modifier,
) {
    // New-post flash animation
    val flashAlpha = remember { Animatable(if (isNewPost) 1f else 0f) }
    LaunchedEffect(isNewPost) {
        if (isNewPost) {
            flashAlpha.snapTo(1f)
            flashAlpha.animateTo(0f, tween(durationMillis = 1000))
            onNewPostAnimated()
        }
    }

    // Resolve source profile for repost header (kind-6 wrapper author).
    val sourceProfile = if (model.repost != null && profileFlow != null) {
        profileFlow(model.sourcePubkey).collectAsState().value
    } else null

    // Fallback profile fetch for reposts when the source's profile hasn't
    // arrived yet — the existing AvatarImage `lookupProfile` debounce covers
    // most cases; this LaunchedEffect catches the long tail.
    if (model.repost != null && sourceProfile == null && lookupProfile != null) {
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
    val authorProfile = if (profileFlow != null) {
        profileFlow(model.pubkey).collectAsState().value
    } else null

    // For repost cards, model.pubkey is the inner (effective) author and
    // model.sourcePubkey is the wrapper author. authorProfile reactively
    // resolves the inner author for both cases.
    val effectiveProfile = authorProfile

    // Article layout
    if (role == CardRole.Article || model.article != null) {
        ArticleLayout(
            model = model,
            row = row,
            engagement = engagement,
            onNoteClick = onNoteClick,
            onArticleClick = onArticleClick,
            onReact = onReact,
            onRepost = onRepost,
            onQuote = onQuote,
            onZap = onZap,
            onSaveNwcUri = onSaveNwcUri,
            modifier = modifier,
        )
        return
    }

    // Standard note layout
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = flashAlpha.value * 0.05f))
            .clickable { onNoteClick(model.navigateId) },
    ) {
        // Repost header (kind 6 only)
        if (model.repost != null) {
            RepostHeader(
                sourcePubkey    = model.sourcePubkey,
                sourceCreatedAt = model.sourceCreatedAt,
                sourceProfile   = sourceProfile,
                onClick         = { onNoteClick(model.navigateId) },
            )
        }

        // Author header. Picture/displayName/nip05 read live from authorProfile
        // (profileFlow) when present, falling back to the FeedRow snapshot for
        // first-frame stability. authorProfile reactively updates when MES
        // profile data changes — eliminates list-wide feedRows recompute on
        // every kind-0 arrival.
        AuthorHeader(
            pubkey      = model.pubkey,
            picture     = authorProfile?.picture ?: row.authorPicture,
            displayName = authorProfile?.displayName?.takeIf { it.isNotBlank() }
                ?: authorProfile?.name?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }
                ?: row.displayName
                ?: "${model.pubkey.take(6)}…${model.pubkey.takeLast(4)}",
            nip05       = authorProfile?.nip05 ?: row.authorNip05,
            createdAt   = model.createdAt,
            onAuthorClick = onAuthorClick,
            onNoteClick = { onNoteClick(model.navigateId) },
            lookupProfile = lookupProfile,
        )

        // Thread parent card (Conversations tab)
        if (parentEvent != null) {
            ThreadParentCard(
                event               = parentEvent,
                author              = parentAuthor,
                onNoteClick         = onNoteClick,
                lookupProfile       = lookupProfile,
                onAuthorClick       = onAuthorClick,
                fetchOgMetadata     = fetchOgMetadata,
                imageDimensionCache = imageDimensionCache,
                modifier            = Modifier.padding(bottom = Spacing.small),
            )
        }

        // Content flow — walks segments and renders primitives
        ContentFlow(
            model               = model,
            role                = role,
            onNoteClick         = onNoteClick,
            onAuthorClick       = onAuthorClick,
            lookupProfile       = lookupProfile,
            lookupEvent         = lookupEvent,
            lookupModel         = lookupModel,
            fetchOgMetadata     = fetchOgMetadata,
            imageDimensionCache = imageDimensionCache,
            isActiveVideo       = isActiveVideo,
            isFullscreen        = isFullscreen,
            onOpenFullscreen    = onOpenFullscreen,
            exoPlayer           = exoPlayer,
            isMuted             = isMuted,
            onToggleMute        = onToggleMute,
            thumbnailCache      = thumbnailCache,
        )

        // Empty-content repost fallback (mostr.pub bridge style):
        // kind-6 with empty wrapper content + targetId — render the target inline.
        // Routes via target author's outbox relays for bridge content.
        if (model.repost != null &&
            model.repost.embeddedJson == null &&
            model.repost.targetId != null &&
            lookupEventWithAuthor != null
        ) {
            EmptyRepostBody(
                targetId = model.repost.targetId,
                relayHints = listOfNotNull(model.repost.relayHint),
                targetAuthorPubkey = model.repost.targetAuthorPubkey,
                lookupEventWithAuthor = lookupEventWithAuthor,
                lookupProfile = lookupProfile,
                lookupModel = lookupModel,
                fetchOgMetadata = fetchOgMetadata,
                imageDimensionCache = imageDimensionCache,
                onNoteClick = onNoteClick,
                onAuthorClick = onAuthorClick,
            )
        }

        // Action bar
        EventActionBar(
            noteId          = row.id,
            replyCount      = row.replyCount,
            repostCount     = row.repostCount,
            reactionCount   = row.reactionCount,
            zapTotalSats    = row.zapTotalSats,
            hasReacted      = model.engagementId in engagement.reactedIds,
            hasReposted     = model.engagementId in engagement.repostedIds,
            hasZapped       = model.engagementId in engagement.zappedIds,
            isNwcConfigured = engagement.isNwcConfigured,
            isZapLoading    = row.id in engagement.zapLoadingIds,
            extraZapSats    = engagement.optimisticZapSats[row.id] ?: 0L,
            zapFlash        = engagement.zapFlash,
            onNoteClick     = { onNoteClick(model.navigateId) },
            onReact         = onReact,
            onRepost        = onRepost,
            onQuote         = onQuote,
            onZap           = onZap,
            onSaveNwcUri    = onSaveNwcUri,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)
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
    engagement: EngagementSnapshot,
    onNoteClick: (String) -> Unit,
    onArticleClick: (FeedRow) -> Unit,
    onReact: () -> Unit,
    onRepost: () -> Unit,
    onQuote: (String) -> Unit,
    onZap: (Long) -> Unit,
    onSaveNwcUri: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val article = model.article
    val authorLabel = row.displayName ?: "${row.pubkey.take(6)}…${row.pubkey.takeLast(4)}"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small)
            .clickable { onArticleClick(row) },
    ) {
        // Author row
        AuthorHeader(
            pubkey      = model.pubkey,
            picture     = row.authorPicture,
            displayName = authorLabel,
            nip05       = row.authorNip05,
            createdAt   = model.createdAt,
            onAuthorClick = {},
            onNoteClick = { onArticleClick(row) },
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
                SubcomposeAsyncImage(
                    model              = rememberFullWidthImageRequest(article.image, aspectRatio = 16f / 9f),
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                )
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
                ?: row.content.take(150).replace('\n', ' ').ifBlank { null }
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

            // Action bar
            EventActionBar(
                noteId          = row.id,
                replyCount      = row.replyCount,
                repostCount     = row.repostCount,
                reactionCount   = row.reactionCount,
                zapTotalSats    = row.zapTotalSats,
                hasReacted      = model.engagementId in engagement.reactedIds,
                hasReposted     = model.engagementId in engagement.repostedIds,
                hasZapped       = model.engagementId in engagement.zappedIds,
                isNwcConfigured = engagement.isNwcConfigured,
                isZapLoading    = row.id in engagement.zapLoadingIds,
                extraZapSats    = engagement.optimisticZapSats[row.id] ?: 0L,
                zapFlash        = engagement.zapFlash,
                onNoteClick     = { onNoteClick(row.id) },
                onReact         = onReact,
                onRepost        = onRepost,
                onQuote         = onQuote,
                onZap           = onZap,
                onSaveNwcUri    = onSaveNwcUri,
            )
        }
    }
}
