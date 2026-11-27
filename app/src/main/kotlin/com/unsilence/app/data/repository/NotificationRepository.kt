package com.unsilence.app.data.repository

import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.RelayPreferencesStore
import javax.inject.Inject
import javax.inject.Singleton
import com.unsilence.app.data.memory.NotificationRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The badge uses the same moderation rules as the inbox without loading actor profiles. */
@Singleton
class NotificationRepository @Inject constructor(
    private val store: MemoryEventStore,
    private val preferences: RelayPreferencesStore,
) {
    private val seenInSession = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val seenWrites = Mutex()

    fun rows(owner: String, followedOnly: Boolean = false, includeProfiles: Boolean = true) =
        mutedNotificationsFlow(
            store.notificationsFlow(owner, followedOnly, includeProfileChanges = includeProfiles),
            store.ownMuteListFlow(), store::getNostrEvent,
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    fun applyScoreFilter(candidates: Flow<List<NotificationRow>>): Flow<List<NotificationRow>> =
        preferences.wotScoreFilter.filterNotNull().flatMapLatest { filter ->
            if (!filter.enabled) candidates else combine(candidates, store.wotSignalFlow) { rows, _ ->
                projectScoredNotifications(rows, filter, store::wotFor)
            }
        }.flowOn(Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun unread(owner: String, requestScores: (Collection<String>) -> Unit): Flow<Boolean> {
        val pending = combine(rows(owner, includeProfiles = false), preferences.getLastSeenTimestamp(owner), seenInSession) { rows, stored, seen ->
            val cutoff = maxOf(stored, seen[owner] ?: 0L)
            rows.takeWhile { it.mostRecentAt > cutoff } to cutoff
        }
        return preferences.wotScoreFilter.filterNotNull().flatMapLatest { filter ->
            if (!filter.enabled) pending.map { (rows, _) -> rows.isNotEmpty() }
            else {
                val candidates = pending.onEach { (rows, cutoff) ->
                    // Hydrate only unread actors. A new high-scoring stranger can light the badge before the inbox opens.
                    val authors = buildSet {
                        for (row in rows) when (row) {
                            is NotificationRow.Single -> add(row.actorPubkey)
                            is NotificationRow.Grouped -> row.actors.forEach { actor ->
                                if (actor.createdAt > cutoff) actor.pubkey?.let(::add)
                            }
                        }
                    }
                    if (authors.isNotEmpty()) requestScores(authors)
                }
                combine(candidates, store.wotSignalFlow) { (rows, cutoff), _ ->
                    val accepted = projectScoredNotifications(rows, filter, store::wotFor)
                    hasUnreadNotifications(accepted.firstOrNull()?.mostRecentAt, cutoff, cutoff)
                }
            }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    suspend fun markSeen(owner: String, timestamp: Long) {
        seenInSession.update { it + (owner to maxOf(it[owner] ?: 0L, timestamp)) }
        seenWrites.withLock {
            val stored = preferences.getLastSeenTimestamp(owner).first()
            val latest = maxOf(stored, seenInSession.value.getValue(owner))
            if (latest > stored) preferences.setLastSeenTimestamp(owner, latest)
        }
    }
}

internal fun hasUnreadNotifications(newest: Long?, storedSeen: Long, sessionSeen: Long): Boolean =
    newest != null && newest > maxOf(storedSeen, sessionSeen)
