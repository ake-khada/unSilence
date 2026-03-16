package com.unsilence.app.ui.relays

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.db.dao.NostrRelaySetDao
import com.unsilence.app.data.db.dao.RelayConfigDao
import com.unsilence.app.data.db.entity.NostrRelaySetEntity
import com.unsilence.app.data.db.entity.NostrRelaySetMemberEntity
import com.unsilence.app.data.db.entity.RelayConfigEntity
import com.unsilence.app.data.relay.RelayPool
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
    private val relayConfigDao: RelayConfigDao,
    private val nostrRelaySetDao: NostrRelaySetDao,
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
) : ViewModel() {

    val ownerPubkey: String? get() = keyManager.getPublicKeyHex()

    /** Kind 10002 read/write relays. */
    val readWriteRelays: Flow<List<RelayConfigEntity>> = relayConfigDao.getReadWriteRelays()

    /** Kind 10006 blocked relays. */
    val blockedRelays: Flow<List<RelayConfigEntity>> = relayConfigDao.getBlockedRelays()

    /** Kind 10007 search relays. */
    val searchRelays: Flow<List<RelayConfigEntity>> = relayConfigDao.getSearchRelays()

    /** Kind 10012 favorite relays. */
    val favoriteRelays: Flow<List<RelayConfigEntity>> = relayConfigDao.getFavoriteRelays()

    /** Kind 30002 relay sets. */
    val relaySets: Flow<List<NostrRelaySetEntity>> = nostrRelaySetDao.getAllSets()

    val publishing = MutableStateFlow(false)
    private val publishMutex = Mutex()

    // ── Kind 10002: Read/Write relays ─────────────────────────────────────────

    fun addReadWriteRelay(url: String) {
        val normalized = normalizeRelayUrl(url) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            relayConfigDao.insert(
                RelayConfigEntity(
                    kind = 10002,
                    relayUrl = normalized,
                    marker = null,  // both read + write
                    eventCreatedAt = nowSeconds(),
                )
            )
            publishChanges(10002)
        }
    }

    fun removeReadWriteRelay(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            relayConfigDao.deleteRelay(10002, url)
            publishChanges(10002)
        }
    }

    fun toggleMarker(relay: RelayConfigEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            // Cycle: both → read-only → write-only → both
            val newMarker = when (relay.marker) {
                null    -> "read"
                "read"  -> "write"
                "write" -> null
                else    -> null
            }
            relayConfigDao.insert(relay.copy(marker = newMarker, eventCreatedAt = nowSeconds()))
            publishChanges(10002)
        }
    }

    // ── Kind 10006: Blocked relays ────────────────────────────────────────────

    fun addBlockedRelay(url: String) {
        val normalized = normalizeRelayUrl(url) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            relayConfigDao.insert(
                RelayConfigEntity(kind = 10006, relayUrl = normalized, eventCreatedAt = nowSeconds())
            )
            relayPool.onBlockedRelaysChanged(relayConfigDao.blockedRelayUrls().toSet())
            publishChanges(10006)
        }
    }

    fun removeBlockedRelay(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            relayConfigDao.deleteRelay(10006, url)
            relayPool.refreshBlockedRelays()
            publishChanges(10006)
        }
    }

    // ── Kind 10007: Search relays ─────────────────────────────────────────────

    fun addSearchRelay(url: String) {
        val normalized = normalizeRelayUrl(url) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            relayConfigDao.insert(
                RelayConfigEntity(kind = 10007, relayUrl = normalized, eventCreatedAt = nowSeconds())
            )
            publishChanges(10007)
        }
    }

    fun removeSearchRelay(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            relayConfigDao.deleteRelay(10007, url)
            publishChanges(10007)
        }
    }

    // ── Kind 10012: Favorite relays ────────────────────────────────────────────

    fun addFavoriteRelay(url: String) {
        val normalized = normalizeRelayUrl(url) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            relayConfigDao.insert(
                RelayConfigEntity(kind = 10012, relayUrl = normalized, eventCreatedAt = nowSeconds())
            )
            publishChanges(10012)
        }
    }

    fun removeFavoriteRelay(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            relayConfigDao.deleteRelay(10012, url)
            publishChanges(10012)
        }
    }

    fun addFavoriteSetRef(setRef: String) {
        viewModelScope.launch(Dispatchers.IO) {
            relayConfigDao.insert(
                RelayConfigEntity(kind = 10012, relayUrl = "", setRef = setRef, eventCreatedAt = nowSeconds())
            )
            publishChanges(10012)
        }
    }

    fun removeFavoriteSetRef(setRef: String) {
        viewModelScope.launch(Dispatchers.IO) {
            relayConfigDao.deleteBySetRef(10012, setRef)
            publishChanges(10012)
        }
    }

    // ── Kind 30002: Relay sets ────────────────────────────────────────────────

    fun createRelaySet(name: String, relays: List<String>) {
        val dTag = name.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val members = relays.mapNotNull { normalizeRelayUrl(it) }
                .map { NostrRelaySetMemberEntity(setDTag = dTag, ownerPubkey = pk, relayUrl = it) }
            nostrRelaySetDao.replaceSet(
                NostrRelaySetEntity(dTag = dTag, ownerPubkey = pk, title = name),
                members,
                nowSeconds(),
            )
            publishRelaySet(dTag)
        }
    }

    fun deleteRelaySet(dTag: String) {
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            nostrRelaySetDao.deleteMembersByDTag(dTag, pk)
            nostrRelaySetDao.deleteSet(dTag, pk)
            // Publish empty relay set to delete on relays (NIP-51 deletion)
            publishRelaySet(dTag)
        }
    }

    fun getSetMembers(dTag: String): Flow<List<NostrRelaySetMemberEntity>> {
        val pk = ownerPubkey ?: return emptyFlow()
        return nostrRelaySetDao.getSetMembers(dTag, pk)
    }

    fun addRelayToSet(dTag: String, url: String) {
        val normalized = normalizeRelayUrl(url) ?: return
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            nostrRelaySetDao.insertMembers(
                listOf(NostrRelaySetMemberEntity(setDTag = dTag, ownerPubkey = pk, relayUrl = normalized))
            )
            publishRelaySet(dTag)
        }
    }

    fun removeRelayFromSet(dTag: String, url: String) {
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            nostrRelaySetDao.deleteMember(dTag, pk, url)
            publishRelaySet(dTag)
        }
    }

    // ── Publishing ────────────────────────────────────────────────────────────

    private suspend fun publishChanges(kind: Int): Unit = publishMutex.withLock {
        publishing.value = true
        try {
            val now = nowSeconds()
            val configs = relayConfigDao.getAllReadWriteRelays()

            val tags: Array<Array<String>>
            val publishKind: Int

            when (kind) {
                10002 -> {
                    val allKind10002 = relayConfigDao.getAllReadWriteRelays()
                    tags = allKind10002.mapNotNull { relay ->
                        val isRead = relay.marker == null || relay.marker == "read"
                        val isWrite = relay.marker == null || relay.marker == "write"
                        when {
                            isRead && isWrite -> arrayOf("r", relay.relayUrl)
                            isRead            -> arrayOf("r", relay.relayUrl, "read")
                            isWrite           -> arrayOf("r", relay.relayUrl, "write")
                            else              -> null
                        }
                    }.toTypedArray()
                    publishKind = 10002
                }
                10006, 10007 -> {
                    val list = when (kind) {
                        10006 -> relayConfigDao.blockedRelayUrls()
                        10007 -> relayConfigDao.searchRelayUrls()
                        else -> emptyList()
                    }
                    tags = list.map { arrayOf("relay", it) }.toTypedArray()
                    publishKind = kind
                }
                10012 -> {
                    val favoriteConfigs = relayConfigDao.getAllFavoriteRelays()
                    val tagsList = mutableListOf<Array<String>>()
                    for (config in favoriteConfigs) {
                        if (config.setRef != null) {
                            tagsList.add(arrayOf("a", config.setRef))
                        } else {
                            tagsList.add(arrayOf("relay", config.relayUrl))
                        }
                    }
                    tags = tagsList.toTypedArray()
                    publishKind = 10012
                }
                else -> return
            }

            val template = EventTemplate<Event>(
                createdAt = now,
                kind      = publishKind,
                tags      = tags,
                content   = "",
            )
            val signed = signingManager.sign(template) ?: return

            val eventJson = toEventJson(signed)
            val writeUrls = configs.filter { it.marker == null || it.marker == "write" }.map { it.relayUrl }
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

    private suspend fun publishRelaySet(dTag: String): Unit = publishMutex.withLock {
        val pk = ownerPubkey ?: return
        publishing.value = true
        try {
            val now = nowSeconds()
            val members = nostrRelaySetDao.getSetMembersSnapshot(dTag, pk)
            val tagsList = mutableListOf<Array<String>>()
            tagsList.add(arrayOf("d", dTag))
            for (member in members) {
                tagsList.add(arrayOf("relay", member.relayUrl))
            }

            val template = EventTemplate<Event>(
                createdAt = now,
                kind      = 30002,
                tags      = tagsList.toTypedArray(),
                content   = "",
            )
            val signed = signingManager.sign(template) ?: return

            val eventJson = toEventJson(signed)
            val configs = relayConfigDao.getAllReadWriteRelays()
            val writeUrls = configs.filter { it.marker == null || it.marker == "write" }.map { it.relayUrl }
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

    private fun nowSeconds() = System.currentTimeMillis() / 1000L

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
            url = url.removePrefix("https://").removePrefix("http://")
            if (!url.startsWith("wss://") && !url.startsWith("ws://")) {
                url = "wss://$url"
            }
            val host = url.removePrefix("wss://").removePrefix("ws://").split("/").firstOrNull() ?: return null
            if (!host.contains(".")) return null
            return url
        }
    }
}
