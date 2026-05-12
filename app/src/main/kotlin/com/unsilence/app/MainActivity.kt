package com.unsilence.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.RelayPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.unsilence.app.ui.onboarding.RootScreen
import com.unsilence.app.ui.theme.UnsilenceTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var relayPool: RelayPool
    @Inject lateinit var signingManager: SigningManager
    @Inject lateinit var relayPreferencesStore: RelayPreferencesStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Debug: set sensitive content mode via intent extra
        // adb shell am start -n com.unsilence.app/.MainActivity --es sensitive_mode blur
        intent?.getStringExtra("sensitive_mode")?.let { mode ->
            val scm = SensitiveContentMode.entries.firstOrNull { it.name.equals(mode, ignoreCase = true) }
            if (scm != null) {
                CoroutineScope(Dispatchers.IO).launch { relayPreferencesStore.setSensitiveContentMode(scm) }
            }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    relayPool.reconnectAll()
                }
            }
        )

        val amberSignLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            result.data?.let { signingManager.onAmberResult(it) }
        }

        setContent {
            UnsilenceTheme {
                DisposableEffect(Unit) {
                    val launcher: (Intent) -> Unit = { intent ->
                        amberSignLauncher.launch(intent)
                    }
                    signingManager.registerLauncher(launcher)
                    onDispose { signingManager.unregisterLauncher(launcher) }
                }
                RootScreen()
            }
        }
    }
}
