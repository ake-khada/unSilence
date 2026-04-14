package com.unsilence.app.ui.shared

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.entity.EventEntity
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.data.relay.extractRepostAuthorPubkey
import com.unsilence.app.ui.feed.ArticleCard
import com.unsilence.app.ui.feed.AvatarImage
import com.unsilence.app.ui.feed.NoteCard
import com.unsilence.app.ui.feed.VideoThumbnailCache
import com.unsilence.app.ui.feed.engagementId
import com.unsilence.app.ui.feed.relativeTime
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.feed.NoteActionsViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Parameters bundle for engagement actions — avoids 15-parameter lambda pollution.
 */
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
) {
    // Two-phase parent lookup: Room first, then relay fetch (lookupEvent does both + 5s wait)
    val parentEvent by produceState<EventEntity?>(null, parentId) {
        if (callbacks.lookupEvent != null) {
            value = callbacks.lookupEvent.invoke(parentId, emptyList())
        }
    }
    val parentAuthor by produceState<UserEntity?>(null, parentEvent?.pubkey) {
        val pk = parentEvent?.pubkey
        if (pk != null && callbacks.lookupProfile != null) {
            value = callbacks.lookupProfile.invoke(pk)
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
        parentEvent = parentEvent,
        parentAuthor = parentAuthor,
    )
}

/**
 * Compact parent note card — shows author, timestamp, and truncated content.
 * No action bar. Clickable to navigate to the parent note.
 * Uses EventEntity + UserEntity (fetched via lookupEvent/lookupProfile).
 */
@Composable
internal fun ThreadParentCard(
    event: EventEntity,
    author: UserEntity?,
    onNoteClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    ?: author?.name?.takeIf { it.isNotBlank() }
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
        if (event.content.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = event.content.trim(),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = AppType.body,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
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
    parentEvent: EventEntity? = null,
    parentAuthor: UserEntity? = null,
) {
    if (row.kind == 30023) {
        ArticleCard(
            row = row,
            onClick = { callbacks.onArticleClick(row) },
            onNoteClick = callbacks.onNoteClick,
            onReact = { callbacks.react(row.id, row.pubkey) },
            onRepost = { callbacks.repost(row.id, row.pubkey, row.relayUrl) },
            onQuote = callbacks.onQuote,
            onZap = { amt -> callbacks.zap(row.id, row.pubkey, row.relayUrl, amt) },
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
            onReact = { callbacks.react(row.id, row.pubkey) },
            onRepost = { callbacks.repost(row.id, row.pubkey, row.relayUrl) },
            onQuote = callbacks.onQuote,
            onZap = { amt -> callbacks.zap(row.id, row.pubkey, row.relayUrl, amt) },
            onSaveNwcUri = callbacks.saveNwcUri,
            exoPlayer = if (showVideo) videoScope.exoPlayer else null,
            isMuted = videoScope?.isMuted ?: true,
            onToggleMute = { videoScope?.toggleMute() },
            isActiveVideo = showVideo && videoScope.isActiveVideo(row.id),
            isFullscreen = videoScope?.showFullscreenVideo ?: false,
            onOpenFullscreen = { videoScope?.openFullscreen(row.id) },
            videoRenderModels = if (showVideo) videoScope.videoRenderModels[row.id].orEmpty() else emptyList(),
            thumbnailCache = thumbnailCache,
            lookupProfile = callbacks.lookupProfile,
            lookupEvent = callbacks.lookupEvent,
            fetchOgMetadata = callbacks.fetchOgMetadata,
            isNewPost = isNewPost,
            onNewPostAnimated = onNewPostAnimated,
            parentEvent = parentEvent,
            parentAuthor = parentAuthor,
            isZapLoading = row.id in engagement.zapLoadingIds,
            extraZapSats = engagement.optimisticZapSats[row.id] ?: 0L,
            zapFlash = engagement.zapFlash,
        )
    }
}
