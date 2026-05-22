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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.toEventModel
import com.unsilence.app.data.model.ContentParser
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.ui.common.ShimmerNoteCard
import com.unsilence.app.ui.feed.AvatarImage
import com.unsilence.app.ui.feed.ContentFlow
import com.unsilence.app.ui.feed.EventCard
import com.unsilence.app.ui.feed.ImageDimensionCache
import com.unsilence.app.ui.feed.VideoThumbnailCache
import com.unsilence.app.ui.feed.looksLikeHexPubkey
import com.unsilence.app.ui.feed.relativeTime
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.BorderFaint
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.feed.NoteActionsViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Parameters bundle for engagement actions — avoids 15-parameter lambda pollution.
 */
@androidx.compose.runtime.Stable
data class EventActionCallbacks(
    val onNoteClick: (String) -> Unit = {},
    val onComment: (eventId: String) -> Unit = {},
    val onAuthorClick: (pubkey: String) -> Unit = {},
    val onQuote: (String) -> Unit = {},
    val onArticleClick: (FeedRow) -> Unit = {},
    val react: (eventId: String, pubkey: String) -> Unit = { _, _ -> },
    val repost: (eventId: String, pubkey: String, relayUrl: String) -> Unit = { _, _, _ -> },
    val zap: (eventId: String, pubkey: String, relayUrl: String, amount: Long) -> Unit = { _, _, _, _ -> },
    val saveNwcUri: (String) -> Unit = {},
    val lookupProfile: (suspend (String) -> UserEntity?)? = null,
    val lookupEvent: (suspend (String, List<String>) -> EventEntity?)? = null,
    val lookupEventWithAuthor: (suspend (String, List<String>, String?) -> EventEntity?)? = null,
    val fetchOgMetadata: (suspend (String) -> OgMetadata?)? = null,
    val profileFlow: ((String) -> StateFlow<UserEntity?>)? = null,
    /** Per-event stats observation. Cards collect this so engagement counts
     *  update reactively without going through a list-wide signal trigger.
     *  Falls back to the snapshot in FeedRow when null. */
    val statsFlow: ((String) -> StateFlow<com.unsilence.app.data.memory.EventStats>)? = null,
    val onLongPress: ((FeedRow) -> Unit)? = null,
    val zapDetailsForEvent: ((String) -> List<com.unsilence.app.data.memory.ZapDetail>)? = null,
    val repostPubkeysForEvent: ((String) -> List<String>)? = null,
    val reactionsForEvent: ((String) -> List<com.unsilence.app.data.memory.ReactionInfo>)? = null,
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
 * using the unified EventCard pipeline.
 *
 * [showThreadParents] — when true (Conversations tab), replies are grouped
 * with a compact parent note card above them, connected by a vertical line.
 * Parent notes are fetched via [EventActionCallbacks.lookupEvent] (MES + relay).
 */
fun LazyListScope.eventFeedItems(
    events: List<FeedRow>,
    engagement: EngagementSnapshot,
    callbacks: EventActionCallbacks,
    videoScope: VideoPlaybackScope? = null,
    role: CardRole = CardRole.Feed,
    newEventIds: Set<String> = emptySet(),
    onNewPostAnimated: (String) -> Unit = {},
    thumbnailCache: VideoThumbnailCache? = null,
    imageDimensionCache: ImageDimensionCache? = null,
    showThreadParents: Boolean = false,
    eventModelProvider: ((String) -> EventModel?)? = null,
    sensitiveBlur: Boolean = false,
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
                role = role,
                isNewPost = row.id in newEventIds,
                onNewPostAnimated = { onNewPostAnimated(row.id) },
                thumbnailCache = thumbnailCache,
                imageDimensionCache = imageDimensionCache,
                eventModelProvider = eventModelProvider,
                sensitiveBlur = sensitiveBlur,
            )
        } else {
            EventFeedItem(
                row = row,
                engagement = engagement,
                callbacks = callbacks,
                videoScope = videoScope,
                role = role,
                isNewPost = row.id in newEventIds,
                onNewPostAnimated = { onNewPostAnimated(row.id) },
                thumbnailCache = thumbnailCache,
                imageDimensionCache = imageDimensionCache,
                eventModelProvider = eventModelProvider,
                sensitiveBlur = sensitiveBlur,
            )
        }
        HorizontalDivider(
            color = BorderFaint,
            thickness = 1.dp,
            modifier = Modifier.padding(horizontal = Spacing.medium),
        )
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
    role: CardRole,
    isNewPost: Boolean,
    onNewPostAnimated: () -> Unit,
    thumbnailCache: VideoThumbnailCache? = null,
    imageDimensionCache: ImageDimensionCache? = null,
    eventModelProvider: ((String) -> EventModel?)? = null,
    sensitiveBlur: Boolean = false,
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

    // Single card — parent is embedded inside the reply EventCard
    EventFeedItem(
        row = replyRow,
        engagement = engagement,
        callbacks = callbacks,
        videoScope = videoScope,
        role = role,
        isNewPost = isNewPost,
        onNewPostAnimated = onNewPostAnimated,
        thumbnailCache = thumbnailCache,
        imageDimensionCache = imageDimensionCache,
        parentEvent = parentEvent,
        parentAuthor = parentAuthor,
        eventModelProvider = eventModelProvider,
        sensitiveBlur = sensitiveBlur,
    )
}

