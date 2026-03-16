package com.unsilence.app.ui.relays

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.db.dao.OwnRelayDao
import com.unsilence.app.data.db.entity.OwnRelayEntity
import com.unsilence.app.data.relay.RelayPool
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

@HiltViewModel
class RelayManagementViewModel @Inject constructor(
    private val ownRelayDao: OwnRelayDao,
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
) : ViewModel() {

    val relays: Flow<List<OwnRelayEntity>> = ownRelayDao.allFlow()
    val publishing = MutableStateFlow(false)
    private val publishMutex = Mutex()

    fun addRelay(url: String) {
        val normalizedUrl = normalizeRelayUrl(url) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis() / 1000L
            ownRelayDao.upsert(OwnRelayEntity(url = normalizedUrl, createdAt = now))
            publishKind10002()
        }
    }

    fun removeRelay(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ownRelayDao.delete(url)
            publishKind10002()
        }
    }

    fun toggleRead(relay: OwnRelayEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis() / 1000L
            ownRelayDao.upsert(relay.copy(read = !relay.read, createdAt = now))
            publishKind10002()
        }
    }

    fun toggleWrite(relay: OwnRelayEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis() / 1000L
            ownRelayDao.upsert(relay.copy(write = !relay.write, createdAt = now))
            publishKind10002()
        }
    }

    private suspend fun publishKind10002(): Unit = publishMutex.withLock {
        publishing.value = true
        try {
            val now = System.currentTimeMillis() / 1000L
            val allRelays = ownRelayDao.getAll()
            val tags = allRelays.mapNotNull { relay ->
                when {
                    relay.read && relay.write -> arrayOf("r", relay.url)
                    relay.read               -> arrayOf("r", relay.url, "read")
                    relay.write              -> arrayOf("r", relay.url, "write")
                    else                     -> null
                }
            }.toTypedArray()

            val template = EventTemplate<Event>(
                createdAt = now,
                kind      = 10002,
                tags      = tags,
                content   = "",
            )
            val signed = signingManager.sign(template) ?: return

            // Bump stored created_at so echoed events don't overwrite local state
            for (relay in allRelays) {
                if (relay.createdAt < now) ownRelayDao.upsert(relay.copy(createdAt = now))
            }

            val eventJson = toEventJson(signed)
            val writeUrls = allRelays.filter { it.write }.map { it.url }
            val indexerUrls = listOf(
                "wss://purplepag.es",
                "wss://user.kindpag.es",
                "wss://indexer.coracle.social",
                "wss://antiprimal.net",
            )
            relayPool.publishToRelays(eventJson, (writeUrls + indexerUrls).distinct())
        } finally {
            publishing.value = false
        }
    }

    private fun toEventJson(event: Event): String = buildJsonObject {
        put("id",         event.id)
        put("pubkey",     event.pubKey)
        put("created_at", event.createdAt)
        put("kind",       event.kind)
        put("tags",       buildJsonArray {
            event.tags.forEach { row ->
                add(buildJsonArray { row.forEach { cell -> add(JsonPrimitive(cell)) } })
            }
        })
        put("content",    event.content)
        put("sig",        event.sig)
    }.toString()

    companion object {
        /** Normalize relay URL: ensure wss:// prefix, trim, strip trailing slash. */
        internal fun normalizeRelayUrl(raw: String): String? {
            var url = raw.trim().removeSuffix("/")
            if (url.isBlank()) return null
            // Strip http(s):// if user pasted a web URL, replace with wss://
            url = url.removePrefix("https://").removePrefix("http://")
            // Add wss:// if no scheme present
            if (!url.startsWith("wss://") && !url.startsWith("ws://")) {
                url = "wss://$url"
            }
            // Basic validation: must have a dot in the host
            val host = url.removePrefix("wss://").removePrefix("ws://").split("/").firstOrNull() ?: return null
            if (!host.contains(".")) return null
            return url
        }
    }
}
