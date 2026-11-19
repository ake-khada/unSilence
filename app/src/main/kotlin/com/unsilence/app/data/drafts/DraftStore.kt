package com.unsilence.app.data.drafts

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.draftsDataStore: DataStore<Preferences> by preferencesDataStore(name = "drafts")

@Singleton
class DraftStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(context.draftsDataStore, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val writes = Mutex()

    private val _drafts = MutableStateFlow<Map<String, List<Draft>>>(emptyMap())
    val drafts: StateFlow<Map<String, List<Draft>>> = _drafts.asStateFlow()

    private val initialRead = scope.launch {
        try {
            val prefs = dataStore.data.first()
            _drafts.value = buildMap {
                for ((key, value) in prefs.asMap()) {
                    if (value !is String) continue
                    val drafts = runCatching { json.decodeFromString<List<Draft>>(value) }.getOrNull()
                    if (!drafts.isNullOrEmpty()) put(key.name, drafts)
                }
            }
        } catch (_: IOException) {
            // A subsequent edit reads the durable account record again.
        }
    }

    suspend fun awaitLoaded() = initialRead.join()

    fun draftsFor(pubkey: String): List<Draft> = _drafts.value[pubkey].orEmpty()

    fun draft(pubkey: String, key: String): Draft? =
        DraftMutations.find(draftsFor(pubkey), key)

    fun save(pubkey: String, draft: Draft): Deferred<Result<Unit>> =
        mutate(pubkey) { DraftMutations.upsert(it, draft) }

    fun delete(pubkey: String, key: String): Deferred<Result<Unit>> =
        mutate(pubkey) { DraftMutations.delete(it, key) }

    // Enter the fair mutex in submission order, even when callers leave the screen.
    // Publish to observers only after DataStore acknowledges the edit.
    private fun mutate(pubkey: String, change: (List<Draft>) -> List<Draft>): Deferred<Result<Unit>> =
        scope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                writes.withLock {
                    awaitLoaded()
                    val key = stringPreferencesKey(pubkey)
                    lateinit var updated: List<Draft>
                    dataStore.edit { prefs ->
                        val previous = prefs[key]?.let { json.decodeFromString<List<Draft>>(it) }.orEmpty()
                        updated = change(previous)
                        if (updated.isEmpty()) prefs.remove(key) else prefs[key] = json.encodeToString(updated)
                    }
                    _drafts.value = if (updated.isEmpty()) {
                        _drafts.value - pubkey
                    } else {
                        _drafts.value + (pubkey to updated)
                    }
                }
                Result.success(Unit)
            } catch (failure: IOException) {
                Result.failure(failure)
            } catch (failure: SerializationException) {
                Result.failure(failure)
            }
        }
}
