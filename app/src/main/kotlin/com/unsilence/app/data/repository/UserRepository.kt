package com.unsilence.app.data.repository

import com.unsilence.app.data.db.dao.RelayListDao
import com.unsilence.app.data.db.dao.UserDao
import com.unsilence.app.data.db.entity.RelayListEntity
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.relay.ProfileResolver
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val userDao: UserDao,
    private val relayListDao: RelayListDao,
    private val profileResolver: ProfileResolver,
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
     * Delegates to [ProfileResolver] for in-flight dedup, batching, and staleness checks.
     */
    fun fetchMissingProfiles(pubkeys: List<String>) {
        profileResolver.request(pubkeys)
    }
}
