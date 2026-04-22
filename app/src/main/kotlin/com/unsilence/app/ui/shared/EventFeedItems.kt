package com.unsilence.app.ui.shared

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import coil3.compose.SubcomposeAsyncImage
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.data.relay.extractRepostAuthorPubkey
import com.unsilence.app.ui.common.rememberFullWidthImageRequest
import com.unsilence.app.ui.feed.ArticleCard
import com.unsilence.app.ui.feed.AvatarImage
import com.unsilence.app.ui.feed.IMAGE_URL_REGEX
import com.unsilence.app.ui.feed.LINK_URL_REGEX
import com.unsilence.app.ui.feed.LinkPreviewCard
import com.unsilence.app.ui.feed.NoteCard
import com.unsilence.app.ui.feed.NostrRichText
import com.unsilence.app.ui.feed.VIDEO_URL_REGEX
import com.unsilence.app.ui.feed.ImageDimensionCache
import com.unsilence.app.ui.feed.feedImageAspectRatio
import com.unsilence.app.ui.feed.VideoThumbnailCache
import com.unsilence.app.ui.feed.engagementId
import com.unsilence.app.ui.feed.looksLikeHexPubkey
import com.unsilence.app.ui.feed.relativeTime
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.feed.NoteActionsViewModel
import kotlinx.coroutines.flow.StateFlow

// Stable empty-collection singletons — Compose sees the same reference across recompositions
private val EMPTY_IMETA_DIMS: Map<String, Float> = emptyMap()
private val EMPTY_VIDEO_MODELS: List<com.unsilence.app.data.model.VideoRenderModel> = emptyList()

/**
 * Parameters bundle for engagement actions — avoids 15-parameter lambda pollution.
 */
@androidx.compose.runtime.Stable
data class EventActionCallbacks(
    val onNoteClick: (String) -> Unit = {},
    val onAuthorClick: (pubkey: String) -> Unit = {},
    val onQuote: (String) -> Unit = {},
    val onArticleClick: (FeedRow) -> Unit = {},
    val react: (eventId: String, pubkey: String) -> Unit = { _, _ -> },
    val repost: (eventId: String, pubkey: String, relayUrl: String) -> Unit = { _, _, _ -> },
    val zap: (eventId: String, pubkey: String, relayUrl: String, amount: Long) -> Unit = { _, _, _, _ -> },
    val saveNwcUri: (String) -> Unit = {},
    val lookupProfile: (suspend (String) -> UserEntity?)? = null,
    val lookupEvent: (suspend (String, List<String>) -> EventEntity?)? = null,
    val fetchOgMetadata: (suspend (String) -> OgMetadata?)? = null,
    val profileFlow: ((String) -> StateFlow<UserEntity?>)? = null,
)

/**
 * Engagement state snapshot — avoids re-collecting in every item.
 */
@androidx.compose.runtime.Immutable
data class EngagementSnapshot(
    val reactedIds: Set<String> = emptySet(),
    val repostedIds: Set<String> = emptySet(),
    val zappedIds: Set<String> = emptySet(),
    val isNwcConfigured: Boolean = false,
    val zapLoadingIds: Set<String> = emptySet(),
    val optimisticZapSats: Map<String, Long> = emptyMap(),
    val zapFlash: NoteActionsViewModel.ZapFlashState? = null,
)

/**
 * Shared LazyListScope extension that renders a list of FeedRow items
 * using the unified NoteCard / ArticleCard pipeline.
 *
 * [showThreadParents] — when true (Conversations tab), replies are grouped
 * with a compact parent note card above them, connected by a vertical line.
 * Parent notes are fetched via [EventActionCallbacks.lookupEvent] (Room + relay).
 */
