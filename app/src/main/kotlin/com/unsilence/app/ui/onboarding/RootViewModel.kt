package com.unsilence.app.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.AppBootstrapper
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.blossom.BlossomServersStore
import com.unsilence.app.data.settings.SettingsStore
import com.unsilence.app.data.wallet.ZapPreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    val keyManager: KeyManager,
    private val bootstrapper: AppBootstrapper,
    private val zapPreferencesStore: ZapPreferencesStore,
    private val blossomServersStore: BlossomServersStore,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    var isLoggedIn by mutableStateOf(keyManager.hasKey())
        private set

    var isLoggingOut by mutableStateOf(false)
        private set

    /** Incremented on each login — ensures hiltViewModel(key) creates fresh VMs
     *  even when the same pubkey re-logs in. */
    var sessionId by mutableIntStateOf(savedStateHandle[SESSION_ID_KEY] ?: 0)
        private set

    init {
        // Bootstrap on app restart when user is already logged in.
        // Without this, no indexer connections, no kind-10002 refresh,
        // and FeedViewModel uses stale relay data from previous sessions.
        if (isLoggedIn) {
            val pubkey = keyManager.getPublicKeyHex()
            if (pubkey != null) {
                selectPreferenceOwner(pubkey)
                viewModelScope.launch(Dispatchers.IO) { bootstrapper.bootstrap(pubkey) }
            }
        }
    }

    fun onOnboardingComplete() {
        if (isLoggingOut) return
        val pubkey = keyManager.getPublicKeyHex() ?: return
        selectPreferenceOwner(pubkey)
        sessionId++
        savedStateHandle[SESSION_ID_KEY] = sessionId
        isLoggedIn = true
        viewModelScope.launch(Dispatchers.IO) { bootstrapper.bootstrap(pubkey) }
    }

    fun logout() {
        if (isLoggingOut) return
        viewModelScope.launch {
            isLoggingOut = true
            // Set isLoggedIn false on Main FIRST — this triggers recomposition,
            // destroys AppNavigation and all nested ViewModels (FeedVM, etc.)
            // BEFORE teardown clears keyManager/MES. Keep onboarding hidden until
            // teardown completes, or a fast re-login can supersede the teardown
            // fence and keep account-scoped DataStore state from the old user.
            // Without this ordering,
            // the Main-thread switch inside teardown (ExoPlayer release) lets
            // Compose see cleared keyManager + isLoggedIn=true → zombie FeedVM.
            isLoggedIn = false
            try {
                withContext(Dispatchers.IO) {
                    bootstrapper.teardown()
                }
            } finally {
                isLoggingOut = false
            }
        }
    }

    private fun selectPreferenceOwner(pubkey: String) {
        zapPreferencesStore.selectOwner(pubkey)
        blossomServersStore.selectOwner(pubkey)
        settingsStore.selectOwner(pubkey)
    }

    private companion object {
        const val SESSION_ID_KEY = "root_session_id"
    }
}
