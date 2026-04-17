package com.unsilence.app.data.repository

import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.RelayList
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.relay.ProfileResolver
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val memoryEventStore: MemoryEventStore,
    private val profileResolver: ProfileResolver,
) {
    fun userFlow(pubkey: String): Flow<UserEntity?> = memoryEventStore.userEntityFlow(pubkey)

    /** Returns the cached lightning address (lud16) for [pubkey], or null if not yet loaded. */
    fun getUserLud16(pubkey: String): String? = memoryEventStore.getUserEntity(pubkey)?.lud16

    /** One-shot lookup for a user profile by pubkey. */
    fun getUser(pubkey: String): UserEntity? = memoryEventStore.getUserEntity(pubkey)

    /** Look up a user's NIP-65 relay list from MES cache. */
    fun getRelayList(pubkey: String): RelayList? = memoryEventStore.getRelayList(pubkey)

    /**
     * Requests profiles for pubkeys not yet cached OR stale (>6 hours).
     * Pre-filters via [ProfileResolver.filterUnresolved] so that already-fresh pubkeys
     * never reach the batching/relay pipeline — eliminates "all fresh, skipping" waste.
     * Default scroll mode: 1 indexer relay.
     */
    suspend fun fetchMissingProfiles(pubkeys: List<String>) {
        val stale = profileResolver.filterUnresolved(pubkeys.toSet())
        if (stale.isEmpty()) return
        profileResolver.request(stale.toList())
    }

    /** Profile screen variant: hits up to [maxRelays] indexer relays for better coverage. */
    suspend fun fetchProfilesWithFanout(pubkeys: List<String>, maxRelays: Int = 4) {
        val stale = profileResolver.filterUnresolved(pubkeys.toSet())
        if (stale.isEmpty()) return
        profileResolver.requestWithFanout(stale.toList(), maxRelays)
    }
}
