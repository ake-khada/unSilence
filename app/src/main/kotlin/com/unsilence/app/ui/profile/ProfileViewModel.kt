package com.unsilence.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.db.dao.FeedRow
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.data.repository.EventRepository
import com.unsilence.app.data.repository.UserRepository
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    keyManager: KeyManager,
    private val userRepository: UserRepository,
    private val eventRepository: EventRepository,
) : ViewModel() {

    val pubkeyHex: String? = keyManager.getPublicKeyHex()

    val npub: String? = pubkeyHex?.let { hex ->
        runCatching { hex.hexToByteArray().toNpub() }.getOrNull()
    }

    /** Live user metadata from Room (null until kind 0 arrives from relay). */
    val userFlow: Flow<UserEntity?> =
        if (pubkeyHex != null) userRepository.userFlow(pubkeyHex) else emptyFlow()

    /** Live top-level posts by this user, newest-first. */
    val postsFlow: Flow<List<FeedRow>> =
        if (pubkeyHex != null) eventRepository.userPostsFlow(pubkeyHex) else emptyFlow()

    // Following / followers counts are Sprint 5 (NIP-65 outbox + NIP-45 COUNT).
    // Exposed as null here so ProfileScreen can show "—" gracefully.
    val following: Int? = null
    val followers: Int? = null

    init {
        // Request a fresh kind 0 for the logged-in user if not yet cached.
        viewModelScope.launch {
            if (pubkeyHex != null) {
                userRepository.fetchMissingProfiles(listOf(pubkeyHex))
            }
        }
    }
}
