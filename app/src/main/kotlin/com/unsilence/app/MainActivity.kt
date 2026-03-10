package com.unsilence.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.ui.onboarding.RootScreen
import com.unsilence.app.ui.theme.UnsilenceTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var relayPool: RelayPool

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

        setContent {
            UnsilenceTheme {
                RootScreen()
            }
        }
    }
}
