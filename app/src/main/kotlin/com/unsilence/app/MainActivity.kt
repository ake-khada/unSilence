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
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.ui.onboarding.RootScreen
import com.unsilence.app.ui.theme.UnsilenceTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var relayPool: RelayPool
    @Inject lateinit var signingManager: SigningManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
