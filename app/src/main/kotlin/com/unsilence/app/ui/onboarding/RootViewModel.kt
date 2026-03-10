package com.unsilence.app.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.unsilence.app.data.AppBootstrapper
import com.unsilence.app.data.auth.KeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    val keyManager: KeyManager,
    private val bootstrapper: AppBootstrapper,
) : ViewModel() {

    var isLoggedIn by mutableStateOf(keyManager.hasKey())
        private set

    fun onOnboardingComplete() {
        isLoggedIn = true
        val pubkey = keyManager.getPublicKeyHex() ?: return
        bootstrapper.bootstrap(pubkey)
    }

    fun logout() {
        bootstrapper.teardown()
        isLoggedIn = false
    }
}
