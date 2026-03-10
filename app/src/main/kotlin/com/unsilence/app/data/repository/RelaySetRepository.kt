package com.unsilence.app.data.repository

import com.unsilence.app.data.db.dao.RelaySetDao
import com.unsilence.app.data.db.entity.RelaySetEntity
import com.unsilence.app.domain.model.FeedFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

val GLOBAL_RELAY_URLS = listOf(
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://nostr.mom",
    "wss://relay.nostr.net",
    "wss://relay.ditto.pub",
    "wss://relay.primal.net",
)

@Singleton
class RelaySetRepository @Inject constructor(
    private val relaySetDao: RelaySetDao,
) {
    private val lenientJson = Json { ignoreUnknownKeys = true }

    val allSetsFlow: Flow<List<RelaySetEntity>> = relaySetDao.allSetsFlow()

    /**
     * Seeds the built-in Global relay set.
     * INSERT OR IGNORE creates the row on first launch.
     * The follow-up UPDATE forces filter_json to the current default on every launch,
     * so changes to FeedFilter.globalDefault take effect on existing installs too.
     */
    suspend fun seedDefaults() {
        relaySetDao.insertIfAbsent(
            RelaySetEntity(
                id         = "global",
                name       = "Global",
                relayUrls  = Json.encodeToString(GLOBAL_RELAY_URLS),
                isDefault  = true,
                isBuiltIn  = true,
                filterJson = Json.encodeToString(FeedFilter.globalDefault),
            )
        )
        // Always sync the filter to the current compiled default.
        relaySetDao.updateFilterJson("global", Json.encodeToString(FeedFilter.globalDefault))
    }

    suspend fun defaultSet(): RelaySetEntity? = relaySetDao.defaultSet()

    suspend fun getById(id: String): RelaySetEntity? = relaySetDao.getById(id)

    /** Creates a new user-defined relay set and persists it. */
    suspend fun createUserSet(name: String, relayUrls: List<String>) {
        val entity = RelaySetEntity(
            id        = UUID.randomUUID().toString(),
            name      = name,
            relayUrls = Json.encodeToString(relayUrls),
            isDefault = false,
            isBuiltIn = false,
        )
        relaySetDao.upsert(entity)
    }

    /** Deletes a user-created relay set. Built-in sets are protected by the DAO. */
    suspend fun deleteUserSet(id: String) = relaySetDao.deleteUserSet(id)

    /** Decodes the relay URL list from JSON. */
    fun decodeUrls(entity: RelaySetEntity): List<String> =
        Json.decodeFromString(entity.relayUrls)

    /** Decodes the FeedFilter stored on this relay set (lenient — survives field removals). */
    fun decodeFilter(entity: RelaySetEntity): FeedFilter =
        entity.filterJson?.let { runCatching { lenientJson.decodeFromString<FeedFilter>(it) }.getOrNull() } ?: FeedFilter()
}
