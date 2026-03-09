package com.unsilence.app.data.repository

import com.unsilence.app.data.db.dao.UserDao
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.relay.RelayPool
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val userDao: UserDao,
    private val relayPool: RelayPool,
) {
    fun userFlow(pubkey: String): Flow<UserEntity?> = userDao.userFlow(pubkey)

    /** Returns the cached lightning address (lud16) for [pubkey], or null if not yet loaded. */
    suspend fun getUserLud16(pubkey: String): String? = userDao.getUser(pubkey)?.lud16

    /**
     * Requests profiles for pubkeys not yet cached.
     * The fetched kind 0 events will arrive via EventProcessor → Room.
     */
    suspend fun fetchMissingProfiles(pubkeys: List<String>) {
        val cached = userDao.allPubkeys().toSet()
        val missing = pubkeys.filterNot { it in cached }
        if (missing.isNotEmpty()) {
            relayPool.fetchProfiles(missing)
        }
    }
}
