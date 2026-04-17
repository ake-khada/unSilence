package com.unsilence.app.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.AppBootstrapper
import com.unsilence.app.data.auth.KeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    val keyManager: KeyManager,
    private val bootstrapper: AppBootstrapper,
) : ViewModel() {

    var isLoggedIn by mutableStateOf(keyManager.hasKey())
        private set

    /** Incremented on each login — ensures hiltViewModel(key) creates fresh VMs
     *  even when the same pubkey re-logs in. */
    var sessionId by mutableIntStateOf(0)
        private set

    init {
        // Bootstrap on app restart when user is already logged in.
        // Without this, no indexer connections, no kind-10002 refresh,
        // and FeedViewModel uses stale relay data from previous sessions.
        if (isLoggedIn) {
            val pubkey = keyManager.getPublicKeyHex()
            if (pubkey != null) {
                viewModelScope.launch(Dispatchers.IO) { bootstrapper.bootstrap(pubkey) }
            }
        }
    }

    fun onOnboardingComplete() {
        sessionId++
        isLoggedIn = true
        val pubkey = keyManager.getPublicKeyHex() ?: return
        viewModelScope.launch(Dispatchers.IO) { bootstrapper.bootstrap(pubkey) }
    }

    fun logout() {
        viewModelScope.launch {
            // Set isLoggedIn false on Main FIRST — this triggers recomposition,
            // destroys AppNavigation and all nested ViewModels (FeedVM, etc.)
            // BEFORE teardown clears keyManager/MES. Without this ordering,
            // the Main-thread switch inside teardown (ExoPlayer release) lets
            // Compose see cleared keyManager + isLoggedIn=true → zombie FeedVM.
            isLoggedIn = false
            withContext(Dispatchers.IO) {
                bootstrapper.teardown()
            }
        }
    }
}