fun LazyListScope.eventFeedItems(
    events: List<FeedRow>,
    engagement: EngagementSnapshot,
    callbacks: EventActionCallbacks,
    videoScope: VideoPlaybackScope? = null,
    context: RenderContext = RenderContext.Feed,
    newEventIds: Set<String> = emptySet(),
    onNewPostAnimated: (String) -> Unit = {},
    thumbnailCache: VideoThumbnailCache? = null,
    imageDimensionCache: ImageDimensionCache? = null,
    imetaImageDimsProvider: ((String) -> Map<String, Float>)? = null,
    showThreadParents: Boolean = false,
) {
    items(
        items = events,
        key = { it.id },
        contentType = { if (it.kind == 30023) "article" else "note" },
    ) { row ->
        // replyToId is the direct parent; rootId is the thread root.
        // Fall back to rootId when replyToId is null (direct reply to root).
        val parentId = row.replyToId ?: row.rootId
        if (showThreadParents && parentId != null) {
            ThreadedReplyItem(
                parentId = parentId,
                replyRow = row,
                engagement = engagement,
                callbacks = callbacks,
                videoScope = videoScope,
                context = context,
                isNewPost = row.id in newEventIds,
                onNewPostAnimated = { onNewPostAnimated(row.id) },
                thumbnailCache = thumbnailCache,
                imageDimensionCache = imageDimensionCache,
                imetaImageDims = imetaImageDimsProvider?.invoke(row.id) ?: EMPTY_IMETA_DIMS,
            )
        } else {
            EventFeedItem(
                row = row,
                engagement = engagement,
                callbacks = callbacks,
                videoScope = videoScope,
                context = context,
                isNewPost = row.id in newEventIds,
                onNewPostAnimated = { onNewPostAnimated(row.id) },
                thumbnailCache = thumbnailCache,
                imageDimensionCache = imageDimensionCache,
                imetaImageDims = imetaImageDimsProvider?.invoke(row.id) ?: EMPTY_IMETA_DIMS,
            )
        }
    }
}

/**
 * A reply with its parent note fetched via lookupEvent and embedded
 * inside the reply card (between header and content), like quoted notes.
 */
@Composable
private fun ThreadedReplyItem(
    parentId: String,
    replyRow: FeedRow,
    engagement: EngagementSnapshot,
    callbacks: EventActionCallbacks,
    videoScope: VideoPlaybackScope?,
    context: RenderContext,
    isNewPost: Boolean,
    onNewPostAnimated: () -> Unit,
    thumbnailCache: VideoThumbnailCache? = null,
    imageDimensionCache: ImageDimensionCache? = null,
    imetaImageDims: Map<String, Float> = EMPTY_IMETA_DIMS,
) {
    // Two-phase parent lookup: MemoryEventStore first, then relay fetch (5s wait).
    // Pass the reply's source relay as a hint — the parent event is most likely
    // on the same relay. Without this hint, fetchEventById tries 3 random relays
    // which may not have the parent (especially for non-indexed content).
    val parentEvent by produceState<EventEntity?>(null, parentId) {
        if (callbacks.lookupEvent != null) {
            value = callbacks.lookupEvent.invoke(parentId, listOf(replyRow.relayUrl))
        }
    }
    val parentAuthor by produceState<UserEntity?>(null, parentEvent?.pubkey) {
        val pk = parentEvent?.pubkey
        if (pk != null && callbacks.lookupProfile != null) {
            value = callbacks.lookupProfile.invoke(pk)
        }
    }

    // ── DIAGNOSTIC: log when thread parent doesn't resolve ───────────────
    LaunchedEffect(parentId, parentEvent) {
        if (parentEvent == null) {
            kotlinx.coroutines.delay(6000) // Wait past lookupEvent's 5s timeout
            android.util.Log.w("CardHydrator", "Outbox final: refId=${parentId.take(12)} exists=false " +
                "kind=null author=null relayUrl=null contentLen=0 " +
                "referencedBy=${replyRow.id.take(12)} referencedByKind=${replyRow.kind} " +
                "phase=unresolved")
        }
    }

    // Single card — parent is embedded inside the reply NoteCard
    EventFeedItem(
        row = replyRow,
        engagement = engagement,
        callbacks = callbacks,
        videoScope = videoScope,
        context = context,
        isNewPost = isNewPost,
        onNewPostAnimated = onNewPostAnimated,
        thumbnailCache = thumbnailCache,
        imageDimensionCache = imageDimensionCache,
        imetaImageDims = imetaImageDims,
        parentEvent = parentEvent,
        parentAuthor = parentAuthor,
    )
}

/**
 * Compact parent note card — shows author, timestamp, content with media.
 * Extracts image URLs and link previews from content so they render
 * instead of showing as raw text. No action bar.
 * Clickable to navigate to the parent note.
 */
