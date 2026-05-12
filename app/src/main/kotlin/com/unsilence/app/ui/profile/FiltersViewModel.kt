package com.unsilence.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.data.repository.MuteListRepository
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
) : ViewModel() {

    val muteList: StateFlow<MuteList?> =
        memoryEventStore.ownMuteListFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sensitiveContentMode: StateFlow<SensitiveContentMode> =
        relayPreferencesStore.sensitiveContentModeFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SensitiveContentMode.BLUR)

    fun getProfile(pubkey: String): UserEntity? = memoryEventStore.getUserEntity(pubkey)

    fun unmuteUser(pubkey: String) = muteListRepository.unmuteUser(pubkey)

    fun setSensitiveContentMode(mode: SensitiveContentMode) {
        viewModelScope.launch { relayPreferencesStore.setSensitiveContentMode(mode) }
    }
}
