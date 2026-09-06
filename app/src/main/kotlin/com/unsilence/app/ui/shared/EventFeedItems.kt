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
import com.unsilence.app.data.model.resolveDisplayModel
import com.unsilence.app.ui.feed.AvatarImage
import com.unsilence.app.ui.feed.ContentFlow
import com.unsilence.app.ui.feed.EventCard
import com.unsilence.app.ui.feed.EventCardHost
import com.unsilence.app.ui.feed.EventCardParent
import com.unsilence.app.ui.feed.EventCardPresentation
import com.unsilence.app.ui.feed.collectProfileAsState
import com.unsilence.app.ui.feed.looksLikeHexPubkey
import com.unsilence.app.ui.feed.relativeTime
import com.unsilence.app.ui.feed.buildReplyParentReference
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.feed.NoteActionsViewModel
import kotlinx.coroutines.flow.Flow

@androidx.compose.runtime.Stable
data class PollActionCallbacks(
    val currentPubkey: String?,
    val responses: (String) -> Flow<List<com.unsilence.app.data.memory.NostrEvent>>,
    val load: (String, List<String>, Long?) -> Unit,
    val vote: (com.unsilence.app.ui.feed.PollVoteRequest) -> Unit,
)

fun NoteActionsViewModel.pollActionCallbacks() = PollActionCallbacks(
    currentPubkey = currentPubkey,
    responses = ::pollResponsesFlow,
    load = ::loadPollResponses,
    vote = ::votePoll,
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

/** Engagement state narrowed to one card. Keeping the large ID sets outside
 *  EventCard lets Compose skip every unaffected card when one reaction, repost,
 *  zap, or loading flag changes. */
@androidx.compose.runtime.Immutable
data class EventEngagementSnapshot(
    val hasReacted: Boolean = false,
    val hasReposted: Boolean = false,
    val hasZapped: Boolean = false,
    val isNwcConfigured: Boolean = false,
    val isZapLoading: Boolean = false,
    val extraZapSats: Long = 0L,
    val zapFlash: NoteActionsViewModel.ZapFlashState? = null,
)

internal fun EngagementSnapshot.forEvent(eventId: String): EventEngagementSnapshot =
    EventEngagementSnapshot(
        hasReacted = eventId in reactedIds,
        hasReposted = eventId in repostedIds,
        hasZapped = eventId in zappedIds,
        isNwcConfigured = isNwcConfigured,
        isZapLoading = eventId in zapLoadingIds,
        extraZapSats = optimisticZapSats[eventId] ?: 0L,
        zapFlash = zapFlash?.takeIf { it.noteId == eventId },
    )

/**
 * Shared LazyListScope extension that renders a list of FeedRow items
 * using the unified EventCard pipeline.
 *
 * [showThreadParents] — when true (Conversations tab), replies are grouped
 * with a compact parent note card above them, connected by a vertical line.
 * Parent notes are fetched through [EventCardHost] (MES + relay).
 */
fun LazyListScope.eventFeedItems(
    events: List<FeedRow>,
    engagement: EngagementSnapshot,
    host: EventCardHost,
    role: CardRole = CardRole.Feed,
    newEventIds: Set<String> = emptySet(),
    onNewPostAnimated: ((String) -> Unit)? = null,
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
        if (showThreadParents && (parentId != null || row.kind == 1111)) {
            ThreadedReplyItem(
                parentId = parentId,
                replyRow = row,
                engagement = engagement,
                host = host,
                role = role,
                newPostAnimated = if (row.id in newEventIds) {
                    onNewPostAnimated?.let { callback -> { callback(row.id) } }
                } else null,
            )
        } else {
            EventFeedItem(
                row = row,
                engagement = engagement,
                host = host,
                role = role,
                newPostAnimated = if (row.id in newEventIds) {
                    onNewPostAnimated?.let { callback -> { callback(row.id) } }
                } else null,
            )
        }
        FeedDivider()
    }
}

/**
 * A reply with its parent note fetched via lookupEvent and embedded
 * inside the reply card (between header and content), like quoted notes.
 */
