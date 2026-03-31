package com.unsilence.app.ui.onboarding

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unsilence.app.data.AppBootstrapper
import com.unsilence.app.data.auth.KeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.system.exitProcess

@HiltViewModel
class RootViewModel @Inject constructor(
    val keyManager: KeyManager,
    private val bootstrapper: AppBootstrapper,
    @ApplicationContext private val context: Context,
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
            // Restart the process to destroy all ViewModel/Compose/singleton state.
            // Without this, @HiltViewModel instances scoped to the Activity survive
            // the recomposition and hold stale pubkey-derived data.
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            exitProcess(0)
        }
    }
}
