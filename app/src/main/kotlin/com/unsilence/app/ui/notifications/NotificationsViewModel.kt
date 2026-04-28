package com.unsilence.app.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NotificationItem
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.NostrFilter
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.relay.Subscription
import com.unsilence.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationsUiState(
    val items: List<NotificationItem> = emptyList(),
    val loading: Boolean = true,
)

enum class NotifFilter { Following, Global }

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val memoryEventStore: MemoryEventStore,
    private val relayPreferencesStore: RelayPreferencesStore,
    private val userRepository: UserRepository,
    private val subscription: Subscription,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()

    private val _filter = MutableStateFlow(NotifFilter.Global)
    val filter: StateFlow<NotifFilter> = _filter.asStateFlow()

    private val _hasNew = MutableStateFlow(false)
    val hasNewNotifications: StateFlow<Boolean> = _hasNew.asStateFlow()

    /** Mark current notifications as seen — clears the blue dot. */
    fun markSeen() {
        val items = _uiState.value.items
        if (items.isEmpty()) {
            _hasNew.value = false
            return
        }
        val pubkey = keyManager.getPublicKeyHex() ?: return
        viewModelScope.launch {
            relayPreferencesStore.setLastSeenTimestamp(pubkey, items.first().createdAt)
            _hasNew.value = false
        }
    }

    private var collectJob: Job? = null
    private var notifSubHandle: Subscription.Handle? = null

    init {
        keyManager.getPublicKeyHex()?.let { pubkey ->
            startNotifSubscription(pubkey)
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

            memoryEventStore.notificationsFlow(pubkey, limit = 100, followedOnly = followedOnly)
                .collect { items ->
                    _uiState.update { it.copy(items = items, loading = false) }
                    if (items.isNotEmpty()) {
                        // Re-read lastSeen on each emission so markSeen() writes
                        // are reflected immediately (stale capture caused dot reappearing).
                        val lastSeen = relayPreferencesStore.getLastSeenTimestamp(pubkey).first()
                        _hasNew.value = items.first().createdAt > lastSeen
                    }

                    val missingPubkeys = items
                        .filter { it.actorPicture == null }
                        .map { it.actorPubkey }
                        .distinct()
                    if (missingPubkeys.isNotEmpty()) {
                        userRepository.fetchMissingProfiles(missingPubkeys)
                    }
                }
        }
    }

    private fun startNotifSubscription(pubkey: String) {
        notifSubHandle?.close()
        viewModelScope.launch {
            val readRelays = memoryEventStore.getReadWriteRelayConfigs(pubkey)
                .filter { it.marker == null || it.marker == "read" }
                .map { it.url }
                .ifEmpty { GLOBAL_RELAY_URLS }

            val filter = NostrFilter(
                kinds = listOf(1, 6, 7, 9735),
                tags = mapOf("p" to listOf(pubkey)),
                limit = 100,
                since = System.currentTimeMillis() / 1000L - 86_400L,
            )

            notifSubHandle = subscription.subscribe(
                urls = readRelays,
                filter = filter,
                onevent = { /* events flow through EventProcessor → MES → notificationsFlow */ },
            )
        }
    }

    override fun onCleared() {
        notifSubHandle?.close()
        super.onCleared()
    }
}
