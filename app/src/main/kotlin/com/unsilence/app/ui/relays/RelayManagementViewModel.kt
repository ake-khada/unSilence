package com.unsilence.app.ui.relays

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.RelayHealthInfo
import com.unsilence.app.data.memory.FavoriteEntry
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.RelayConfig
import com.unsilence.app.data.memory.RelaySet
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.relay.RelayCapabilitiesStore
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.relay.normalizeRelayUrl
import com.unsilence.app.data.relay.toEventJson
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltViewModel
class RelayManagementViewModel @Inject constructor(
    private val memoryEventStore: MemoryEventStore,
    private val relayPreferencesStore: RelayPreferencesStore,
    private val relayPool: RelayPool,
    private val relayCapabilitiesStore: RelayCapabilitiesStore,
    private val keyManager: KeyManager,
    private val signingManager: SigningManager,
) : ViewModel() {

    // PHASE-2: replace with the dedicated discovery screen's load. Temporary trigger to
    // validate the Phase-1 relay-directory firehose (ensureDirectoryFresh is on-demand only,
    // never cold-start/background). Single-flight + 6h TTL are enforced inside the call.
    /** User-initiated one-shot RTT probe for the "Test" action (no background/persistence). */
    suspend fun measureRtt(url: String): Int? = relayPool.measureRtt(url)

    /** §05 detail page: device NIP-11 overlay + live RTT + reachability for one relay. */
    suspend fun loadRelayDetail(url: String): com.unsilence.app.data.relay.RelayDirectoryEntry? =
        relayPool.buildRelayDetail(url)

    /** Reactive profile for the relay's operator (NIP-11 pubkey) — resolves the npub to a
     *  NIP-05 / display name once the kind-0 is in MES. */
    fun operatorProfileFlow(pubkeyHex: String): Flow<UserEntity?> =
        memoryEventStore.profileFlow(pubkeyHex)
            .map { memoryEventStore.getUserEntity(pubkeyHex) }
            .flowOn(Dispatchers.Default)

    /** On detail-open: fetch the operator's kind-0 so the npub resolves to a name/NIP-05. */
    fun fetchOperatorProfile(pubkeyHex: String) {
        viewModelScope.launch(Dispatchers.IO) { relayPool.fetchProfiles(listOf(pubkeyHex)) }
    }

    fun phase1TriggerDirectoryBuild() {
        viewModelScope.launch(Dispatchers.IO) { relayPool.ensureDirectoryFresh() }
    }

    val ownerPubkey: String? get() = keyManager.getPublicKeyHex()

    /** Kind 10002 read/write relays. */
    val readWriteRelays: Flow<List<RelayConfig>> =
        ownerPubkey?.let { memoryEventStore.readWriteRelayConfigsFlow(it) } ?: emptyFlow()

    /** Kind 10006 blocked relays. */
    val blockedRelays: Flow<List<String>> =
        ownerPubkey?.let { memoryEventStore.blockedRelayUrlsFlow(it) } ?: emptyFlow()

    /** Kind 10007 search relays. */
    val searchRelays: Flow<List<String>> =
        ownerPubkey?.let { memoryEventStore.searchRelayUrlsFlow(it) } ?: emptyFlow()

    /** Kind 10012 favorite relays. */
    val favoriteRelays: Flow<List<FavoriteEntry>> =
        ownerPubkey?.let { memoryEventStore.favoriteRelayConfigsFlow(it) } ?: emptyFlow()

    /** Kind 99 (local-only) indexer relays. */
    val indexerRelays: Flow<List<String>> = relayPreferencesStore.indexerRelayUrlsFlow()

    /** Kind 30002 relay sets. */
    val relaySets: Flow<List<RelaySet>> =
        ownerPubkey?.let { memoryEventStore.getAllSetsFlow(it) } ?: emptyFlow()

    /** Combined relay health (trust score + monitor) keyed by URL. */
    val relayHealth: Flow<Map<String, RelayHealthInfo>> =
        memoryEventStore.relayHealthFlow()

    val publishing = MutableStateFlow(false)
    private val publishMutex = Mutex()

    // ── Kind 10002: Read/Write relays ─────────────────────────────────────────

    fun addReadWriteRelay(url: String) {
        val normalized = normalizeRelayUrl(url) ?: return
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            relayCapabilitiesStore.clearCooldownForRelay(normalized)
            memoryEventStore.addReadWriteRelay(pk, RelayConfig(normalized, null))
            publishChanges(10002)
        }
    }

    fun removeReadWriteRelay(url: String) {
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            memoryEventStore.removeReadWriteRelay(pk, url)
            publishChanges(10002)
        }
    }

    fun toggleMarker(relay: RelayConfig) {
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val newMarker = when (relay.marker) {
                null    -> "read"
                "read"  -> "write"
                "write" -> null
                else    -> null
            }
            memoryEventStore.updateRelayMarker(pk, relay.url, newMarker)
            publishChanges(10002)
        }
    }

    /** Set a specific R/W marker (null = read+write, "read" = read-only, "write" = write-only)
     *  via the SAME kind-10002 path as toggleMarker — for the independent R / W toggle pills. */
    fun setRelayMarker(relay: RelayConfig, marker: String?) {
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            memoryEventStore.updateRelayMarker(pk, relay.url, marker)
            publishChanges(10002)
        }
    }

    // ── Kind 10006: Blocked relays ────────────────────────────────────────────

    fun addBlockedRelay(url: String) {
        val normalized = normalizeRelayUrl(url) ?: return
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            memoryEventStore.addBlockedRelay(pk, normalized)
            relayPool.onBlockedRelaysChanged(memoryEventStore.getBlockedRelayUrls(pk).toSet())
            publishChanges(10006)
        }
    }

    fun removeBlockedRelay(url: String) {
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            memoryEventStore.removeBlockedRelay(pk, url)
            relayPool.onBlockedRelaysChanged(memoryEventStore.getBlockedRelayUrls(pk).toSet())
            publishChanges(10006)
        }
    }

    // ── Kind 10007: Search relays ─────────────────────────────────────────────

    fun addSearchRelay(url: String) {
        val normalized = normalizeRelayUrl(url) ?: return
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            relayCapabilitiesStore.clearCooldownForRelay(normalized)
            memoryEventStore.addSearchRelay(pk, normalized)
            publishChanges(10007)
        }
    }

    fun removeSearchRelay(url: String) {
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            memoryEventStore.removeSearchRelay(pk, url)
            publishChanges(10007)
        }
    }

    // ── Kind 10012: Favorite relays ────────────────────────────────────────────

    fun addFavoriteRelay(url: String) {
        val normalized = normalizeRelayUrl(url) ?: return
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            relayCapabilitiesStore.clearCooldownForRelay(normalized)
            memoryEventStore.addFavoriteRelay(pk, FavoriteEntry(normalized, null))
            publishChanges(10012)
        }
    }

    fun removeFavoriteRelay(url: String) {
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            memoryEventStore.removeFavoriteRelay(pk, url)
            publishChanges(10012)
        }
    }

    fun addFavoriteSetRef(setRef: String) {
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            memoryEventStore.addFavoriteRelay(pk, FavoriteEntry(null, setRef))
            publishChanges(10012)
        }
    }

    fun removeFavoriteSetRef(setRef: String) {
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            memoryEventStore.removeFavoriteBySetRef(pk, setRef)
            publishChanges(10012)
        }
    }

    // ── Kind 99: Indexer relays (local-only, never published) ───────────────

    fun addIndexerRelay(url: String) {
        val normalized = normalizeRelayUrl(url) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            relayCapabilitiesStore.clearCooldownForRelay(normalized)
            relayPreferencesStore.addIndexerUrl(normalized)
        }
    }

    fun removeIndexerRelay(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            relayPreferencesStore.removeIndexerUrl(url)
        }
    }

    // ── Kind 30002: Relay sets ────────────────────────────────────────────────

    fun createRelaySet(name: String, relays: List<String>) {
        val baseDTag = name.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // Handle d-tag collision: append numeric suffix if needed
            var dTag = baseDTag
            var suffix = 1
            val existingSets = memoryEventStore.getAllRelaySets(pk)
            val existingDTags = existingSets.map { it.dTag }.toSet()
            while (dTag in existingDTags) {
                suffix++
                dTag = "$baseDTag-$suffix"
            }

            val members = relays.mapNotNull { normalizeRelayUrl(it) }
            memoryEventStore.upsertRelaySet(
                RelaySet(dTag = dTag, ownerPubkey = pk, title = name, members = members)
            )
            publishRelaySet(dTag)
        }
    }

    fun deleteRelaySet(dTag: String) {
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            memoryEventStore.deleteRelaySet(pk, dTag, nowSeconds())
            publishRelaySet(dTag)
        }
    }

    fun getSetMembers(dTag: String): Flow<List<String>> {
        val pk = ownerPubkey ?: return emptyFlow()
        return memoryEventStore.getSetMembersFlow(pk, dTag)
    }

    fun addRelayToSet(dTag: String, url: String) {
        val normalized = normalizeRelayUrl(url) ?: return
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            memoryEventStore.addRelayToSet(pk, dTag, normalized)
            publishRelaySet(dTag)
        }
    }

    fun removeRelayFromSet(dTag: String, url: String) {
        val pk = ownerPubkey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            memoryEventStore.removeRelayFromSet(pk, dTag, url)
            publishRelaySet(dTag)
        }
    }

    // ── Publishing ────────────────────────────────────────────────────────────

    private suspend fun publishChanges(kind: Int): Unit = publishMutex.withLock {
        val pk = ownerPubkey ?: return
        publishing.value = true
        try {
            val now = nowSeconds()

            val tags: Array<Array<String>>
            val publishKind: Int

            when (kind) {
                10002 -> {
                    val allRelays = memoryEventStore.getReadWriteRelayConfigs(pk)
                    tags = allRelays.mapNotNull { relay ->
                        val isRead = relay.marker == null || relay.marker == "read"
                        val isWrite = relay.marker == null || relay.marker == "write"
                        when {
                            isRead && isWrite -> arrayOf("r", relay.url)
                            isRead            -> arrayOf("r", relay.url, "read")
                            isWrite           -> arrayOf("r", relay.url, "write")
                            else              -> null
                        }
                    }.toTypedArray()
                    publishKind = 10002
                }
                10006, 10007 -> {
                    val list = when (kind) {
                        10006 -> memoryEventStore.getBlockedRelayUrls(pk)
                        10007 -> memoryEventStore.getSearchRelayUrls(pk)
                        else -> emptyList()
                    }
                    tags = list.map { arrayOf("relay", it) }.toTypedArray()
                    publishKind = kind
                }
                10012 -> {
                    val favorites = memoryEventStore.getFavoriteRelayConfigs(pk)
                    val tagsList = mutableListOf<Array<String>>()
                    for (fav in favorites) {
                        if (fav.setRef != null) {
                            tagsList.add(arrayOf("a", fav.setRef))
                        } else if (fav.url != null) {
                            tagsList.add(arrayOf("relay", fav.url))
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
            val writeUrls = memoryEventStore.getReadWriteRelayConfigs(pk)
                .filter { it.marker == null || it.marker == "write" }
                .map { it.url }
            val indexerUrls = relayPreferencesStore.indexerRelayUrlsSnapshot()
            val targets = (writeUrls + indexerUrls).distinct()
            relayPool.publishToRelays(eventJson, targets)
            android.util.Log.w("RelayMgmt", "RELAY-LIST published kind=$publishKind id=${signed.id.take(8)}… → ${targets.size} relays: " +
                tags.joinToString(", ") { it.joinToString(":") })
        } finally {
            publishing.value = false
        }
    }

    private suspend fun publishRelaySet(dTag: String): Unit = publishMutex.withLock {
        val pk = ownerPubkey ?: return
        publishing.value = true
        try {
            val now = nowSeconds()
            val set = memoryEventStore.getRelaySet(pk, dTag)
            val members = set?.members ?: memoryEventStore.getSetMembers(pk, dTag)
            val tagsList = mutableListOf<Array<String>>()
            tagsList.add(arrayOf("d", dTag))
            // Preserve NIP-51 metadata across edits — dropping `title` here was mangling
            // the set name to the random d-tag on every relay add/remove.
            set?.title?.takeIf { it.isNotBlank() }?.let { tagsList.add(arrayOf("title", it)) }
            set?.description?.takeIf { it.isNotBlank() }?.let { tagsList.add(arrayOf("description", it)) }
            set?.image?.takeIf { it.isNotBlank() }?.let { tagsList.add(arrayOf("image", it)) }
            for (url in members) {
                tagsList.add(arrayOf("relay", url))
            }

            val template = EventTemplate<Event>(
                createdAt = now,
                kind      = 30002,
                tags      = tagsList.toTypedArray(),
                content   = "",
            )
            val signed = signingManager.sign(template) ?: return

            val eventJson = toEventJson(signed)
            val writeUrls = memoryEventStore.getReadWriteRelayConfigs(pk)
                .filter { it.marker == null || it.marker == "write" }
                .map { it.url }
            val indexerUrls = relayPreferencesStore.indexerRelayUrlsSnapshot()
            relayPool.publishToRelays(eventJson, (writeUrls + indexerUrls).distinct())
            android.util.Log.w("RelayMgmt", "RELAY-LIST published kind=30002 set=$dTag id=${signed.id.take(8)}… tags: " +
                tagsList.joinToString(", ") { it.joinToString(":") })
        } finally {
            publishing.value = false
        }
    }

    private fun nowSeconds() = System.currentTimeMillis() / 1000L

}
