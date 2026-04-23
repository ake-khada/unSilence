package com.unsilence.app.data.relay

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.unsilence.app.data.memory.PinnedRelay
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.relayPrefs: DataStore<Preferences> by preferencesDataStore(name = "relay_prefs")

private val KEY_INDEXER_URLS = stringSetPreferencesKey("indexer_urls")
private const val PINNED_PREFIX = "pinned_"
private const val LAST_SEEN_PREFIX = "notif_last_seen_"
private val KEY_LAST_MONITOR_FETCH = longPreferencesKey("last_monitor_fetch_at")

@Singleton
class RelayPreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore get() = context.relayPrefs
    private val editMutex = Mutex()

    // ─── Indexer URLs ───────────────────────────────────────────────────────

    private val _indexerUrls: StateFlow<List<String>> =
        dataStore.data
            .map { prefs -> prefs[KEY_INDEXER_URLS]?.toList()?.sorted() ?: emptyList() }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Non-blocking snapshot of cached indexer URLs. May return empty before DataStore loads. */
    fun indexerRelayUrlsSnapshot(): List<String> = _indexerUrls.value

    /** Suspending read — waits for DataStore to load from disk. Use in bootstrap. */
    suspend fun indexerRelayUrlsSuspending(): List<String> =
        dataStore.data.first()[KEY_INDEXER_URLS]?.toList()?.sorted() ?: emptyList()

    /** Reactive Flow of indexer URLs. */
    fun indexerRelayUrlsFlow(): Flow<List<String>> = _indexerUrls

    suspend fun setIndexerUrls(urls: List<String>) = editMutex.withLock {
        dataStore.edit { prefs ->
            prefs[KEY_INDEXER_URLS] = urls.toSet()
        }
    }

    suspend fun addIndexerUrl(url: String) = editMutex.withLock {
        dataStore.edit { prefs ->
            val current = prefs[KEY_INDEXER_URLS] ?: emptySet()
            prefs[KEY_INDEXER_URLS] = current + url
        }
    }

    suspend fun removeIndexerUrl(url: String) = editMutex.withLock {
        dataStore.edit { prefs ->
            val current = prefs[KEY_INDEXER_URLS] ?: emptySet()
            prefs[KEY_INDEXER_URLS] = current - url
        }
    }

    // ─── Relay Monitor Staleness ───────────────────────────────────────────

    /** Returns 0L if never fetched. */
    suspend fun lastMonitorFetchAt(): Long =
        dataStore.data.first()[KEY_LAST_MONITOR_FETCH] ?: 0L

    suspend fun setLastMonitorFetchAt(timestamp: Long) {
        dataStore.edit { prefs -> prefs[KEY_LAST_MONITOR_FETCH] = timestamp }
    }

    // ─── Pinned Relays ──────────────────────────────────────────────────────

    /**
     * Pinned relays are stored as individual string keys:
     *   pinned_{pubkey}_{url} = "label|addedAt"
     *
     * This avoids JSON serialization while keeping per-relay atomicity.
     */
    fun pinnedRelaysFlow(pubkey: String): Flow<List<PinnedRelay>> =
        dataStore.data
            .map { prefs -> parsePinnedRelays(prefs, pubkey) }
            .distinctUntilChanged()

    fun pinnedRelaysSnapshot(pubkey: String): List<PinnedRelay> {
        // Best-effort from the StateFlow; may be slightly stale on first call
        return emptyList() // Callers should prefer the Flow
    }

    suspend fun upsertPinnedRelay(pubkey: String, url: String, displayLabel: String?) {
        val key = stringPreferencesKey("${PINNED_PREFIX}${pubkey}_$url")
        val value = "${displayLabel ?: ""}|${System.currentTimeMillis() / 1000}"
        dataStore.edit { prefs -> prefs[key] = value }
    }

    suspend fun deletePinnedRelay(pubkey: String, url: String) {
        val key = stringPreferencesKey("${PINNED_PREFIX}${pubkey}_$url")
        dataStore.edit { prefs -> prefs.remove(key) }
    }

    suspend fun clearAllPinnedRelays() {
        dataStore.edit { prefs ->
            val keysToRemove = prefs.asMap().keys.filter { it.name.startsWith(PINNED_PREFIX) }
            keysToRemove.forEach { prefs.remove(it) }
        }
    }

    private fun parsePinnedRelays(prefs: Preferences, pubkey: String): List<PinnedRelay> {
        val prefix = "${PINNED_PREFIX}${pubkey}_"
        return prefs.asMap()
            .filter { (key, _) -> key.name.startsWith(prefix) }
            .mapNotNull { (key, value) ->
                val url = key.name.removePrefix(prefix)
                val parts = (value as? String)?.split("|", limit = 2) ?: return@mapNotNull null
                val label = parts[0].ifEmpty { null }
                val addedAt = parts.getOrNull(1)?.toLongOrNull() ?: (System.currentTimeMillis() / 1000)
                PinnedRelay(pubkey = pubkey, url = url, displayLabel = label, addedAt = addedAt)
            }
            .sortedBy { it.addedAt }
    }

    // ─── Notification Last-Seen ────────────────────────────────────────────

    fun getLastSeenTimestamp(pubkey: String): Flow<Long> =
        dataStore.data
            .map { prefs -> prefs[longPreferencesKey("$LAST_SEEN_PREFIX$pubkey")] ?: 0L }
            .distinctUntilChanged()

    suspend fun setLastSeenTimestamp(pubkey: String, timestamp: Long) {
        dataStore.edit { prefs ->
            prefs[longPreferencesKey("$LAST_SEEN_PREFIX$pubkey")] = timestamp
        }
    }
}
