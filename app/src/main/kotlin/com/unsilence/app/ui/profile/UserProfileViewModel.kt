package com.unsilence.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.repository.EventRepository
import com.unsilence.app.data.repository.UserRepository
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UserProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val eventRepository: EventRepository,
) : ViewModel() {

    private val _pubkeyHex = MutableStateFlow<String?>(null)
    val pubkeyHex: StateFlow<String?> = _pubkeyHex.asStateFlow()

    val npub: String?
        get() = _pubkeyHex.value?.let { hex ->
            runCatching { hex.hexToByteArray().toNpub() }.getOrNull()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val userFlow: Flow<UserEntity?> = _pubkeyHex
        .filterNotNull()
        .flatMapLatest { userRepository.userFlow(it) }

    @OptIn(ExperimentalCoroutinesApi::class)
    val postsFlow: Flow<List<FeedRow>> = _pubkeyHex
        .filterNotNull()
        .flatMapLatest { eventRepository.userPostsFlow(it) }

    fun loadProfile(pubkey: String) {
        if (_pubkeyHex.value == pubkey) return
        _pubkeyHex.value = pubkey
        viewModelScope.launch {
            userRepository.fetchMissingProfiles(listOf(pubkey))
        }
    }
}
