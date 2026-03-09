package com.unsilence.app.ui.relays

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.repository.RelaySetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CreateRelaySetViewModel @Inject constructor(
    private val relaySetRepository: RelaySetRepository,
) : ViewModel() {

    /** Persists the new relay set and invokes [onCreated] on the calling coroutine. */
    fun create(name: String, relayUrls: List<String>, onCreated: () -> Unit) {
        viewModelScope.launch {
            relaySetRepository.createUserSet(name.trim(), relayUrls)
            onCreated()
        }
    }
}
