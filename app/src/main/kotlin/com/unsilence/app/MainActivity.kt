package com.unsilence.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.unsilence.app.data.AppBootstrapper
import com.unsilence.app.data.auth.AmberSigner
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.data.relay.RelayPreferencesStore
import kotlinx.coroutines.launch
import com.unsilence.app.ui.onboarding.RootScreen
import com.unsilence.app.ui.navigation.DeepLinkRouter
import com.unsilence.app.ui.theme.UnsilenceTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var signingManager: SigningManager
    @Inject lateinit var relayPreferencesStore: RelayPreferencesStore
    @Inject lateinit var appBootstrapper: AppBootstrapper
    @Inject lateinit var keyManager: KeyManager
    @Inject lateinit var deepLinkRouter: DeepLinkRouter

    // Launcher for automatic Amber re-authorize when bootstrap detects
    // missing NIP-44 permissions. Runs round-trip self-test on success.
    private val amberReauthorizeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pubkey = AmberSigner.parseLoginResult(result.data)
        if (pubkey != null) {
            lifecycleScope.launch {
                val ok = appBootstrapper.recoverMuteListAfterAmberAuthorization()
                if (ok) {
                    Toast.makeText(
                        this@MainActivity,
                        "Amber permissions granted \u2014 mute sync enabled",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Amber denied NIP-44 permissions \u2014 mutes will stay local only",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        } else {
            Toast.makeText(
                this@MainActivity,
                "Amber authorization cancelled \u2014 mutes stay local",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDeepLinkIntent(intent)

        // Debug: set sensitive content mode via intent extra
        // adb shell am start -n com.unsilence.app/.MainActivity --es sensitive_mode blur
        if (BuildConfig.DEBUG) {
            intent?.getStringExtra("sensitive_mode")?.let { mode ->
                val scm = SensitiveContentMode.entries.firstOrNull { it.name.equals(mode, ignoreCase = true) }
                if (scm != null) {
                    lifecycleScope.launch { relayPreferencesStore.setSensitiveContentMode(scm) }
                }
            }
        }

        val amberSignLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            result.data?.let { signingManager.onAmberResult(it) }
        }

        // Observe Amber re-authorize requests from bootstrap.
        // repeatOnLifecycle(STARTED) ensures we only launch when the activity
        // is in foreground. If bootstrap emits before STARTED, the buffer holds it.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appBootstrapper.amberReauthorizeRequiredFlow.collect {
                    val pubkey = keyManager.getPublicKeyHex() ?: return@collect
                    amberReauthorizeLauncher.launch(
                        AmberSigner.createReauthorizeIntent(pubkey),
                    )
                }
            }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.dataString?.let(deepLinkRouter::submit)
    }
}