@Composable
private fun ThreadedReplyItem(
    parentId: String?,
    replyRow: FeedRow,
    engagement: EngagementSnapshot,
    host: EventCardHost,
    role: CardRole,
    newPostAnimated: (() -> Unit)?,
) {
    // Resolve from MES, then source/tag hints, author outbox, and finally the
    // address coordinate. The wire-derived reference survives snapshot deletion.
    val parentReference = remember(parentId, replyRow.tags, replyRow.relayUrl, replyRow.relaysSeen) {
        buildReplyParentReference(
            eventId = parentId,
            tagsJson = replyRow.tags,
            sourceRelay = replyRow.relayUrl,
            sourceRelayHints = replyRow.relaysSeen,
        )
    }
    val parentEvent by produceState<EventEntity?>(null, parentReference) {
        val reference = parentReference ?: return@produceState
        value = host.lookupEvent(reference)
    }
    val parentAuthor by produceState<UserEntity?>(null, parentEvent?.pubkey) {
        val pk = parentEvent?.pubkey
        if (pk != null) {
            value = host.lookupProfile(
                pk,
                (listOf(replyRow.relayUrl) + replyRow.relaysSeen).distinct(),
            )
        }
    }

    // Single card — parent is embedded inside the reply EventCard
    EventFeedItem(
        row = replyRow,
        engagement = engagement,
        host = host,
        role = role,
        newPostAnimated = newPostAnimated,
        parentEvent = parentEvent,
        parentAuthor = parentAuthor,
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
    host: EventCardHost,
    videoOwnerId: String,
    modifier: Modifier,
) {
    val actions = host.actions
    val services = host.services
    val surface = host.surface
    val lookupProfile: suspend (String) -> UserEntity? = { pubkey -> host.lookupProfile(pubkey) }
    val sourceModel = remember(event.id) {
        services.lookupModel(event.id) ?: runCatching {
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
    val model = remember(sourceModel, services.lookupModel) {
        sourceModel?.resolveDisplayModel(modelProvider = services.lookupModel)
    }
    val displayPubkey = model?.pubkey ?: event.pubkey
    val displayCreatedAt = model?.createdAt ?: event.createdAt
    val liveAuthor = collectProfileAsState(displayPubkey, surface.profileFlow)
    val effectiveAuthor = liveAuthor ?: author.takeIf { displayPubkey == event.pubkey }
    LaunchedEffect(displayPubkey) {
        surface.onWotSubjectsVisible(listOf(displayPubkey))
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
            .clickable { actions.onNoteClick(model?.navigateId ?: event.id) }
            .padding(12.dp),
    ) {
        // Compact header: avatar + name + timestamp
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarImage(
                pubkey = displayPubkey,
                picture = effectiveAuthor?.picture,
                modifier = Modifier.size(24.dp),
                sizeDp = 24.dp,
                lookupProfile = lookupProfile,
                profileFlow = surface.profileFlow,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = effectiveAuthor?.displayName?.takeIf { it.isNotBlank() }
                    ?: effectiveAuthor?.name?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }
                    ?: "${displayPubkey.take(6)}…${displayPubkey.takeLast(4)}",
                color = Color.White.copy(alpha = 0.7f),
                fontWeight = FontWeight.SemiBold,
                fontSize = AppType.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val lookup = surface.wotLookup?.invoke(displayPubkey)
            Spacer(Modifier.width(6.dp))
            WotFeedMetaTimestamp(
                lookup = lookup,
                mode = surface.feedWotDisplayMode,
                timestamp = relativeTime(displayCreatedAt),
                timestampColor = Color.White.copy(alpha = 0.4f),
            )
        }

        if (model != null) {
            Spacer(Modifier.height(4.dp))
            // NIP-36: gate on the parent's own content-warning.
            EmbeddedSensitiveGate(
                mode = surface.sensitiveMode,
                sensitive = event.hasContentWarning || model.warnings.hasContentWarning,
                reason = event.contentWarningReason ?: model.warnings.reason,
            ) {
                ContentFlow(
                    model               = model,
                    role                = CardRole.Embedded,
                    host                = host,
                    videoOwnerId        = videoOwnerId,
                    nestDepth           = 1,
                    knownLightningAddress = effectiveAuthor?.lud16,
                )
            }
        } else if (event.kind == 6 || event.kind == 16) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Reposted post unavailable",
                color = com.unsilence.app.ui.theme.TextSecondary,
                fontSize = AppType.footnote,
            )
        }
    }
}

@Composable
internal fun EventFeedItem(
    row: FeedRow,
    engagement: EngagementSnapshot,
    host: EventCardHost,
    role: CardRole,
    newPostAnimated: (() -> Unit)? = null,
    parentEvent: EventEntity? = null,
    parentAuthor: UserEntity? = null,
) {
    val model = remember(row.id) {
        host.services.lookupModel(row.id) ?: row.toEventModel()
    }
    val rowRelayHints = remember(row.id, row.relayUrl, row.relaysSeen) {
        (listOf(row.relayUrl) + row.relaysSeen).filter { it.isNotBlank() }.distinct()
    }
    val rowHost = remember(host, rowRelayHints) { host.withRelayHints(rowRelayHints) }
    val eventEngagement = remember(model.engagementId, engagement) {
        engagement.forEvent(model.engagementId)
    }

    EventCard(
        model               = model,
        row                 = row,
        role                = role,
        engagement          = eventEngagement,
        host                = rowHost,
        presentation        = EventCardPresentation(
            newPostAnimated = newPostAnimated,
            parent = parentEvent?.let {
                EventCardParent(it, parentAuthor)
            },
        ),
    )
}
