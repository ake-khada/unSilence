package com.unsilence.app.data.repository

import com.unsilence.app.data.db.dao.RelayListDao
import com.unsilence.app.data.db.dao.UserDao
import com.unsilence.app.data.db.entity.RelayListEntity
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.relay.RelayPool
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val userDao: UserDao,
    private val relayListDao: RelayListDao,
    private val relayPool: RelayPool,
) {
    fun userFlow(pubkey: String): Flow<UserEntity?> = userDao.userFlow(pubkey)

    /** Returns the cached lightning address (lud16) for [pubkey], or null if not yet loaded. */
    suspend fun getUserLud16(pubkey: String): String? = userDao.getUser(pubkey)?.lud16

    /** Debug: one-shot lookup for a user profile by pubkey. */
    suspend fun getUser(pubkey: String): UserEntity? = userDao.getUser(pubkey)

    /** NIP-50 profile search — re-emits as search results arrive from the relay. */
    fun searchUsers(query: String): Flow<List<UserEntity>> = userDao.searchUsers(query)

    /** Look up a user's NIP-65 relay list from Room cache. */
    suspend fun getRelayList(pubkey: String): RelayListEntity? =
        relayListDao.getByPubkey(pubkey)

    /**
     * Requests profiles for pubkeys not yet cached OR stale (>6 hours).
     * The fetched kind-0 events will arrive via EventProcessor → Room.
     */
    suspend fun fetchMissingProfiles(pubkeys: List<String>) {
        val cached = userDao.allPubkeys().toSet()
        val staleThreshold = System.currentTimeMillis() / 1000 - (6 * 3600)
        val stale = userDao.stalePubkeys(staleThreshold).toSet()
        val toFetch = pubkeys.filter { it !in cached || it in stale }
        if (toFetch.isNotEmpty()) {
            relayPool.fetchProfiles(toFetch)
        }
    }
}
