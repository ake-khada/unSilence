package com.unsilence.app.data.repository

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

            is NotificationRow.Grouped -> projectGroupedNotification(row, keepUnknown = true) {
                !isPubkeyMuted(it, muteList)
            }
        }
    }.sortedByDescending { it.mostRecentAt }
}

private fun projectGroupedNotification(
    row: NotificationRow.Grouped,
    keepUnknown: Boolean,
    keep: (String) -> Boolean,
): NotificationRow.Grouped? {
    val actors = row.actors.filter { actor -> actor.pubkey?.let(keep) ?: keepUnknown }
    val base = if (keepUnknown) row else row.copy(
        anonymousCount = 0, anonymousSats = 0L, anonymousMostRecentAt = 0L,
        unverifiedCount = 0, unverifiedSats = 0L, unverifiedMostRecentAt = 0L,
    )
    if (actors.isEmpty() && base.anonymousCount == 0 && base.unverifiedCount == 0) return null

    val reactionCounts = actors.asSequence()
        .mapNotNull { it.reaction }
        .groupingBy { it }
        .eachCount()
    val dominantReaction = reactionCounts.maxByOrNull { it.value }?.key

    return base.copy(
        actors = actors,
        people = actors.size + base.anonymousCount + base.unverifiedCount,
        sumSats = actors.sumOf { it.sats } + base.anonymousSats + base.unverifiedSats,
        dominantReaction = dominantReaction,
        mostRecentAt = maxOf(
            actors.maxOfOrNull { it.createdAt } ?: 0L,
            base.anonymousMostRecentAt,
            base.unverifiedMostRecentAt,
        ),
    )
}

internal fun projectScoredNotifications(
    rows: List<NotificationRow>,
    filter: com.unsilence.app.data.relay.WotScoreFilter,
    lookup: (String) -> com.unsilence.app.data.memory.WotLookup,
): List<NotificationRow> {
    if (!filter.enabled) return rows
    return rows.mapNotNull { row ->
        when (row) {
            is NotificationRow.Single -> row.takeIf { filter.admits(lookup(it.actorPubkey)) }
            is NotificationRow.Grouped -> projectGroupedNotification(row, keepUnknown = false) { filter.admits(lookup(it)) }
        }
    }.sortedByDescending { it.mostRecentAt }
}
