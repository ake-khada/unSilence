package com.unsilence.app.ui.shared

import androidx.collection.LruCache
import com.unsilence.app.data.memory.EventStats
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.ReactionInfo
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.ZapDetail
import com.unsilence.app.data.repository.UserRepository
import javax.inject.Inject
import dagger.hilt.android.scopes.ViewModelScoped

private const val CARD_FLOW_CACHE_SIZE = 500

/**
 * Shared implementation, with one cache owner per ViewModel (not a global instance).
 *
 * Feed, profile, thread, and article-reader screens all render the same card
 * primitives. Keeping their profile/stat caches here prevents each screen from
 * drifting into slightly different cache sizing and source selection.
 *
 * The cache contains cold handles, not ViewModel-owned sharing jobs. Collection
 * belongs to visible card lifecycles, so LRU eviction cannot strand jobs in a
 * long-lived feed ViewModel or interrupt an already-visible card.
 */
@ViewModelScoped
class TimelineCardData @Inject constructor(
    private val userRepository: UserRepository,
    private val memoryEventStore: MemoryEventStore,
) {
    private val profileCache = LruCache<String, CardDataFlow<UserEntity?>>(CARD_FLOW_CACHE_SIZE)
    private val statsCache = LruCache<String, CardDataFlow<EventStats>>(CARD_FLOW_CACHE_SIZE)

    fun profileFlow(pubkey: String): CardDataFlow<UserEntity?> =
        synchronized(profileCache) {
            profileCache.get(pubkey) ?: CardDataFlow(userRepository.userFlow(pubkey)) {
                userRepository.getUser(pubkey)
            }.also { profileCache.put(pubkey, it) }
        }

    fun statsFlow(eventId: String): CardDataFlow<EventStats> =
        synchronized(statsCache) {
            statsCache.get(eventId) ?: CardDataFlow(memoryEventStore.statsFlow(eventId)) {
                memoryEventStore.currentStatsSnapshot(eventId)
            }.also { statsCache.put(eventId, it) }
        }

    fun zapDetailsForEvent(eventId: String): List<ZapDetail> =
        memoryEventStore.zapDetailsForEvent(eventId)

    fun repostPubkeysForEvent(eventId: String): List<String> =
        memoryEventStore.repostPubkeysForEvent(eventId)

    fun reactionsForEvent(eventId: String): List<ReactionInfo> =
        memoryEventStore.reactionsForEvent(eventId)
}
