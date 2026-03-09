package com.unsilence.app.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.db.dao.NotificationRow
import com.unsilence.app.data.db.dao.NotificationsDao
import com.unsilence.app.data.relay.RelayPool
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationsUiState(
    val items: List<NotificationRow> = emptyList(),
    val loading: Boolean = true,
)

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val notificationsDao: NotificationsDao,
    private val relayPool: RelayPool,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()

    init {
        keyManager.getPublicKeyHex()?.let { pubkey ->
            // Pull notification events from connected relays.
            relayPool.fetchNotifications(pubkey)

            // Collect the live Room query — re-emits as new events arrive via EventProcessor.
            viewModelScope.launch {
                notificationsDao.notificationsFlow(pubkey).collect { rows ->
                    _uiState.update { it.copy(items = rows, loading = false) }
                }
            }
        }
    }
}
