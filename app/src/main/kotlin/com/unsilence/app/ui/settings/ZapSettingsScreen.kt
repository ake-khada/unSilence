package com.unsilence.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.unsilence.app.data.wallet.ZapPreferences
import com.unsilence.app.data.wallet.ZapPreset
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.feed.ConnectWalletDialog
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap

@Composable
fun ZapSettingsScreen(onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val vm: ZapSettingsViewModel = hiltViewModel()
    val showSnackbar = LocalShowSnackbar.current
    val state by vm.preferences.collectAsState()
    val balanceSats by vm.balanceSats.collectAsState()
    var showConnectWallet by remember { mutableStateOf(false) }
    var showConfirmDisconnect by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshBalance() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // Top bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = Spacing.medium),
        ) {
            Text(
                "Zap settings",
                color = Color.White,
                fontSize = AppType.subheading,
                fontWeight = FontWeight.Medium,
            )
        }

        SectionLabel("Wallet")
        WalletCard(
            connected = vm.walletConnected,
            label = vm.walletLabel,
            balanceSats = balanceSats,
            onConnect = { showConnectWallet = true },
            onDisconnect = { showConfirmDisconnect = true },
        )

        SectionLabel("Presets")
        Column(
            modifier = Modifier
                .padding(horizontal = Spacing.medium)
                .background(Surface1, RoundedCornerShape(12.dp)),
        ) {
            state.presets.forEachIndexed { index, preset ->
                PresetRow(
                    isDefault = index == 0,
                    preset = preset,
                    onUpdate = { newAmount, newMessage ->
                        vm.updatePreset(index, newAmount, newMessage)
                    },
                )
                if (index < state.presets.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.medium)
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.04f)),
                    )
                }
            }
        }
        HelperText("Top amount is your one-tap default. Long-press the bolt to pick another.")

        SectionLabel("Privacy")
        PrivacyToggleRow(
            isPrivate = state.defaultPrivate,
            onChange = vm::setPrivacy,
        )
        HelperText("Public shows your npub on the receipt; private encrypts it so only the recipient knows.")

        Spacer(Modifier.height(Spacing.xl))
    }

    if (showConnectWallet) {
        ConnectWalletDialog(
            onConnect = { uri ->
                if (vm.saveNwcUri(uri)) {
                    showSnackbar("Wallet connected")
                }
                showConnectWallet = false
            },
            onDismiss = { showConnectWallet = false },
        )
    }

    if (showConfirmDisconnect) {
        AlertDialog(
            onDismissRequest = { showConfirmDisconnect = false },
            containerColor = Surface1,
            title = { Text("Disconnect wallet", color = Color.White) },
            text = { Text("One-tap zaps will stop working until you reconnect.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    vm.disconnectWallet()
                    showConfirmDisconnect = false
                }) { Text("Disconnect", color = Like) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDisconnect = false }) {
                    Text("Cancel", color = Brand)
                }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = TextSecondary,
        fontSize = AppType.caption,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(
            start = Spacing.medium + 2.dp,
            top = Spacing.medium,
            bottom = Spacing.small,
        ),
    )
}

@Composable
private fun HelperText(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = AppType.caption,
        modifier = Modifier.padding(
            horizontal = Spacing.medium + 4.dp,
            vertical = Spacing.small,
        ),
    )
}

@Composable
private fun WalletCard(
    connected: Boolean,
    label: String?,
    balanceSats: Long?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = Spacing.medium)
            .fillMaxWidth()
            .background(Surface1, RoundedCornerShape(12.dp))
            .clickable(onClick = if (connected) onDisconnect else onConnect)
            .padding(Spacing.medium),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(Zap.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Bolt, contentDescription = null, tint = Zap, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(Spacing.medium))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (connected) (label ?: "Connected") else "No wallet connected",
                color = Color.White,
                fontSize = AppType.bodySmall,
            )
            if (connected && balanceSats != null) {
                Text(
                    "${formatSats(balanceSats)} sats available",
                    color = Zap,
                    fontSize = AppType.caption,
                )
            } else {
                Text(
                    if (connected) "Tap to disconnect" else "Tap to connect via NWC",
                    color = TextSecondary,
                    fontSize = AppType.caption,
                )
            }
        }
        Text(
            if (connected) "Disconnect" else "Connect",
            color = Brand,
            fontSize = AppType.bodySmall,
        )
    }
}

private fun formatSats(sats: Long): String = when {
    sats >= 1_000_000 -> "%.1fM".format(sats / 1_000_000.0)
    sats >= 1_000     -> "%,d".format(sats)
    else              -> sats.toString()
}

@Composable
private fun PresetRow(
    isDefault: Boolean,
    preset: ZapPreset,
    onUpdate: (amountSats: Long?, message: String?) -> Unit,
) {
    var amountText by remember(preset.amountSats) { mutableStateOf(preset.amountSats.toString()) }
    var messageText by remember(preset.message) { mutableStateOf(preset.message ?: "") }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (isDefault) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Default",
                    tint = Brand,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = amountText,
            onValueChange = { v ->
                amountText = v.filter { it.isDigit() }.take(9)
                amountText.toLongOrNull()?.takeIf { it > 0 }?.let { onUpdate(it, null) }
            },
            textStyle = TextStyle(
                color = Zap,
                fontSize = AppType.bodySmall,
                fontWeight = FontWeight.Medium,
            ),
            cursorBrush = SolidColor(Zap),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(60.dp),
        )
        Text("sats", color = TextSecondary, fontSize = AppType.caption)
        Spacer(Modifier.width(Spacing.small))
        BasicTextField(
            value = messageText,
            onValueChange = { v ->
                messageText = v.take(140)
                onUpdate(null, messageText)
            },
            textStyle = TextStyle(color = Color.White.copy(alpha = 0.78f), fontSize = AppType.bodySmall),
            cursorBrush = SolidColor(Brand),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (messageText.isEmpty()) {
                    Text("optional message", color = TextSecondary.copy(alpha = 0.5f), fontSize = AppType.bodySmall)
                }
                inner()
            },
        )
    }
}

@Composable
private fun PrivacyToggleRow(isPrivate: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = Spacing.medium)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PrivacyPill(
            label = "Public",
            icon = Icons.Filled.Public,
            selected = !isPrivate,
            onClick = { onChange(false) },
            modifier = Modifier.weight(1f),
        )
        PrivacyPill(
            label = "Private",
            icon = Icons.Filled.Lock,
            selected = isPrivate,
            onClick = { onChange(true) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PrivacyPill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fg = if (selected) Brand else TextSecondary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .background(
                if (selected) Brand.copy(alpha = 0.10f) else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .border(
                width = 1.dp,
                color = if (selected) Brand else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, fontSize = AppType.bodySmall, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}
