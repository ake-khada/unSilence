package com.unsilence.app.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NotificationRow
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.relay.WotHydrationCoalescer
import com.unsilence.app.data.relay.wotLookupSnapshot
import com.unsilence.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationsUiState(
    val items: List<NotificationRow> = emptyList(),
    val loading: Boolean = true,
)

enum class NotifFilter { Following, Global }

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val memoryEventStore: MemoryEventStore,
    private val relayPreferencesStore: RelayPreferencesStore,
    private val userRepository: UserRepository,
    private val wotHydrationCoalescer: WotHydrationCoalescer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()

    private val _filter = MutableStateFlow(NotifFilter.Global)
    val filter: StateFlow<NotifFilter> = _filter.asStateFlow()

    private val _hasNew = MutableStateFlow(false)
    val hasNewNotifications: StateFlow<Boolean> = _hasNew.asStateFlow()
    private val _wotSubjects = MutableStateFlow<Set<String>>(emptySet())
    val wotLookups: StateFlow<Map<String, WotLookup>> =
        combine(_wotSubjects, memoryEventStore.wotSignalFlow) { subjects, _ ->
            wotLookupSnapshot(subjects, memoryEventStore::wotFor)
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyMap())

    val feedWotDisplayMode: StateFlow<FeedWotDisplayMode> =
        relayPreferencesStore.feedWotDisplayModeFlow()
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, FeedWotDisplayMode.NUMBERS)

    /**
     * In-memory mirror of the DataStore lastSeen timestamp. Seeded once per
     * pubkey in [startCollecting]; [markSeen] updates BOTH this and DataStore,
     * so each notification emission reads a fresh value without re-opening a
     * DataStore flow (disk I/O per emission). Preserves the stale-capture fix:
     * markSeen() writes are reflected immediately, the dot doesn't reappear.
     */
    private val lastSeenCache = MutableStateFlow(0L)
    private var lastSeenPubkey: String? = null

    /** Mark current notifications as seen — clears the blue dot. */
    fun markSeen() {
        val items = _uiState.value.items
        if (items.isEmpty()) {
            _hasNew.value = false
            return
        }
        val pubkey = keyManager.getPublicKeyHex() ?: return
        lastSeenCache.value = items.first().mostRecentAt
        _hasNew.value = false
        viewModelScope.launch {
            relayPreferencesStore.setLastSeenTimestamp(pubkey, items.first().mostRecentAt)
        }
    }

    private var collectJob: Job? = null

    init {
        keyManager.getPublicKeyHex()?.let { pubkey ->
            startCollecting(pubkey)
        }
    }

    fun setFilter(f: NotifFilter) {
        if (_filter.value == f) return
        _filter.value = f
        _uiState.update { it.copy(loading = true) }
        keyManager.getPublicKeyHex()?.let { startCollecting(it) }
    }

    private fun startCollecting(pubkey: String) {
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            val followedOnly = _filter.value == NotifFilter.Following

            // Seed lastSeenCache from DataStore once per pubkey — markSeen()
            // keeps it in sync afterwards, so per-emission reads stay in memory.
            if (lastSeenPubkey != pubkey) {
                lastSeenCache.value = relayPreferencesStore.getLastSeenTimestamp(pubkey).first()
                lastSeenPubkey = pubkey
            }

            memoryEventStore.notificationsFlow(pubkey, followedOnly = followedOnly)
                .collect { items ->
                    _uiState.update { it.copy(items = items, loading = false) }
                    if (items.isNotEmpty()) {
                        // Read the in-memory mirror — markSeen() updates it
                        // immediately (stale capture caused dot reappearing).
                        _hasNew.value = items.first().mostRecentAt > lastSeenCache.value
                    }

                    // Fetch missing profiles across ALL actors (singles + every
                    // grouped actor), not just one actor per row.
                    val missingPubkeys = items.flatMap { row ->
                        when (row) {
                            is NotificationRow.Single ->
                                if (row.actorPicture == null) listOf(row.actorPubkey) else emptyList()
                            is NotificationRow.Grouped ->
                                row.actors.filter { it.picture == null && it.pubkey != null }.map { it.pubkey!! }
                        }
                    }.distinct()
                    if (missingPubkeys.isNotEmpty()) {
                        userRepository.fetchMissingProfiles(missingPubkeys)
                    }
                    val actorPubkeys = items.flatMap { row ->
                        when (row) {
                            is NotificationRow.Single -> listOf(row.actorPubkey)
                            is NotificationRow.Grouped -> row.actors.mapNotNull { it.pubkey }
                        }
                    }.toSet()
                    _wotSubjects.value = actorPubkeys
                    wotHydrationCoalescer.requestHydration(actorPubkeys)
                }
        }
    }

    override fun onCleared() {
        collectJob?.cancel()
        super.onCleared()
    }
}
