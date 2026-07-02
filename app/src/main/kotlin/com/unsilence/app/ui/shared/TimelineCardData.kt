package com.unsilence.app.ui.shared

import androidx.collection.LruCache
import com.unsilence.app.data.memory.EventStats
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.ReactionInfo
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.ZapDetail
import com.unsilence.app.data.repository.UserRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

private const val CARD_FLOW_CACHE_SIZE = 500
private const val CARD_FLOW_STOP_TIMEOUT_MS = 5_000L

/**
 * Shared reactive data source for timeline cards.
 *
 * Feed, profile, thread, and article-reader screens all render the same card
 * primitives. Keeping their profile/stat caches here prevents each screen from
 * drifting into slightly different cache sizing, source selection, and stop
 * timeout behavior.
 */
class TimelineCardData @Inject constructor(
    private val userRepository: UserRepository,
    private val memoryEventStore: MemoryEventStore,
) {
    private val profileCache = LruCache<String, StateFlow<UserEntity?>>(CARD_FLOW_CACHE_SIZE)
    private val statsCache = LruCache<String, StateFlow<EventStats>>(CARD_FLOW_CACHE_SIZE)

    fun profileFlow(pubkey: String, scope: CoroutineScope): StateFlow<UserEntity?> =
        synchronized(profileCache) {
            profileCache.get(pubkey) ?: userRepository.userFlow(pubkey)
                .stateIn(scope, SharingStarted.WhileSubscribed(CARD_FLOW_STOP_TIMEOUT_MS), null)
                .also { profileCache.put(pubkey, it) }
        }

    fun statsFlow(eventId: String, scope: CoroutineScope): StateFlow<EventStats> =
        synchronized(statsCache) {
            statsCache.get(eventId) ?: memoryEventStore.statsFlow(eventId)
                .stateIn(
                    scope,
                    SharingStarted.WhileSubscribed(CARD_FLOW_STOP_TIMEOUT_MS),
                    memoryEventStore.currentStatsSnapshot(eventId),
                )
                .also { statsCache.put(eventId, it) }
        }

    fun zapDetailsForEvent(eventId: String): List<ZapDetail> =
        memoryEventStore.zapDetailsForEvent(eventId)

    fun repostPubkeysForEvent(eventId: String): List<String> =
        memoryEventStore.repostPubkeysForEvent(eventId)

    fun reactionsForEvent(eventId: String): List<ReactionInfo> =
        memoryEventStore.reactionsForEvent(eventId)
}
