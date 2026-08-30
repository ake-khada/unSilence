package com.unsilence.app.ui.notifications

import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.NotificationRow
import com.unsilence.app.data.memory.isMuted
import com.unsilence.app.data.memory.isPubkeyMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

internal fun mutedNotificationsFlow(
    rows: Flow<List<NotificationRow>>,
    muteLists: Flow<MuteList?>,
    eventProvider: (String) -> NostrEvent?,
): Flow<List<NotificationRow>> =
    combine(rows, muteLists) { notificationRows, muteList ->
        projectMutedNotifications(notificationRows, muteList, eventProvider)
    }.flowOn(Dispatchers.Default)

/** Apply mute policy before notification UI, profile fetches, and WoT hydration. */
internal fun projectMutedNotifications(
    rows: List<NotificationRow>,
    muteList: MuteList?,
    eventProvider: (String) -> NostrEvent?,
): List<NotificationRow> {
    if (muteList == null) return rows

    return rows.mapNotNull { row ->
        when (row) {
            is NotificationRow.Single -> {
                val event = eventProvider(row.id)
                val muted = if (event != null) {
                    isMuted(event, muteList, eventProvider)
                } else {
                    isPubkeyMuted(row.actorPubkey, muteList)
                }
                row.takeUnless { muted }
            }

            is NotificationRow.Grouped -> projectGroupedNotification(row, muteList)
        }
    }.sortedByDescending { it.mostRecentAt }
}

private fun projectGroupedNotification(
    row: NotificationRow.Grouped,
    muteList: MuteList,
): NotificationRow.Grouped? {
    val actors = row.actors.filterNot { actor ->
        actor.pubkey?.let { isPubkeyMuted(it, muteList) } == true
    }
    if (actors.isEmpty() && row.anonymousCount == 0) return null

    val reactionCounts = actors.asSequence()
        .mapNotNull { it.reaction }
        .groupingBy { it }
        .eachCount()
    val dominantReaction = reactionCounts.maxByOrNull { it.value }?.key

    return row.copy(
        actors = actors,
        people = actors.size + row.anonymousCount,
        sumSats = actors.sumOf { it.sats } + row.anonymousSats,
        dominantReaction = dominantReaction,
        mostRecentAt = maxOf(
            actors.maxOfOrNull { it.createdAt } ?: 0L,
            row.anonymousMostRecentAt,
        ),
    )
}