/**
 * Compact parent note card — shows author, timestamp, content via ContentFlow.
 * Uses [CardRole.Embedded] (6-line cap, no expand toggle) for compact rendering.
 * No action bar. Clickable to navigate to the parent note.
 */
@Composable
internal fun ThreadParentCard(
    event: EventEntity,
    author: UserEntity?,
    onNoteClick: (String) -> Unit,
    lookupProfile: (suspend (String) -> UserEntity?)? = null,
    lookupModel: ((String) -> EventModel?)? = null,
    lookupEvent: (suspend (String, List<String>) -> EventEntity?)? = null,
    onAuthorClick: (String) -> Unit = {},
    fetchOgMetadata: (suspend (String) -> OgMetadata?)? = null,
    imageDimensionCache: ImageDimensionCache? = null,
    modifier: Modifier = Modifier,
) {
    val model = remember(event.id) {
        lookupModel?.invoke(event.id) ?: runCatching {
            ContentParser.parse(
                id = event.id, pubkey = event.pubkey, kind = event.kind,
                content = event.content, tagsJson = event.tags,
                createdAt = event.createdAt, relayUrl = event.relayUrl,
                replyToId = event.replyToId, rootId = event.rootId,
                hasContentWarning = event.hasContentWarning,
                contentWarningReason = event.contentWarningReason,
            )
        }.getOrNull()
    }

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

        if (model != null) {
            Spacer(Modifier.height(4.dp))
            ContentFlow(
                model               = model,
                role                = CardRole.Embedded,
                onNoteClick         = onNoteClick,
                onAuthorClick       = onAuthorClick,
                lookupProfile       = lookupProfile,
                lookupEvent         = lookupEvent,
                lookupModel         = lookupModel,
                fetchOgMetadata     = fetchOgMetadata,
                imageDimensionCache = imageDimensionCache,
                nestDepth           = 1,
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
    role: CardRole,
    isNewPost: Boolean,
    onNewPostAnimated: () -> Unit,
    thumbnailCache: VideoThumbnailCache? = null,
    imageDimensionCache: ImageDimensionCache? = null,
    parentEvent: EventEntity? = null,
    parentAuthor: UserEntity? = null,
    eventModelProvider: ((String) -> EventModel?)? = null,
    sensitiveBlur: Boolean = false,
) {
    val model = remember(row.id) {
        eventModelProvider?.invoke(row.id) ?: row.toEventModel()
    }

    // ── Remembered lambdas — stable across recompositions ─────────────────
    // Use model.engagementId / model.pubkey so kind-6 reposts target
    // the ORIGINAL event, not the repost wrapper.
    val onReact = remember(model.engagementId, model.pubkey) {
        { callbacks.react(model.engagementId, model.pubkey) }
    }
    val onRepost = remember(model.engagementId, model.pubkey, row.relayUrl) {
        { callbacks.repost(model.engagementId, model.pubkey, row.relayUrl) }
    }
    val onZap: (Long) -> Unit = remember(model.engagementId, model.pubkey, row.relayUrl) {
        { amt: Long -> callbacks.zap(model.engagementId, model.pubkey, row.relayUrl, amt) }
    }
    val onToggleMute = remember(videoScope) {
        { videoScope?.toggleMute(); Unit }
    }
    val onOpenFullscreen = remember(videoScope, row.id) {
        { videoScope?.openFullscreen(row.id); Unit }
    }
    val onNewPostAnimatedCb = remember(row.id) {
        { onNewPostAnimated() }
    }
    val onComment = remember(model.navigateId) {
        { callbacks.onComment(model.navigateId) }
    }

    EventCard(
        model               = model,
        row                 = row,
        role                = role,
        engagement          = engagement,
        onNoteClick         = callbacks.onNoteClick,
        onComment           = onComment,
        onAuthorClick       = callbacks.onAuthorClick,
        onQuote             = callbacks.onQuote,
        onArticleClick      = callbacks.onArticleClick,
        onReact             = onReact,
        onRepost            = onRepost,
        onZap               = onZap,
        onSaveNwcUri        = callbacks.saveNwcUri,
        lookupProfile       = callbacks.lookupProfile,
        lookupEvent         = callbacks.lookupEvent,
        lookupEventWithAuthor = callbacks.lookupEventWithAuthor,
        lookupModel         = eventModelProvider,
        fetchOgMetadata     = callbacks.fetchOgMetadata,
        profileFlow         = callbacks.profileFlow,
        statsFlow           = callbacks.statsFlow,
        zapDetailsForEvent  = callbacks.zapDetailsForEvent,
        repostPubkeysForEvent = callbacks.repostPubkeysForEvent,
        reactionsForEvent   = callbacks.reactionsForEvent,
        imageDimensionCache = imageDimensionCache,
        thumbnailCache      = thumbnailCache,
        exoPlayer           = videoScope?.exoPlayer,
        isMuted             = videoScope?.isMuted ?: true,
        onToggleMute        = onToggleMute,
        isActiveVideo       = videoScope?.isActiveVideo(row.id) ?: false,
        isFullscreen        = videoScope?.showFullscreenVideo ?: false,
        onOpenFullscreen    = onOpenFullscreen,
        isNewPost           = isNewPost,
        onNewPostAnimated   = onNewPostAnimatedCb,
        parentEvent         = parentEvent,
        parentAuthor        = parentAuthor,
        onLongPress         = callbacks.onLongPress?.let { lp -> { lp(row) } },
        sensitiveBlur       = sensitiveBlur && row.hasContentWarning,
        contentWarningReason = row.contentWarningReason,
    )
}