@Composable
internal fun ThreadParentCard(
    event: EventEntity,
    author: UserEntity?,
    onNoteClick: (String) -> Unit,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    onAuthorClick: (String) -> Unit = {},
    fetchOgMetadata: (suspend (String) -> OgMetadata?)? = null,
    imageDimensionCache: ImageDimensionCache? = null,
    modifier: Modifier = Modifier,
) {
    // Extract media URLs from content
    val content = event.content.trim()
    val mediaExtraction = remember(event.id) {
        val imageUrls = IMAGE_URL_REGEX.findAll(content).map { it.value }.distinct().toList()
        val afterImages = IMAGE_URL_REGEX.replace(content, "")
        val videoUrls = VIDEO_URL_REGEX.findAll(afterImages).map { it.value }.distinct().toList()
        val afterVideos = VIDEO_URL_REGEX.replace(afterImages, "")
        val linkUrls = LINK_URL_REGEX.findAll(afterVideos).map { it.value }.distinct().take(1).toList()
        val textContent = LINK_URL_REGEX.replace(afterVideos, "").trim()
        Triple(imageUrls, linkUrls, textContent)
    }
    val (imageUrls, linkUrls, textContent) = mediaExtraction

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .border(
                width = 0.5.dp,
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(12.dp),
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable { onNoteClick(event.id) }
            .padding(12.dp),
    ) {
        // Compact header: avatar + name + timestamp
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarImage(
                pubkey = event.pubkey,
                picture = author?.picture,
                modifier = Modifier.size(24.dp),
                sizeDp = 24.dp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = author?.displayName?.takeIf { it.isNotBlank() }
                    ?: author?.name?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }
                    ?: "${event.pubkey.take(6)}…${event.pubkey.takeLast(4)}",
                color = Color.White.copy(alpha = 0.7f),
                fontWeight = FontWeight.SemiBold,
                fontSize = AppType.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = relativeTime(event.createdAt),
                color = Color.White.copy(alpha = 0.4f),
                fontSize = AppType.bodySmall,
            )
        }
        if (textContent.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            NostrRichText(
                content       = textContent,
                lookupProfile = lookupProfile,
                onAuthorClick = onAuthorClick,
                onTextClick   = { onNoteClick(event.id) },
                maxLines      = 3,
                overflow      = TextOverflow.Ellipsis,
            )
        }
        // Render images extracted from content — collapse on error (no gap)
        if (imageUrls.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.small))
            imageUrls.take(2).forEach { url ->
                val cachedAspect = imageDimensionCache?.getCached(url)
                val aspect = feedImageAspectRatio(cachedAspect)
                SubcomposeAsyncImage(
                    model = rememberFullWidthImageRequest(url, aspectRatio = aspect),
                    contentDescription = null,
                    error = { /* collapse — no gap */ },
                    success = {
                        Image(
                            painter = painter,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(aspect, matchHeightConstraintsFirst = false)
                                .clip(RoundedCornerShape(Sizing.mediaCornerRadius)),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect, matchHeightConstraintsFirst = false),
                )
            }
        }
        // OG link preview for first non-media URL
        if (linkUrls.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.small))
            LinkPreviewCard(url = linkUrls.first(), fetchOgMetadata = fetchOgMetadata)
        }
    }
}

