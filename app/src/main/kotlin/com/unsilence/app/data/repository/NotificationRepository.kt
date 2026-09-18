package com.unsilence.app.data.repository

import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.RelayPreferencesStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Badge observation never creates the notification screen or hydrates its actors. */
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

    fun unread(owner: String) = combine(
        rows(owner, includeProfiles = false),
        preferences.getLastSeenTimestamp(owner),
        seenInSession,
    ) { rows, stored, seen ->
        hasUnreadNotifications(rows.firstOrNull()?.mostRecentAt, stored, seen[owner] ?: 0L)
    }.distinctUntilChanged()

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
