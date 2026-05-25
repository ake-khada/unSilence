package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.unsilence.app.data.wallet.ZapRequest
import com.unsilence.app.ui.common.LocalZapPreferences
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.SurfaceVariant
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap

/**
 * Dialog shown when the user taps ⚡ without a configured NWC wallet.
 * Provides a text field to paste a nostr+walletconnect:// URI.
 */
@Composable
fun ConnectWalletDialog(
    onConnect: (uri: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var uri by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = SurfaceVariant,
        title = {
            Text("Connect Wallet", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    text     = "Paste a nostr+walletconnect:// URI to enable one-tap zaps.",
                    color    = TextSecondary,
                    fontSize = AppType.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                BasicTextField(
                    value         = uri,
                    onValueChange = { uri = it },
                    textStyle     = TextStyle(color = Color.White, fontSize = AppType.bodySmall),
                    cursorBrush   = SolidColor(Brand),
                    modifier      = Modifier
                        .fillMaxWidth()
                        .background(Black, RoundedCornerShape(8.dp))
                        .border(1.dp, Surface1, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        if (uri.isEmpty()) {
                            Text("nostr+walletconnect://…", color = TextSecondary, fontSize = AppType.bodySmall)
                        }
                        inner()
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick  = { onConnect(uri) },
                enabled  = uri.isNotBlank(),
            ) {
                Text("Connect", color = Brand)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
    )
}

/**
 * Bottom-sheet zap picker matching the mockup: 5 preset chips, message
 * input, public/private toggle, full-width "Zap X sats" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZapAmountDialog(
    onZap: (request: ZapRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    val prefs = LocalZapPreferences.current
    val presetAmounts = prefs.presets.map { it.amountSats }
    // Pre-select the first preset's stored message (if any).
    var selected by remember { mutableStateOf(presetAmounts.firstOrNull() ?: 21L) }
    var message by remember {
        mutableStateOf(prefs.presets.firstOrNull()?.message ?: "")
    }
    var isPrivate by remember { mutableStateOf(prefs.defaultPrivate) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Black,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 14.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(
                        Color.White.copy(alpha = 0.22f),
                        RoundedCornerShape(2.dp),
                    ),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp),
        ) {
            // ── Title row ──────────────────────────────────────────────────
            Text(
                "Zap",
                color = Color.White,
                fontSize = AppType.subheading,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(14.dp))

            // ── Preset chips ───────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                presetAmounts.forEachIndexed { index, amount ->
                    val isSelected = selected == amount
                    Text(
                        text = formatPreset(amount),
                        color = if (isSelected) Zap else Color.White.copy(alpha = 0.55f),
                        fontSize = AppType.footnote,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                color = if (isSelected) Zap.copy(alpha = 0.12f) else Surface2,
                                shape = RoundedCornerShape(10.dp),
                            )
                            .then(
                                if (isSelected) Modifier.border(1.dp, Zap, RoundedCornerShape(10.dp))
                                else Modifier
                            )
                            .clickable {
                                selected = amount
                                // Load stored message for this preset.
                                message = prefs.presets.getOrNull(index)?.message ?: ""
                            }
                            .padding(vertical = 9.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── Message input ──────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface2, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Icon(
                    Icons.Filled.Message,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = message,
                    onValueChange = { message = it.take(140) },
                    textStyle = TextStyle(color = Color.White, fontSize = AppType.bodySmall),
                    cursorBrush = SolidColor(Brand),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (message.isEmpty()) {
                            Text(
                                "optional message",
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = AppType.bodySmall,
                            )
                        }
                        inner()
                    },
                )
            }
            Spacer(Modifier.height(10.dp))

            // ── Privacy toggle ─────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PrivacyPill(
                    label = "Public",
                    icon = Icons.Filled.Public,
                    selected = !isPrivate,
                    onClick = { isPrivate = false },
                    modifier = Modifier.weight(1f),
                )
                PrivacyPill(
                    label = "Private",
                    icon = Icons.Filled.Lock,
                    selected = isPrivate,
                    onClick = { isPrivate = true },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(14.dp))

            // ── Zap button ─────────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Zap, RoundedCornerShape(12.dp))
                    .clickable {
                        onZap(
                            ZapRequest(
                                amountSats = selected,
                                message = message.takeIf { it.isNotBlank() },
                                isPrivate = isPrivate,
                            )
                        )
                    }
                    .padding(vertical = 14.dp),
            ) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = Black,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isPrivate) "Zap ${formatPreset(selected)} sats privately"
                    else "Zap ${formatPreset(selected)} sats",
                    color = Black,
                    fontSize = AppType.body,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
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
    val fg = if (selected) Brand else Color.White.copy(alpha = 0.5f)
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
            .padding(vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = fg,
            fontSize = AppType.footnote,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

private fun formatPreset(sats: Long): String = when {
    sats >= 1_000 -> "${sats.toFloat() / 1_000}".removeSuffix(".0") + "k"
    else          -> "$sats"
}