@Composable
private fun EventFeedItem(
    row: FeedRow,
    engagement: EngagementSnapshot,
    callbacks: EventActionCallbacks,
    videoScope: VideoPlaybackScope?,
    context: RenderContext,
    isNewPost: Boolean,
    onNewPostAnimated: () -> Unit,
    thumbnailCache: VideoThumbnailCache? = null,
    imageDimensionCache: ImageDimensionCache? = null,
    imetaImageDims: Map<String, Float> = EMPTY_IMETA_DIMS,
    parentEvent: EventEntity? = null,
    parentAuthor: UserEntity? = null,
) {
    // ── Remembered lambdas — stable across recompositions ─────────────────
    val onReact = remember(row.id, row.pubkey) {
        { callbacks.react(row.id, row.pubkey) }
    }
    val onRepost = remember(row.id, row.pubkey, row.relayUrl) {
        { callbacks.repost(row.id, row.pubkey, row.relayUrl) }
    }
    val onZap: (Long) -> Unit = remember(row.id, row.pubkey, row.relayUrl) {
        { amt: Long -> callbacks.zap(row.id, row.pubkey, row.relayUrl, amt) }
    }
    val onToggleMute = remember(videoScope) {
        { videoScope?.toggleMute(); Unit }
    }
    val onOpenFullscreen = remember(videoScope, row.id) {
        { videoScope?.openFullscreen(row.id); Unit }
    }
    val onArticleClick = remember(row) {
        { callbacks.onArticleClick(row) }
    }
    val onNewPostAnimatedCb = remember(row.id) {
        { onNewPostAnimated() }
    }

    if (row.kind == 30023) {
        ArticleCard(
            row = row,
            onClick = onArticleClick,
            onNoteClick = callbacks.onNoteClick,
            onReact = onReact,
            onRepost = onRepost,
            onQuote = callbacks.onQuote,
            onZap = onZap,
            onSaveNwcUri = callbacks.saveNwcUri,
            hasReacted = row.engagementId in engagement.reactedIds,
            hasReposted = row.engagementId in engagement.repostedIds,
            hasZapped = row.engagementId in engagement.zappedIds,
            isNwcConfigured = engagement.isNwcConfigured,
            isZapLoading = row.id in engagement.zapLoadingIds,
            extraZapSats = engagement.optimisticZapSats[row.id] ?: 0L,
            zapFlash = engagement.zapFlash,
        )
    } else {
        // Resolve original author profile for kind-6 reposts
        val repostAuthorPubkey = if (row.kind == 6) extractRepostAuthorPubkey(row.content, row.tags) else null
        val originalAuthorProfile = if (repostAuthorPubkey != null && callbacks.profileFlow != null) {
            callbacks.profileFlow.invoke(repostAuthorPubkey).collectAsState().value
        } else null

        // Fallback: if profile flow returned null, kick off a one-shot relay fetch
        if (repostAuthorPubkey != null && originalAuthorProfile == null && callbacks.lookupProfile != null) {
            LaunchedEffect(repostAuthorPubkey) {
                delay(1500)
                callbacks.lookupProfile.invoke(repostAuthorPubkey)
            }
        }

        val showVideo = videoScope != null &&
            context in setOf(RenderContext.Feed, RenderContext.Profile)

        NoteCard(
            row = row,
            onNoteClick = callbacks.onNoteClick,
            onAuthorClick = callbacks.onAuthorClick,
            hasReacted = row.engagementId in engagement.reactedIds,
            hasReposted = row.engagementId in engagement.repostedIds,
            hasZapped = row.engagementId in engagement.zappedIds,
            isNwcConfigured = engagement.isNwcConfigured,
            originalAuthorProfile = originalAuthorProfile,
            onReact = onReact,
            onRepost = onRepost,
            onQuote = callbacks.onQuote,
            onZap = onZap,
            onSaveNwcUri = callbacks.saveNwcUri,
            exoPlayer = if (showVideo) videoScope.exoPlayer else null,
            isMuted = videoScope?.isMuted ?: true,
            onToggleMute = onToggleMute,
            isActiveVideo = showVideo && videoScope.isActiveVideo(row.id),
            isFullscreen = videoScope?.showFullscreenVideo ?: false,
            onOpenFullscreen = onOpenFullscreen,
            videoRenderModels = if (showVideo) videoScope.videoRenderModels[row.id] ?: EMPTY_VIDEO_MODELS else EMPTY_VIDEO_MODELS,
            thumbnailCache = thumbnailCache,
            imageDimensionCache = imageDimensionCache,
            imetaImageDims = imetaImageDims,
            lookupProfile = callbacks.lookupProfile,
            lookupEvent = callbacks.lookupEvent,
            fetchOgMetadata = callbacks.fetchOgMetadata,
            isNewPost = isNewPost,
            onNewPostAnimated = onNewPostAnimatedCb,
            parentEvent = parentEvent,
            parentAuthor = parentAuthor,
            isZapLoading = row.id in engagement.zapLoadingIds,
            extraZapSats = engagement.optimisticZapSats[row.id] ?: 0L,
            zapFlash = engagement.zapFlash,
        )
    }
}
