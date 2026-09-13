package com.unsilence.app.ui.notifications

import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.NotificationRow
import com.unsilence.app.data.memory.isMuted
import com.unsilence.app.data.memory.toEventEntity
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.RepostPayload
import com.unsilence.app.data.model.resolveDisplayModel
import com.unsilence.app.ui.feed.EventReferenceTarget
import com.unsilence.app.ui.feed.buildRepostTargetReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

internal const val NOTIFICATION_PREVIEW_WINDOW = 12
internal const val NOTIFICATION_PREVIEW_CONCURRENCY = 3
// Match the existing reference resolver's bounded wait; an unavailable post remains tappable.
internal const val NOTIFICATION_PREVIEW_TIMEOUT_MS = 8_000L

/** Replies/mentions preview the new message, votes the poll, groups the acted-on post. */
internal fun NotificationRow.previewTargetId(): String? = when (this) {
    is NotificationRow.Single -> if (notifType == "poll_vote") targetNoteId else id
    is NotificationRow.Grouped -> targetNoteId
}?.takeIf(String::isNotBlank)

/** One small warm window, deduplicated by target rather than notification key. */
internal fun notificationPreviewTargets(
    rows: List<NotificationRow>,
    firstVisible: Int,
    lastVisible: Int,
): List<String> {
    if (firstVisible < 0 || lastVisible < firstVisible) return emptyList()
    val start = (firstVisible - 1).coerceAtLeast(0)
    return rows.asSequence()
        .drop(start)
        .take((lastVisible - start + 2).coerceAtMost(NOTIFICATION_PREVIEW_WINDOW))
        .mapNotNull(NotificationRow::previewTargetId)
        .distinct()
        .toList()
}

internal sealed interface NotificationPreview {
    data object Unavailable : NotificationPreview
    data object Muted : NotificationPreview
    data class Ready(val event: EventEntity, val model: EventModel) : NotificationPreview
}

/**
 * Bounded, cache-only projection. Observe store arrivals independently of notification
 * grouping: a fetched target need not generate another notification signal. No stats
 * snapshots, conversation scans or second model cache are needed for these previews.
 */
internal fun notificationPreviews(
    targets: Flow<List<String>>,
    eventSignal: Flow<Long>,
    muteLists: Flow<MuteList?>,
    eventProvider: (String) -> NostrEvent?,
    modelProvider: (String) -> EventModel?,
): Flow<Map<String, NotificationPreview>> = combine(targets, eventSignal, muteLists) { ids, _, mutes ->
    ids.take(NOTIFICATION_PREVIEW_WINDOW).associateWith { id ->
        val event = eventProvider(id)
        when {
            event == null -> NotificationPreview.Unavailable
            isMuted(event, mutes, eventProvider) -> NotificationPreview.Muted
            else -> {
                val model = modelProvider(id)?.resolveDisplayModel(modelProvider = modelProvider)
                if (model == null) NotificationPreview.Unavailable
                else NotificationPreview.Ready((eventProvider(model.id) ?: event).toEventEntity(), model)
            }
        }
    }
}.distinctUntilChanged().flowOn(Dispatchers.Default)

/** No persistent queue/cache: cancellation releases this screen's small warm window. */
internal suspend fun resolveNotificationPreviews(
    targets: List<String>,
    eventProvider: (String) -> NostrEvent?,
    muteList: () -> MuteList?,
    lookup: suspend (EventReferenceTarget) -> EventEntity?,
    onFinished: (String) -> Unit,
) = coroutineScope {
    val permits = Semaphore(NOTIFICATION_PREVIEW_CONCURRENCY)
    targets.distinct().take(NOTIFICATION_PREVIEW_WINDOW).map { id ->
        async {
            try {
                permits.withPermit {
                    withTimeoutOrNull(NOTIFICATION_PREVIEW_TIMEOUT_MS) {
                        if (eventProvider(id) == null) {
                            lookup(EventReferenceTarget(id, null, null, emptyList()))
                        }
                        val event = eventProvider(id) ?: return@withTimeoutOrNull
                        if (isMuted(event, muteList(), eventProvider)) return@withTimeoutOrNull
                        val repost = event.repostInfo
                        if (repost?.payload is RepostPayload.ReferenceOnly) {
                            buildRepostTargetReference(
                                eventId = repost.targetId,
                                addressCoordinate = repost.addressCoordinate,
                                authorPubkey = repost.targetAuthorHint,
                                relayHints = listOfNotNull(repost.relayHint, repost.addressRelayHint),
                            )?.let { lookup(it) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The row retains an explicit unavailable/tap-to-open fallback, never raw content.
            } finally {
                onFinished(id)
            }
        }
    }.awaitAll()
}
