package com.unsilence.app.ui.settings.keys

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.auth.AmberSigner
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BorderFaint
import com.unsilence.app.ui.theme.BorderSubtle
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.Text4
import com.unsilence.app.ui.theme.TextSecondary
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNsec
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val AuthenticatorFlags =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

@Composable
fun KeysScreen(
    onDismiss: () -> Unit,
    viewModel: KeysViewModel = hiltViewModel(
        key = "keys-${LocalAppSessionKey.current}",
    ),
) {
    BackHandler(onBack = onDismiss)

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val revealMachine = remember { KeyRevealStateMachine() }
    var revealState by remember { mutableStateOf<KeyRevealState>(revealMachine.state) }
    var revealedSecret by remember { mutableStateOf<RevealedSecret?>(null) }
    var showSecretAsHex by remember { mutableStateOf(false) }
    var remainingRevealSeconds by remember { mutableStateOf(0L) }
    var showNoLockWarning by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val reauthorizeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        statusMessage = if (AmberSigner.parseLoginResult(result.data) != null) {
            "Amber authorization refreshed."
        } else {
            "Amber authorization cancelled."
        }
    }

    fun maskSecret() {
        revealedSecret = null
        showSecretAsHex = false
        remainingRevealSeconds = 0L
        revealState = revealMachine.mask()
    }

    fun revealSecret() {
        val privateHex = viewModel.privateKeyHexForReveal()
        if (privateHex == null) {
            statusMessage = "No local secret key is stored on this device."
            maskSecret()
            return
        }
        revealedSecret = RevealedSecret(
            nsec = privateHex.hexToByteArray().toNsec(),
            hex = privateHex,
        )
        val revealed = revealMachine.reveal(SystemClock.elapsedRealtime()) as KeyRevealState.Revealed
        revealState = revealed
        remainingRevealSeconds = remainingSeconds(revealed.expiresAtMillis)
        statusMessage = null
    }

    fun startRevealFlow() {
        if (!hasDeviceLock(context)) {
            showNoLockWarning = true
            return
        }
        val activity = context.findActivity() as? FragmentActivity
        if (activity == null) {
            statusMessage = "Device authentication is unavailable in this window."
            return
        }

        revealState = revealMachine.startAuthentication()
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    revealSecret()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    statusMessage = "Unlock cancelled."
                    revealedSecret = null
                    remainingRevealSeconds = 0L
                    revealState = revealMachine.cancel()
                }

                override fun onAuthenticationFailed() {
                    statusMessage = "Authentication failed."
                }
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock secret key")
            .setSubtitle("Confirm it is you to reveal your nsec for 30 seconds.")
            .setAllowedAuthenticators(AuthenticatorFlags)
            .build()
        prompt.authenticate(promptInfo)
    }

    DisposableEffect(Unit) {
        viewModel.refreshAmberInstallState()
        val window = context.findActivity()?.window
        val hadSecureFlag = window != null &&
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            revealedSecret = null
            remainingRevealSeconds = 0L
            revealMachine.mask()
            if (!hadSecureFlag) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                revealedSecret = null
                remainingRevealSeconds = 0L
                revealState = revealMachine.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(revealState) {
        val revealed = revealState as? KeyRevealState.Revealed ?: return@LaunchedEffect
        remainingRevealSeconds = remainingSeconds(revealed.expiresAtMillis)
        while (isActive) {
            delay(500L)
            val current = revealMachine.state
            if (current is KeyRevealState.Revealed) {
                remainingRevealSeconds = remainingSeconds(current.expiresAtMillis)
            }
            val next = revealMachine.tick(SystemClock.elapsedRealtime())
            revealState = next
            if (next is KeyRevealState.Masked) {
                revealedSecret = null
                remainingRevealSeconds = 0L
                break
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            KeysTopBar(onDismiss = onDismiss)
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = Spacing.small,
                    bottom = Spacing.xl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                item {
                    PublicKeyCard(
                        publicNpub = uiState.publicNpub,
                        onCopy = {
                            uiState.publicNpub?.let {
                                copyToClipboard(context, "npub", it, sensitive = false)
                                statusMessage = "Public key copied."
                            }
                        },
                    )
                }
                item {
                    if (uiState.isAmberMode) {
                        AmberModeCard(
                            state = uiState,
                            statusMessage = statusMessage,
                            onOpenAmber = { openAmber(context) },
                            onReauthorize = { pubkey ->
                                statusMessage = null
                                reauthorizeLauncher.launch(AmberSigner.createReauthorizeIntent(pubkey))
                            },
                            setStatus = { statusMessage = it },
                        )
                    } else {
                        LocalSecretKeyCard(
                            revealState = revealState,
                            secret = revealedSecret,
                            showHex = showSecretAsHex,
                            remainingSeconds = remainingRevealSeconds,
                            statusMessage = statusMessage,
                            onUnlock = ::startRevealFlow,
                            onMask = ::maskSecret,
                            onToggleHex = { showSecretAsHex = !showSecretAsHex },
                            onCopy = { value ->
                                copyToClipboard(context, "secret key", value, sensitive = true)
                                statusMessage = "Secret key copied."
                            },
                        )
                    }
                }
                item {
                    if (uiState.isAmberMode) {
                        AmberStorageCard()
                    } else {
                        LocalStorageCard()
                    }
                }
            }
        }
    }

    if (showNoLockWarning) {
        AlertDialog(
            onDismissRequest = { showNoLockWarning = false },
            title = { Text("No screen lock configured") },
            text = {
                Text(
                    "Your device has no lock screen, so unSilence cannot require device authentication before showing the secret key.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNoLockWarning = false
                        revealSecret()
                    },
                ) { Text("Reveal anyway") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNoLockWarning = false
                        statusMessage = "Unlock cancelled."
                    },
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun KeysTopBar(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(Sizing.topBarHeight)
            .padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Keys",
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
        }
    }
}

@Composable
private fun PublicKeyCard(
    publicNpub: String?,
    onCopy: () -> Unit,
) {
    KeyCard(title = "Public key", icon = Icons.Filled.Key) {
        Text(
            text = publicNpub ?: "No public key loaded.",
            color = Color.White,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.small))
        KeyButton(
            text = "Copy",
            icon = Icons.Filled.ContentCopy,
            enabled = publicNpub != null,
            modifier = Modifier.fillMaxWidth(),
            onClick = onCopy,
        )
    }
}

