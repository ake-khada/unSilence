package com.unsilence.app.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.AppBootstrapper
import com.unsilence.app.data.auth.KeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    val keyManager: KeyManager,
    private val bootstrapper: AppBootstrapper,
) : ViewModel() {

    var isLoggedIn by mutableStateOf(keyManager.hasKey())
        private set

    init {
        // Bootstrap on app restart when user is already logged in.
        // Without this, no indexer connections, no kind-10002 refresh,
        // and FeedViewModel uses stale relay data from previous sessions.
        if (isLoggedIn) {
            val pubkey = keyManager.getPublicKeyHex()
            if (pubkey != null) {
                viewModelScope.launch { bootstrapper.bootstrap(pubkey) }
            }
        }
    }

    fun onOnboardingComplete() {
        isLoggedIn = true
        val pubkey = keyManager.getPublicKeyHex() ?: return
        viewModelScope.launch { bootstrapper.bootstrap(pubkey) }
    }

    fun logout() {
        viewModelScope.launch {
            bootstrapper.teardown()
            isLoggedIn = false
        }
    }
}
