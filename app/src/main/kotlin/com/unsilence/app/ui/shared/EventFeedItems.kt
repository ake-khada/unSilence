package com.unsilence.app.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
import com.unsilence.app.ui.feed.displayName
import com.unsilence.app.ui.feed.engagementId
import com.unsilence.app.ui.feed.relativeTime
import com.unsilence.app.ui.theme.TextSecondary
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
 *
 * [showThreadParents] — when true (Conversations tab), replies are grouped
 * with a compact parent note card above them, connected by a vertical line.
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
    if (!showThreadParents) {
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
    } else {
        // Conversations mode: group replies with parent context cards.
        // Build lookup of events by ID for quick parent resolution.
        val eventMap = events.associateBy { it.id }
        // IDs that appear as replyToId targets of other events in this list
        val replyTargetIds = events.mapNotNull { it.replyToId }.toSet()
        // "Context-only" parents: rows that are in the list solely because they
        // are reply targets, not because they are replies themselves.
        val contextOnlyIds = replyTargetIds.filter { id ->
            val row = eventMap[id] ?: return@filter false
            row.replyToId == null && row.rootId == null && row.kind != 6
        }.toSet()

        // Render: skip context-only parents as standalone items; they appear
        // as compact parent cards above their replies.
        val visibleEvents = events.filter { it.id !in contextOnlyIds }

        items(
            items = visibleEvents,
            key = { it.id },
        ) { row ->
            val parentRow = row.replyToId?.let { eventMap[it] }
            if (parentRow != null) {
                ThreadedReplyItem(
                    parentRow = parentRow,
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
}

/**
 * A reply with its parent note rendered above in a compact card,
 * connected by a thin vertical line.
 */
@Composable
private fun ThreadedReplyItem(
    parentRow: FeedRow,
    replyRow: FeedRow,
    engagement: EngagementSnapshot,
    callbacks: EventActionCallbacks,
    videoScope: VideoPlaybackScope?,
    context: RenderContext,
    isNewPost: Boolean,
    onNewPostAnimated: () -> Unit,
    thumbnailCache: VideoThumbnailCache? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // ── Compact parent card ──────────────────────────────────────────
        ThreadParentCard(
            row = parentRow,
            onNoteClick = callbacks.onNoteClick,
        )

        // ── Connecting line ──────────────────────────────────────────────
        val lineColor = Color.White.copy(alpha = 0.1f)
        Box(
            modifier = Modifier
                .padding(start = 28.dp)
                .width(1.dp)
                .height(8.dp)
                .background(lineColor),
        )

        // ── Full reply card ──────────────────────────────────────────────
        EventFeedItem(
            row = replyRow,
            engagement = engagement,
            callbacks = callbacks,
            videoScope = videoScope,
            context = context,
            isNewPost = isNewPost,
            onNewPostAnimated = onNewPostAnimated,
            thumbnailCache = thumbnailCache,
        )
    }
}

/**
 * Compact parent note card — shows author, timestamp, and truncated content.
 * No action bar. Clickable to navigate to the parent note.
 */
@Composable
private fun ThreadParentCard(
    row: FeedRow,
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
            .clickable { onNoteClick(row.id) }
            .padding(12.dp),
    ) {
        // Compact header: avatar + name + timestamp
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarImage(
                pubkey = row.pubkey,
                picture = row.authorPicture,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = row.displayName ?: "${row.pubkey.take(6)}…${row.pubkey.takeLast(4)}",
                color = Color.White.copy(alpha = 0.7f),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = relativeTime(row.createdAt),
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 13.sp,
            )
        }
        if (row.content.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = row.content.trim(),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
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
