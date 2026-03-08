package com.unsilence.app.data.repository

import com.unsilence.app.data.db.dao.RelaySetDao
import com.unsilence.app.data.db.entity.RelaySetEntity
import com.unsilence.app.domain.model.FeedFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

val GLOBAL_RELAY_URLS = listOf(
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://nostr.mom",
    "wss://relay.nostr.net",
)

@Singleton
class RelaySetRepository @Inject constructor(
    private val relaySetDao: RelaySetDao,
) {
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
        // Always sync the filter to the current compiled default (picks up minReactions changes).
        relaySetDao.updateFilterJson("global", Json.encodeToString(FeedFilter.globalDefault))
    }

    suspend fun defaultSet(): RelaySetEntity? = relaySetDao.defaultSet()

    suspend fun getById(id: String): RelaySetEntity? = relaySetDao.getById(id)

    /** Decodes the relay URL list from JSON. */
    fun decodeUrls(entity: RelaySetEntity): List<String> =
        Json.decodeFromString(entity.relayUrls)

    /** Decodes the FeedFilter stored on this relay set. */
    fun decodeFilter(entity: RelaySetEntity): FeedFilter =
        entity.filterJson?.let { Json.decodeFromString(it) } ?: FeedFilter()
}
