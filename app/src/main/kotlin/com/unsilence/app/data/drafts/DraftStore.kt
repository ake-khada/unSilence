package com.unsilence.app.data.drafts

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DraftStore"
private val Context.draftsDataStore: DataStore<Preferences> by preferencesDataStore(name = "drafts")

@Singleton
class DraftStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val versions = mutableMapOf<String, Long>()

    private val _drafts = MutableStateFlow<Map<String, List<Draft>>>(emptyMap())
    val drafts: StateFlow<Map<String, List<Draft>>> = _drafts.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            runCatching {
                val prefs = context.draftsDataStore.data.first()
                val hydrated = buildMap {
                    prefs.asMap().forEach { (key, value) ->
                        val encoded = value as? String ?: return@forEach
                        runCatching { json.decodeFromString<List<Draft>>(encoded) }
                            .getOrNull()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { put(key.name, it) }
                    }
                }
                synchronized(lock) {
                    _drafts.value = mergeDraftMaps(hydrated, _drafts.value)
                }
            }.onFailure { Log.w(TAG, "Draft hydration failed: ${it.message}") }
            loaded.complete(Unit)
        }
    }

    suspend fun awaitLoaded() {
        loaded.await()
    }

    fun draftsFor(pubkey: String): List<Draft> = _drafts.value[pubkey].orEmpty()

    fun draft(pubkey: String, key: String): Draft? =
        DraftMutations.find(draftsFor(pubkey), key)

    fun save(pubkey: String, draft: Draft) {
        val (next, version) = synchronized(lock) {
            val next = DraftMutations.upsert(draftsFor(pubkey), draft)
            _drafts.value = _drafts.value + (pubkey to next)
            next to bumpVersion(pubkey)
        }
        persist(pubkey, next, version)
    }

    fun delete(pubkey: String, key: String) {
        val (next, version) = synchronized(lock) {
            val next = DraftMutations.delete(draftsFor(pubkey), key)
            _drafts.value = if (next.isEmpty()) {
                _drafts.value - pubkey
            } else {
                _drafts.value + (pubkey to next)
            }
            next to bumpVersion(pubkey)
        }
        persist(pubkey, next, version)
    }

    private fun persist(pubkey: String, list: List<Draft>, version: Long) {
        scope.launch {
            runCatching {
                val isLatest = synchronized(lock) { versions[pubkey] == version }
                if (!isLatest) return@launch
                context.draftsDataStore.edit { prefs ->
                    val key = stringPreferencesKey(pubkey)
                    if (list.isEmpty()) {
                        prefs.remove(key)
                    } else {
                        prefs[key] = json.encodeToString(list)
                    }
                }
            }.onFailure { Log.w(TAG, "Draft persist failed: ${it.message}") }
        }
    }

    private fun bumpVersion(pubkey: String): Long {
        val next = (versions[pubkey] ?: 0L) + 1L
        versions[pubkey] = next
        return next
    }

    private fun mergeDraftMaps(
        hydrated: Map<String, List<Draft>>,
        current: Map<String, List<Draft>>,
    ): Map<String, List<Draft>> {
        if (current.isEmpty()) return hydrated
        val merged = hydrated.toMutableMap()
        current.forEach { (pubkey, drafts) ->
            var list = merged[pubkey].orEmpty()
            drafts.forEach { draft -> list = DraftMutations.upsert(list, draft) }
            if (list.isNotEmpty()) merged[pubkey] = list
        }
        return merged
    }
}