@Composable
private fun LocalSecretKeyCard(
    revealState: KeyRevealState,
    secret: RevealedSecret?,
    showHex: Boolean,
    remainingSeconds: Long,
    statusMessage: String?,
    onUnlock: () -> Unit,
    onMask: () -> Unit,
    onToggleHex: () -> Unit,
    onCopy: (String) -> Unit,
) {
    KeyCard(
        title = "Secret key",
        icon = Icons.Filled.Lock,
        background = Brand.copy(alpha = 0.10f),
        border = Brand.copy(alpha = 0.28f),
    ) {
        val revealed = revealState as? KeyRevealState.Revealed
        if (secret == null || revealed == null) {
            Text(
                text = "nsec1••••••••••••••••••••••••••••••••",
                color = TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(Spacing.small))
            KeyButton(
                text = if (revealState is KeyRevealState.Authenticating) "Unlocking..." else "Unlock to reveal",
                icon = Icons.Filled.Visibility,
                enabled = revealState !is KeyRevealState.Authenticating,
                modifier = Modifier.fillMaxWidth(),
                onClick = onUnlock,
            )
        } else {
            val value = if (showHex) secret.hex else secret.nsec
            Text(
                text = value,
                color = Color.White,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Masks in ${remainingSeconds}s",
                color = Text3,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(Spacing.small))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyButton(
                    text = "Copy",
                    icon = Icons.Filled.ContentCopy,
                    modifier = Modifier.weight(1f),
                    onClick = { onCopy(value) },
                )
                KeyButton(
                    text = if (showHex) "nsec" else "Hex",
                    modifier = Modifier.weight(1f),
                    onClick = onToggleHex,
                )
                KeyButton(
                    text = "Mask",
                    icon = Icons.Filled.VisibilityOff,
                    modifier = Modifier.weight(1f),
                    onClick = onMask,
                )
            }
        }
        if (statusMessage != null) {
            Text(
                text = statusMessage,
                color = Text3,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = Spacing.small),
            )
        }
    }
}

