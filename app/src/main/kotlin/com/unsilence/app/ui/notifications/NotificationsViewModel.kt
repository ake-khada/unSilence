package com.unsilence.app.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NotificationItem
import com.unsilence.app.data.relay.RelayPreferencesStore
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

            memoryEventStore.notificationsFlow(pubkey, followedOnly = followedOnly)
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

    override fun onCleared() {
        collectJob?.cancel()
        super.onCleared()
    }
}
