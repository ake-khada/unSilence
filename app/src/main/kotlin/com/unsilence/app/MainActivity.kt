package com.unsilence.app

import android.content.Intent
import android.os.Bundle
import androidx.compose.material3.SnackbarDuration
import com.unsilence.app.ui.common.ActivityNotice
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.media.MediaLoadingPolicy
import com.unsilence.app.ui.shared.LocalMediaLoadPermissions
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.fragment.app.FragmentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.unsilence.app.data.AppBootstrapper
import com.unsilence.app.data.auth.AmberSigner
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.SigningManager
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.data.network.Nip05Verifier
import com.unsilence.app.data.relay.RelayPreferencesStore
import kotlinx.coroutines.launch
import com.unsilence.app.ui.onboarding.RootScreen
import com.unsilence.app.ui.common.LocalNip05VerificationController
import com.unsilence.app.ui.common.ProvideMinuteClock
import com.unsilence.app.ui.navigation.DeepLinkRouter
import com.unsilence.app.ui.theme.UnsilenceTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var signingManager: SigningManager
    @Inject lateinit var mediaLoadingPolicy: MediaLoadingPolicy
    @Inject lateinit var relayPreferencesStore: RelayPreferencesStore
    @Inject lateinit var appBootstrapper: AppBootstrapper
    @Inject lateinit var keyManager: KeyManager
    @Inject lateinit var deepLinkRouter: DeepLinkRouter
    @Inject lateinit var nip05Verifier: Nip05Verifier

    private val notices = Channel<ActivityNotice>(Channel.BUFFERED)
    private val noticeFlow = notices.receiveAsFlow()

    // Launcher for automatic Amber re-authorize when bootstrap detects
    // missing NIP-44 permissions. Runs round-trip self-test on success.
    private val amberReauthorizeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val account = keyManager.getPublicKeyHex() ?: return@registerForActivityResult
        if (AmberSigner.parseLoginResult(result) == account) {
            lifecycleScope.launch {
                val recovered = appBootstrapper.recoverMuteListAfterAmberAuthorization()
                val notice = if (recovered) {
                    ActivityNotice(account, "Amber permissions granted — mute sync enabled")
                } else {
                    ActivityNotice(account, "Amber denied NIP-44 permissions — mutes will stay local only", SnackbarDuration.Long)
                }
                notices.trySend(notice)
            }
        } else {
            notices.trySend(ActivityNotice(account, "Amber authorization cancelled — mutes stay local"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // unSilence is always dark, independently of the device's night-mode setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
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
                val avatarPreferences by relayPreferencesStore.avatarFallbackPreferences.collectAsStateWithLifecycle()
                val mediaPermissions by mediaLoadingPolicy.permissions.collectAsStateWithLifecycle()
                CompositionLocalProvider(
                    LocalMediaLoadPermissions provides mediaPermissions,
                    com.unsilence.app.ui.common.LocalAvatarFallbackPreferences provides avatarPreferences,
                    LocalNip05VerificationController provides nip05Verifier,
                ) {
                    ProvideMinuteClock { RootScreen(activityNotices = noticeFlow) }
                }
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
