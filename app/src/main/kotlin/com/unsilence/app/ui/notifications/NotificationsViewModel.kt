package com.unsilence.app.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.db.dao.NotificationRow
import com.unsilence.app.data.db.dao.NotificationsDao
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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

enum class NotifFilter { Following, Global }

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val keyManager: KeyManager,
    private val notificationsDao: NotificationsDao,
    private val relayPool: RelayPool,
    private val userRepository: UserRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()

    private val _filter = MutableStateFlow(NotifFilter.Global)
    val filter: StateFlow<NotifFilter> = _filter.asStateFlow()

    private var collectJob: Job? = null

    init {
        keyManager.getPublicKeyHex()?.let { pubkey ->
            relayPool.fetchNotifications(pubkey)
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
            val flow = if (_filter.value == NotifFilter.Following)
                notificationsDao.notificationsFollowingFlow(pubkey)
            else
                notificationsDao.notificationsFlow(pubkey)

            flow.collect { rows ->
                _uiState.update { it.copy(items = rows, loading = false) }

                val missingPubkeys = rows
                    .filter { it.actorPicture == null }
                    .map { it.actorPubkey }
                    .distinct()
                if (missingPubkeys.isNotEmpty()) {
                    userRepository.fetchMissingProfiles(missingPubkeys)
                }
            }
        }
    }
}
