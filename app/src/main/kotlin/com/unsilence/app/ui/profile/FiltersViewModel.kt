package com.unsilence.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.AppBootstrapper
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.repository.MuteListRepository
import com.unsilence.app.data.repository.MuteResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FiltersViewModel @Inject constructor(
    private val memoryEventStore: MemoryEventStore,
    private val muteListRepository: MuteListRepository,
    private val relayPreferencesStore: RelayPreferencesStore,
    private val keyManager: KeyManager,
    private val appBootstrapper: AppBootstrapper,
) : ViewModel() {

    val muteList: StateFlow<MuteList?> =
        memoryEventStore.ownMuteListFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sensitiveContentMode: StateFlow<SensitiveContentMode> =
        relayPreferencesStore.sensitiveContentModeFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SensitiveContentMode.BLUR)

    val publishSafe: StateFlow<Boolean> = muteListRepository.publishSafe

    val isAmberMode: Boolean get() = keyManager.isAmberMode

    fun getProfile(pubkey: String): UserEntity? = memoryEventStore.getUserEntity(pubkey)

    fun unmuteUser(pubkey: String): MuteResult = muteListRepository.unmuteUser(pubkey)

    fun setSensitiveContentMode(mode: SensitiveContentMode) {
        viewModelScope.launch { relayPreferencesStore.setSensitiveContentMode(mode) }
    }

    /** Re-emit the Amber re-authorize signal. MainActivity picks it up and fires the intent. */
    fun retryAmberPermissions() {
        viewModelScope.launch { appBootstrapper.requestAmberReauthorize() }
    }
}
