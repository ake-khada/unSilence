package com.unsilence.app.ui.shared

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.entity.EventEntity
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.data.relay.extractRepostAuthorPubkey
import com.unsilence.app.ui.feed.ArticleCard
import com.unsilence.app.ui.feed.NoteCard
import com.unsilence.app.ui.feed.VideoThumbnailCache
import com.unsilence.app.ui.feed.engagementId
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
    val lookupEvent: (suspend (String) -> EventEntity?)? = null,
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
)

/**
 * Shared LazyListScope extension that renders a list of FeedRow items
 * using the unified NoteCard / ArticleCard pipeline.
 *
 * Eliminates the duplicated items block across Feed, Profile,
 * UserProfile, and Search screens.
 *
 * [videoScope] is optional — pass null for screens without inline video
 * (Thread, Search).
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
) {
    items(
        items = events,
        key = { it.id },
    ) { row ->
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
        )
    } else {
        // Resolve original author profile for kind-6 reposts
        val originalAuthorProfile = if (row.kind == 6 && callbacks.profileFlow != null) {
            extractRepostAuthorPubkey(row.content, row.tags)
                ?.let { callbacks.profileFlow.invoke(it).collectAsState().value }
        } else null

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
            onOpenFullscreen = { videoScope?.openFullscreen(row.id) },
            videoRenderModels = if (showVideo) videoScope.videoRenderModels[row.id].orEmpty() else emptyList(),
            thumbnailCache = thumbnailCache,
            lookupProfile = callbacks.lookupProfile,
            lookupEvent = callbacks.lookupEvent,
            fetchOgMetadata = callbacks.fetchOgMetadata,
            isNewPost = isNewPost,
            onNewPostAnimated = onNewPostAnimated,
        )
    }
}
