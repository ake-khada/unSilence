package com.unsilence.app.data.blossom

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.tagsToJson
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.toEventJson
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BlossomServersStore"
private const val PUBLISH_DEBOUNCE_MS = 500L

private val Context.blossomPrefs: DataStore<Preferences> by preferencesDataStore(name = "blossom_prefs")

private val KEY_IMAGE_MAX_DIM = intPreferencesKey("image_max_dim")
private val KEY_IMAGE_QUALITY = intPreferencesKey("image_quality")
private val KEY_VIDEO_QUALITY = stringPreferencesKey("video_quality")

@Singleton
class BlossomServersStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: dagger.Lazy<RelayPool>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore get() = context.blossomPrefs
    private val editMutex = Mutex()
    @Volatile private var activeOwner: String? = null

    private val _selectedServer = MutableStateFlow("")
    val selectedServer: StateFlow<String> = _selectedServer.asStateFlow()

    private val _configuredServers = MutableStateFlow<List<String>>(emptyList())
    val configuredServers: StateFlow<List<String>> = _configuredServers.asStateFlow()

    private val _imageMaxDim = MutableStateFlow(1600)
    val imageMaxDim: StateFlow<Int> = _imageMaxDim.asStateFlow()

    private val _imageQuality = MutableStateFlow(85)
    val imageQuality: StateFlow<Int> = _imageQuality.asStateFlow()

    private val _videoQuality = MutableStateFlow(VideoTranscoder.Quality.STANDARD)
    val videoQuality: StateFlow<VideoTranscoder.Quality> = _videoQuality.asStateFlow()

    private var publishJob: Job? = null
    private val initMutex = Mutex()
    @Volatile private var initialized = false

    fun selectOwner(pubkeyHex: String) {
        val normalized = pubkeyHex.lowercase()
        if (activeOwner == normalized) return
        activeOwner = normalized
        initialized = false
        publishJob?.cancel()
        publishJob = null
        _configuredServers.value = emptyList()
        _selectedServer.value = ""
    }

    fun clearActiveOwner() {
        activeOwner = null
        initialized = false
        publishJob?.cancel()
        publishJob = null
        _configuredServers.value = emptyList()
        _selectedServer.value = ""
    }

    /**
     * Hydrate from MES (kind-10063 from relay) or DataStore, or seed with defaults.
     * Called once by ViewModel on first access. Mutex-guarded — multiple ViewModels
     * race this on startup; later callers wait, then return without re-running.
     */
    suspend fun initialize() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            initialized = true
            doInitialize()
        }
    }

    private suspend fun doInitialize() {
        val owner = activeOwner ?: keyManager.getPublicKeyHex()?.lowercase() ?: return
        // 1. Try MES (published kind-10063)
        val mesServers = memoryEventStore.blossomServersFor(owner)

        // 2. Try DataStore (local edits not yet published)
        val prefs = dataStore.data.first()
        val dsServers = prefs[serversKey(owner)]?.toList() ?: emptyList()
        val dsSelected = prefs[selectedServerKey(owner)] ?: ""

        val servers: List<String>
        val selected: String

        when {
            mesServers.isNotEmpty() -> {
                servers = mesServers
                selected = dsSelected.takeIf { it in mesServers } ?: mesServers.first()
            }
            dsServers.isNotEmpty() -> {
                servers = dsServers
                selected = dsSelected.takeIf { it in dsServers } ?: dsServers.first()
            }
            else -> {
                servers = DEFAULT_BLOSSOM_SERVERS.map { it.url }
                selected = DEFAULT_BLOSSOM_SERVERS.first { it.isDefaultSelected }.url
            }
        }

        _configuredServers.value = servers
        _selectedServer.value = selected
        _imageMaxDim.value = prefs[KEY_IMAGE_MAX_DIM] ?: 1600
        _imageQuality.value = prefs[KEY_IMAGE_QUALITY] ?: 85
        _videoQuality.value = prefs[KEY_VIDEO_QUALITY]?.let { name ->
            VideoTranscoder.Quality.entries.firstOrNull { it.name == name }
        } ?: VideoTranscoder.Quality.STANDARD
        // Persist to DataStore
        persistServers(owner, servers, selected)
    }

    suspend fun setSelected(url: String) {
        if (url !in _configuredServers.value) return
        val owner = activeOwner ?: return
        _selectedServer.value = url
        editMutex.withLock {
            dataStore.edit { it[selectedServerKey(owner)] = url }
        }
        schedulePublish()
    }

    suspend fun addServer(url: String) {
        val normalized = url.trimEnd('/')
        if (normalized in _configuredServers.value) return
        val updated = _configuredServers.value + normalized
        _configuredServers.value = updated
        persistServers(activeOwner ?: return, updated, _selectedServer.value)
        schedulePublish()
    }

    suspend fun removeServer(url: String) {
        val owner = activeOwner ?: return
        if (url == _selectedServer.value && _configuredServers.value.size <= 1) return
        val updated = _configuredServers.value - url
        _configuredServers.value = updated
        if (_selectedServer.value == url) {
            val newSelected = updated.firstOrNull() ?: return
            _selectedServer.value = newSelected
        }
        persistServers(owner, updated, _selectedServer.value)
        schedulePublish()
    }

    suspend fun setImageMaxDim(px: Int) {
        _imageMaxDim.value = px
        editMutex.withLock {
            dataStore.edit { it[KEY_IMAGE_MAX_DIM] = px }
        }
    }

    suspend fun setImageQuality(q: Int) {
        _imageQuality.value = q
        editMutex.withLock {
            dataStore.edit { it[KEY_IMAGE_QUALITY] = q }
        }
    }

    suspend fun setVideoQuality(quality: VideoTranscoder.Quality) {
        _videoQuality.value = quality
        editMutex.withLock {
            dataStore.edit { it[KEY_VIDEO_QUALITY] = quality.name }
        }
    }

    private suspend fun persistServers(owner: String, servers: List<String>, selected: String) {
        editMutex.withLock {
            dataStore.edit {
                it[serversKey(owner)] = servers.toSet()
                it[selectedServerKey(owner)] = selected
            }
        }
    }

    private fun schedulePublish() {
        publishJob?.cancel()
        publishJob = scope.launch {
            delay(PUBLISH_DEBOUNCE_MS)
            publishKind10063()
        }
    }

    private suspend fun publishKind10063() {
        val ownPubkey = keyManager.getPublicKeyHex() ?: return
        val servers = _configuredServers.value
        if (servers.isEmpty()) return

        // Selected server first, then rest in order
        val selected = _selectedServer.value
        val ordered = buildList {
            if (selected.isNotEmpty()) add(selected)
            for (s in servers) { if (s != selected) add(s) }
        }

        val tags = ordered.map { arrayOf("server", it) }.toTypedArray()
        val nowSeconds = System.currentTimeMillis() / 1000L

        val template = EventTemplate<Event>(
            createdAt = nowSeconds,
            kind = 10063,
            tags = tags,
            content = "",
        )
        val signed = signingManager.sign(template) ?: run {
            Log.e(TAG, "publishKind10063: signing failed")
            return
        }

        // Store locally in MES so snapshot captures it
        val tagsList = signed.tags.map { it.toList() }
        val localEvent = NostrEvent(
            id = signed.id,
            pubkey = signed.pubKey,
            kind = 10063,
            content = signed.content,
            createdAt = signed.createdAt,
            tags = tagsList,
            tagsJson = tagsToJson(tagsList),
            sig = signed.sig,
            relayUrl = "",
            replyToId = null,
            rootId = null,
            hasContentWarning = false,
            contentWarningReason = null,
            firstSeenAt = System.currentTimeMillis(),
            relaysSeen = ConcurrentHashMap.newKeySet(),
        )
        memoryEventStore.storeLocalEvent(localEvent)

        val writeRelays = memoryEventStore.writeRelaysFor(ownPubkey)
        if (writeRelays.isEmpty()) {
            Log.w(TAG, "publishKind10063: no write relays")
            return
        }
        relayPool.get().publishToRelays(toEventJson(signed), writeRelays)
        Log.d(TAG, "publishKind10063: ${ordered.size} servers → ${writeRelays.size} relay(s)")
    }

    private fun serversKey(owner: String) = stringSetPreferencesKey("${owner}_servers")

    private fun selectedServerKey(owner: String) = stringPreferencesKey("${owner}_selected_server")
}
