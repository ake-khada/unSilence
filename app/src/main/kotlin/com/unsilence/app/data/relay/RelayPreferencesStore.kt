package com.unsilence.app.data.relay

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.util.Log
import com.unsilence.app.data.DEFAULT_WOT_PROVIDER_PUBKEY
import com.unsilence.app.data.DEFAULT_WOT_RELAY
import com.unsilence.app.data.memory.SensitiveContentMode
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
private val KEY_LAST_TRUST_FETCH = longPreferencesKey("last_trust_fetch_at")
private val KEY_LAST_TRUST_RELAY_URLS = stringSetPreferencesKey("last_trust_relay_urls")
private val KEY_SENSITIVE_CONTENT_MODE = stringPreferencesKey("sensitive_content_mode")
private val KEY_WOT_PROVIDER_PUBKEY = stringPreferencesKey("wot_provider_pubkey")
private val KEY_WOT_PROVIDER_RELAY = stringPreferencesKey("wot_provider_relay")
private val KEY_WOT_PROVIDER_SOURCE = stringPreferencesKey("wot_provider_source")
private val KEY_LAST_WOT_FETCH = longPreferencesKey("last_wot_fetch_at")
private val KEY_LAST_WOT_TARGETS_HASH = stringPreferencesKey("last_wot_targets_hash")

enum class WotProviderSource {
    DEFAULT,
    OWN_10040,
    CUSTOM,
}

data class WotProviderPrefs(
    val pubkey: String,
    val relay: String,
    val source: WotProviderSource,
)

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

    // ─── Trust Score Staleness ────────────────────────────────────────────

    suspend fun lastTrustFetchAt(): Long =
        dataStore.data.first()[KEY_LAST_TRUST_FETCH] ?: 0L

    suspend fun lastTrustRelayUrls(): Set<String> =
        dataStore.data.first()[KEY_LAST_TRUST_RELAY_URLS] ?: emptySet()

    /** Advance timestamp and covered relay set atomically after a successful fetch. */
    suspend fun setLastTrustFetch(timestamp: Long, relayUrls: Set<String>) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_TRUST_FETCH] = timestamp
            prefs[KEY_LAST_TRUST_RELAY_URLS] = relayUrls
        }
    }

    // ─── NIP-85 WoT Provider ───────────────────────────────────────────────

    fun wotProviderPrefsFlow(): Flow<WotProviderPrefs> =
        dataStore.data
            .map { prefs -> prefs.toWotProviderPrefs() }
            .distinctUntilChanged()

    suspend fun wotProviderPrefsSuspending(): WotProviderPrefs =
        dataStore.data.first().toWotProviderPrefs()

    suspend fun setWotProvider(pubkey: String, relay: String, source: WotProviderSource) {
        val normalizedPubkey = normalizeHexPubkey(pubkey) ?: DEFAULT_WOT_PROVIDER_PUBKEY
        val normalizedRelay = normalizeRelayUrl(relay) ?: DEFAULT_WOT_RELAY
        dataStore.edit { prefs ->
            prefs[KEY_WOT_PROVIDER_PUBKEY] = normalizedPubkey
            prefs[KEY_WOT_PROVIDER_RELAY] = normalizedRelay
            prefs[KEY_WOT_PROVIDER_SOURCE] = source.name
        }
    }

    suspend fun lastWotFetchAt(): Long =
        dataStore.data.first()[KEY_LAST_WOT_FETCH] ?: 0L

    suspend fun lastWotTargetsHash(): String =
        dataStore.data.first()[KEY_LAST_WOT_TARGETS_HASH].orEmpty()

    /** Advance WoT staleness gate atomically after a successful provider-target fetch. */
    suspend fun setLastWotFetch(timestamp: Long, targetsHash: String) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_WOT_FETCH] = timestamp
            prefs[KEY_LAST_WOT_TARGETS_HASH] = targetsHash
        }
    }

    // ─── Pinned Relays ──────────────────────────────────────────────────────

    /**
     * Pinned relays are stored as individual string keys:
     *   pinned_{pubkey}_{url} = "label|addedAt"
     *
     * This avoids JSON serialization while keeping per-relay atomicity.
     */
    /** One-time retirement of the old local pinned-relay store — the feed carousel now sources
     *  the user's kind-10012 favorites directly (single source of truth, see FeedViewModel).
     *  We deliberately do NOT auto-publish a kind-10012 from old pins: silently signing a list
     *  event on app upgrade is wrong. Old pins not already favorited simply vanish. Idempotent. */
    suspend fun retirePinnedStore() {
        dataStore.edit { prefs ->
            val pinned = prefs.asMap().keys.filter { it.name.startsWith(PINNED_PREFIX) }
            if (pinned.isNotEmpty()) {
                Log.w("RelayPrefs", "Pinned store retired — ${pinned.size} entries dropped (carousel now reads kind-10012 favorites)")
                pinned.forEach { prefs.remove(it) }
            }
        }
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

    // ─── Sensitive Content Mode ────────────────────────────────────────────

    fun sensitiveContentModeFlow(): Flow<SensitiveContentMode> =
        dataStore.data
            .map { prefs ->
                val raw = prefs[KEY_SENSITIVE_CONTENT_MODE]
                SensitiveContentMode.entries.firstOrNull { it.name == raw }
                    ?: SensitiveContentMode.BLUR
            }
            .distinctUntilChanged()

    suspend fun setSensitiveContentMode(mode: SensitiveContentMode) {
        dataStore.edit { prefs -> prefs[KEY_SENSITIVE_CONTENT_MODE] = mode.name }
    }

    private fun Preferences.toWotProviderPrefs(): WotProviderPrefs {
        val source = prefsWotSource(this[KEY_WOT_PROVIDER_SOURCE])
        return WotProviderPrefs(
            pubkey = normalizeHexPubkey(this[KEY_WOT_PROVIDER_PUBKEY]) ?: DEFAULT_WOT_PROVIDER_PUBKEY,
            relay = normalizeRelayUrl(this[KEY_WOT_PROVIDER_RELAY] ?: DEFAULT_WOT_RELAY) ?: DEFAULT_WOT_RELAY,
            source = source,
        )
    }

    private fun prefsWotSource(raw: String?): WotProviderSource =
        WotProviderSource.entries.firstOrNull { it.name == raw } ?: WotProviderSource.DEFAULT

    private fun normalizeHexPubkey(pubkey: String?): String? {
        val normalized = pubkey?.trim()?.lowercase() ?: return null
        if (normalized.length != 64) return null
        return normalized.takeIf { value -> value.all { it in '0'..'9' || it in 'a'..'f' } }
    }
}
