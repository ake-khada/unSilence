package com.unsilence.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.SurfaceVariant
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.ZapAmber

private val PRESET_AMOUNTS = listOf(21L, 100L, 500L, 1_000L, 5_000L)

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
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                BasicTextField(
                    value         = uri,
                    onValueChange = { uri = it },
                    textStyle     = TextStyle(color = Color.White, fontSize = 13.sp),
                    cursorBrush   = SolidColor(Cyan),
                    modifier      = Modifier
                        .fillMaxWidth()
                        .background(Black, RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF333333), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        if (uri.isEmpty()) {
                            Text("nostr+walletconnect://…", color = TextSecondary, fontSize = 13.sp)
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
                Text("Connect", color = Cyan)
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
 * Bottom-sheet-style dialog for choosing the zap amount.
 * Shows preset buttons (21, 100, 500, 1000, 5000) plus a custom text field.
 */
@Composable
fun ZapAmountDialog(
    onZap: (amountSats: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<Long?>(1_000L) }
    var custom   by remember { mutableStateOf("") }

    val effectiveAmount: Long? = if (custom.isNotBlank()) {
        custom.toLongOrNull()?.takeIf { it > 0 }
    } else {
        selected
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = SurfaceVariant,
        title = {
            Text("Zap amount (sats)", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ── Preset buttons ──────────────────────────────────────────
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PRESET_AMOUNTS.forEach { amount ->
                        val isSelected = selected == amount && custom.isBlank()
                        Text(
                            text      = formatPreset(amount),
                            color     = if (isSelected) Black else ZapAmber,
                            fontSize  = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier  = Modifier
                                .weight(1f)
                                .background(
                                    color = if (isSelected) ZapAmber else Color(0xFF1A1A1A),
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .clickable { selected = amount; custom = "" }
                                .padding(vertical = 8.dp),
                        )
                    }
                }

                // ── Custom amount input ─────────────────────────────────────
                BasicTextField(
                    value         = custom,
                    onValueChange = { custom = it.filter { c -> c.isDigit() } },
                    textStyle     = TextStyle(
                        color     = Color.White,
                        fontSize  = 14.sp,
                        textAlign = TextAlign.Start,
                    ),
                    cursorBrush  = SolidColor(ZapAmber),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier     = Modifier
                        .fillMaxWidth()
                        .background(Black, RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF333333), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        if (custom.isEmpty()) {
                            Text("Custom amount…", color = TextSecondary, fontSize = 14.sp)
                        }
                        inner()
                    },
                )

                effectiveAmount?.let { amt ->
                    Text(
                        text     = "⚡ $amt sats",
                        color    = ZapAmber,
                        fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick  = { effectiveAmount?.let { onZap(it) } },
                enabled  = effectiveAmount != null,
            ) {
                Text("Zap ⚡", color = ZapAmber)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
    )
}

private fun formatPreset(sats: Long): String = when {
    sats < 1_000  -> "$sats"
    sats < 10_000 -> "${sats / 1_000}k"
    else          -> "${sats / 1_000}k"
}
