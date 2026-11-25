package com.unsilence.app.data.drafts

import androidx.core.net.toUri
import android.content.Context
import android.content.ContentResolver
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.yield
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
import kotlinx.serialization.json.Json

private val Context.draftsDataStore: DataStore<Preferences> by preferencesDataStore(name = "drafts")

// This ledger contains only grants acquired by the draft store. Android grants are
// app-wide, so release requires checking the durable drafts of every account.
private val ownedGrants = stringSetPreferencesKey("draft_media_read_grants")
internal const val MAX_DRAFT_MEDIA_URIS = 128

internal interface DraftMediaGrants {
    fun held(): Set<String>
    fun take(uri: String)
    fun release(uri: String)
}

private class PickerGrants(private val resolver: ContentResolver) : DraftMediaGrants {
    override fun held() = resolver.persistedUriPermissions.filter { it.isReadPermission }
        .mapTo(mutableSetOf()) { it.uri.toString() }

    override fun take(uri: String) = resolver.takePersistableUriPermission(
        uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION,
    )

    override fun release(uri: String) = resolver.releasePersistableUriPermission(
        uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION,
    )
}

internal class DraftMediaAccessException : IOException()
internal class DraftMediaLimitException : IOException()

@Singleton
class DraftStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val grants: DraftMediaGrants? = null,
) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(context.draftsDataStore, CoroutineScope(SupervisorJob() + Dispatchers.IO), PickerGrants(context.contentResolver))

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
                    val drafts = runCatching { json.readDrafts(prefs, key.name) }.getOrNull()
                    if (!drafts.isNullOrEmpty()) put(key.name, drafts)
                }
            }
            releaseUnusedGrants()
        } catch (_: IOException) {
            // A subsequent edit reads the durable account record again.
        }
    }

    suspend fun awaitLoaded() = initialRead.join()

    fun draftsFor(pubkey: String): List<Draft> = _drafts.value[pubkey].orEmpty()

    fun draft(pubkey: String, key: String): Draft? =
        DraftMutations.find(draftsFor(pubkey), key)

    fun save(pubkey: String, draft: Draft): Deferred<Result<Unit>> =
        mutate(pubkey, draft) { drafts ->
            val previous = DraftMutations.find(drafts, draft.key)
            DraftMutations.upsert(drafts, draft.copy(hiddenFromFeed = previous?.hiddenFromFeed == true))
        }

    fun hideFromFeed(pubkey: String, displayed: Draft): Deferred<Result<Unit>> =
        mutate(pubkey) { drafts ->
            if (drafts.feedDraft()?.key != displayed.key) drafts else drafts.map { draft ->
                if (draft.isStandalone) draft.copy(hiddenFromFeed = true) else draft
            }
        }

    fun delete(pubkey: String, key: String): Deferred<Result<Unit>> =
        mutate(pubkey) { DraftMutations.delete(it, key) }

    // Enter the fair mutex in submission order, even when callers leave the screen.
    // Publish to observers only after DataStore acknowledges the edit.
    private fun mutate(
        pubkey: String,
        saving: Draft? = null,
        change: (List<Draft>) -> List<Draft>,
    ): Deferred<Result<Unit>> =
        scope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                writes.withLock {
                    awaitLoaded()
                    // The undispatched launch only reserves write order. Resume on
                    // the store dispatcher before calling the picker or DataStore.
                    yield()
                    try {
                        saving?.let { retainSources(pubkey, it) }
                        lateinit var updated: List<Draft>
                        dataStore.edit { prefs ->
                            updated = change(json.readDrafts(prefs, pubkey))
                            json.writeDrafts(prefs, pubkey, updated)
                        }
                        _drafts.value = if (updated.isEmpty()) {
                            _drafts.value - pubkey
                        } else {
                            _drafts.value + (pubkey to updated)
                        }
                    } finally {
                        releaseUnusedGrants()
                    }
                }
                Result.success(Unit)
            } catch (failure: IOException) {
                Result.failure(failure)
            } catch (failure: SerializationException) {
                Result.failure(failure)
            }
        }

    private fun sources(draft: Draft) = draft.blocks.filterIsInstance<DraftBlock.LocalMedia>()

    private suspend fun retainSources(pubkey: String, draft: Draft) {
        val media = sources(draft)
        if (media.isEmpty()) return
        val uris = media.mapTo(mutableSetOf()) { it.uri }
        if (uris.any { !it.startsWith("content://") }) throw DraftMediaAccessException()
        val access = grants ?: throw DraftMediaAccessException()
        val prefs = dataStore.data.first()
        val previous = json.readDrafts(prefs, pubkey)
            .find { it.key == draft.key }?.let(::sources).orEmpty()
        // A previously saved source may since have been revoked. Keep its slot
        // when saving other edits. A freshly picked replacement must earn a grant.
        val retained = previous.mapTo(mutableSetOf()) { it.id to it.uri }
        val needed = media.filterNot { (it.id to it.uri) in retained }.mapTo(mutableSetOf()) { it.uri }
        if (needed.isEmpty()) return
        val held = access.held()
        val missing = needed - held
        if (held.size + missing.size > MAX_DRAFT_MEDIA_URIS) throw DraftMediaLimitException()
        if (missing.isEmpty()) return
        // Record ownership before acquiring. A crash on either side of the
        // draft write can then be reconciled from committed records on startup.
        dataStore.edit { it[ownedGrants] = it[ownedGrants].orEmpty() + missing }
        try {
            missing.forEach(access::take)
        } catch (_: SecurityException) {
            throw DraftMediaAccessException()
        }
    }

    private suspend fun releaseUnusedGrants() {
        val access = grants ?: return
        try {
            val prefs = dataStore.data.first()
            val owned = prefs[ownedGrants].orEmpty()
            if (owned.isEmpty()) return
            val unused = owned.toMutableSet()
            for ((key, value) in prefs.asMap()) {
                if (value !is String) continue
                for (draft in json.readDrafts(prefs, key.name)) {
                    sources(draft).forEach { unused.remove(it.uri) }
                }
            }
            if (unused.isEmpty()) return
            val held = access.held()
            for (uri in unused) if (uri in held) access.release(uri)
            dataStore.edit { if (owned == unused) it.remove(ownedGrants) else it[ownedGrants] = owned - unused }
        } catch (_: IOException) {
            // Keep the durable ledger for the next mutation or process start.
        } catch (_: SerializationException) {
            // An unreadable account could still reference a grant.
        } catch (_: SecurityException) {
            // Permission changed during cleanup. Reconcile again on the next pass.
        }
    }
}