@Composable
private fun AmberModeCard(
    state: KeysUiState,
    statusMessage: String?,
    onOpenAmber: () -> Unit,
    onReauthorize: (String) -> Unit,
    setStatus: (String) -> Unit,
) {
    KeyCard(title = "Signer", icon = Icons.Filled.Lock) {
        Text(
            text = if (state.amberInstalled) "Amber identity connected" else "Amber not installed",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (state.amberInstalled) {
                "Your secret key lives inside Amber — unSilence never sees it; every signature is approved there."
            } else {
                "Install Amber to manage this account's secret key and approve signatures outside unSilence."
            },
            color = TextSecondary,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(Spacing.small))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton(
                text = if (state.amberInstalled) "Open Amber" else "Install Amber",
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                modifier = Modifier.weight(1f),
                onClick = onOpenAmber,
            )
            KeyButton(
                text = "Re-authorize",
                icon = Icons.Filled.Refresh,
                enabled = state.amberInstalled && state.publicKeyHex != null,
                modifier = Modifier.weight(1f),
                onClick = {
                    val pubkey = state.publicKeyHex
                    if (pubkey == null) {
                        setStatus("No Amber public key is loaded.")
                    } else {
                        onReauthorize(pubkey)
                    }
                },
            )
        }
        if (statusMessage != null) {
            Text(
                text = statusMessage,
                color = Text3,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = Spacing.small),
            )
        }
    }
}

@Composable
private fun LocalStorageCard() {
    KeyCard(title = "Storage", icon = Icons.Filled.Lock, background = Mint.copy(alpha = 0.08f), border = Mint.copy(alpha = 0.22f)) {
        Text(
            text = "Encrypted with AES-256 by a hardware-backed Android Keystore key. Never leaves this device; excluded from backups.",
            color = TextSecondary,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
        )
        Text(
            text = "Losing this device without a backup of your nsec means losing the account.",
            color = Color.White,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun AmberStorageCard() {
    KeyCard(title = "Storage", icon = Icons.Filled.Lock, background = Mint.copy(alpha = 0.08f), border = Mint.copy(alpha = 0.22f)) {
        Text(
            text = "Nothing stored here — this app keeps only your public key. Back up your secret key from inside Amber.",
            color = TextSecondary,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun KeyCard(
    title: String,
    icon: ImageVector,
    background: Color = Surface1,
    border: Color = BorderFaint,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun KeyButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) Surface2 else Surface2.copy(alpha = 0.45f))
            .border(1.dp, if (enabled) BorderSubtle else BorderSubtle.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (enabled) Color.White else Text4, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            color = if (enabled) Color.White else Text4,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

private data class RevealedSecret(
    val nsec: String,
    val hex: String,
)

private fun remainingSeconds(expiresAtMillis: Long): Long {
    val remaining = expiresAtMillis - SystemClock.elapsedRealtime()
    return ((remaining + 999L) / 1_000L).coerceAtLeast(0L)
}

private fun hasDeviceLock(context: Context): Boolean {
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    if (keyguard?.isDeviceSecure != true) return false
    return BiometricManager.from(context).canAuthenticate(AuthenticatorFlags) == BiometricManager.BIOMETRIC_SUCCESS
}

private fun copyToClipboard(context: Context, label: String, value: String, sensitive: Boolean) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, value)
    if (sensitive) {
        val extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clip.description.extras = extras
    }
    clipboard.setPrimaryClip(clip)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun openAmber(context: Context) {
    val packageManager = context.packageManager
    val launchIntent = packageManager.getLaunchIntentForPackage(AmberSigner.AMBER_PACKAGE)
    if (launchIntent != null) {
        context.startActivity(launchIntent)
        return
    }
    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${AmberSigner.AMBER_PACKAGE}"))
    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${AmberSigner.AMBER_PACKAGE}"))
    val intent = if (marketIntent.resolveActivity(packageManager) != null) marketIntent else webIntent
    if (intent.resolveActivity(packageManager) != null) {
        context.startActivity(intent)
    }
}
