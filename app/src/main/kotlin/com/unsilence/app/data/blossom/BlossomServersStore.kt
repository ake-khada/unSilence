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
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.MemoryEventStore
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
class BlossomServersStore internal constructor(
    preferences: Lazy<DataStore<Preferences>>,
    private val signingManager: SigningManager,
    private val memoryEventStore: MemoryEventStore,
    private val scope: CoroutineScope,
    private val publishToRelays: (String, List<String>) -> Unit,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        signingManager: SigningManager,
        memoryEventStore: MemoryEventStore,
        relayPool: dagger.Lazy<RelayPool>,
    ) : this(
        lazy { context.blossomPrefs }, signingManager, memoryEventStore,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        { json, targets -> relayPool.get().publishToRelays(json, targets) },
    )

    private val dataStore by preferences
    private val editMutex = Mutex()
    @Volatile private var activeOwner: String? = null

    private val _selectedServer = MutableStateFlow("")
    val selectedServer: StateFlow<String> = _selectedServer.asStateFlow()

    private val _configuredServers = MutableStateFlow<List<String>>(emptyList())
    val configuredServers: StateFlow<List<String>> = _configuredServers.asStateFlow()

    private val _imageMaxDim = MutableStateFlow(2048)
    val imageMaxDim: StateFlow<Int> = _imageMaxDim.asStateFlow()

    private val _videoQuality = MutableStateFlow(VideoTranscoder.Quality.STANDARD)
    val videoQuality: StateFlow<VideoTranscoder.Quality> = _videoQuality.asStateFlow()

    private var publishJob: Job? = null
    private val initMutex = Mutex()
    @Volatile private var initialized = false

    @Synchronized
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

    @Synchronized
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
        val owner = activeOwner ?: return
        initMutex.withLock {
            if (initialized || activeOwner != owner) return
            doInitialize(owner)
        }
    }

    private suspend fun doInitialize(owner: String) {
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

        synchronized(this) {
            if (activeOwner != owner) return
            _configuredServers.value = servers
            _selectedServer.value = selected
            _imageMaxDim.value = prefs[KEY_IMAGE_MAX_DIM] ?: 2048
            _videoQuality.value = prefs[KEY_VIDEO_QUALITY]?.let { name ->
                VideoTranscoder.Quality.entries.firstOrNull { it.name == name }
            } ?: VideoTranscoder.Quality.STANDARD
        }
        persistServers(owner, servers, selected)
        synchronized(this) {
            if (activeOwner == owner) initialized = true
        }
    }

    suspend fun setSelected(url: String) {
        val owner = synchronized(this) {
            val current = activeOwner ?: return
            if (url !in _configuredServers.value) return
            _selectedServer.value = url
            current
        }
        editMutex.withLock {
            dataStore.edit { it[selectedServerKey(owner)] = url }
        }
        schedulePublish(owner)
    }

    suspend fun addServer(url: String) {
        val normalized = url.trimEnd('/')
        val owner: String
        val updated: List<String>
        val selected: String
        synchronized(this) {
            owner = activeOwner ?: return
            if (normalized in _configuredServers.value) return
            updated = _configuredServers.value + normalized
            _configuredServers.value = updated
            selected = _selectedServer.value
        }
        persistServers(owner, updated, selected)
        schedulePublish(owner)
    }

    suspend fun removeServer(url: String) {
        val owner: String
        val updated: List<String>
        val selected: String
        synchronized(this) {
            owner = activeOwner ?: return
            val servers = _configuredServers.value
            val current = _selectedServer.value
            if (url == current && servers.size <= 1) return
            updated = servers - url
            selected = if (url == current) updated.first() else current
            _configuredServers.value = updated
            _selectedServer.value = selected
        }
        persistServers(owner, updated, selected)
        schedulePublish(owner)
    }

    suspend fun setImageUploadTier(tier: AttachmentQuality) {
        val (maxDimension, outputQuality) = tier.imageSettings()
        _imageMaxDim.value = maxDimension
        editMutex.withLock {
            dataStore.edit {
                it[KEY_IMAGE_MAX_DIM] = maxDimension
                it[KEY_IMAGE_QUALITY] = outputQuality
            }
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

    @Synchronized
    private fun schedulePublish(owner: String) {
        // Persistence can finish after the next owner has already queued an edit.
        if (activeOwner != owner) return
        publishJob?.cancel()
        publishJob = scope.launch {
            delay(PUBLISH_DEBOUNCE_MS)
            publishKind10063(owner)
        }
    }

    private suspend fun publishKind10063(owner: String) {
        val (servers, selected) = synchronized(this) {
            if (activeOwner != owner) return
            _configuredServers.value to _selectedServer.value
        }
        if (servers.isEmpty()) return

        // Selected server first, then rest in order
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
        val signed = signingManager.sign(template, expectedPubkey = owner) ?: run {
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
            sig = signed.sig,
            relayUrl = "",
            replyToId = null,
            rootId = null,
            hasContentWarning = false,
            contentWarningReason = null,
            firstSeenAt = System.currentTimeMillis(),
            relaysSeen = ConcurrentHashMap.newKeySet(),
        )
        synchronized(this) {
            if (activeOwner != owner) return
            memoryEventStore.storeLocalEvent(localEvent)
        }

        val writeRelays = memoryEventStore.writeRelaysFor(owner)
        if (writeRelays.isEmpty()) {
            Log.w(TAG, "publishKind10063: no write relays")
            return
        }
        val json = toEventJson(signed)
        if (activeOwner != owner) return
        publishToRelays(json, writeRelays)
        Log.d(TAG, "publishKind10063: ${ordered.size} servers → ${writeRelays.size} relay(s)")
    }

    private fun serversKey(owner: String) = stringSetPreferencesKey("${owner}_servers")

    private fun selectedServerKey(owner: String) = stringPreferencesKey("${owner}_selected_server")
}
