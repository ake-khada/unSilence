package com.unsilence.app.ui.feed

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unsilence.app.data.model.BitcoinAddressFormat
import com.unsilence.app.data.model.BitcoinNetwork
import com.unsilence.app.data.model.LightningNetwork
import com.unsilence.app.data.model.PaymentTarget
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.BorderSubtle
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.NumberFormat

private val BitcoinOrange = Color(0xFFF7931A)

private data class PaymentCardPresentation(
    val title: String,
    val summary: String,
    val action: String,
    val accent: Color,
    val icon: ImageVector,
)

/** A compact, explicit-action card for a parsed Bitcoin or Lightning target. */
@Composable
internal fun PaymentTargetCard(
    target: PaymentTarget,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    val presentation = remember(target) { target.presentation() }
    var status by remember(target) { mutableStateOf<String?>(null) }

    LaunchedEffect(status) {
        if (status != null) {
            delay(1_800L)
            status = null
        }
    }

    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(presentation.accent.copy(alpha = 0.12f), Surface1),
                ),
            )
            .border(1.dp, presentation.accent.copy(alpha = 0.28f), shape)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .background(presentation.accent.copy(alpha = 0.16f), CircleShape),
            ) {
                Icon(
                    imageVector = presentation.icon,
                    contentDescription = null,
                    tint = presentation.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(Spacing.small))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = presentation.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = AppType.body,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = presentation.summary,
                    color = TextSecondary,
                    fontSize = AppType.footnote,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Text(
            text = target.displayValue,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
            fontSize = AppType.caption,
            fontFamily = FontFamily.Monospace,
            lineHeight = AppType.subheading,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.small)
                .background(Color.Black.copy(alpha = 0.24f), RoundedCornerShape(6.dp))
                .border(0.5.dp, BorderSubtle, RoundedCornerShape(6.dp))
                .padding(horizontal = Spacing.small, vertical = Spacing.micro),
        )

        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            status?.let {
                Text(
                    text = it,
                    color = TextSecondary,
                    fontSize = AppType.caption,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } ?: Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("Payment target", target.copyText)),
                        )
                        status = "Copied"
                    }
                },
                colors = ButtonDefaults.textButtonColors(contentColor = presentation.accent),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(Spacing.micro))
                Text("Copy", fontSize = AppType.bodySmall)
            }
            TextButton(
                onClick = {
                    status = runCatching { uriHandler.openUri(target.walletUri) }
                        .fold(onSuccess = { null }, onFailure = { "No compatible wallet" })
                },
                colors = ButtonDefaults.textButtonColors(contentColor = presentation.accent),
            ) {
                Icon(
                    imageVector = Icons.Outlined.AccountBalanceWallet,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(Spacing.micro))
                Text(presentation.action, fontSize = AppType.bodySmall)
            }
        }
    }
}

private fun PaymentTarget.presentation(): PaymentCardPresentation = when (this) {
    is PaymentTarget.LightningInvoice -> PaymentCardPresentation(
        title = "Lightning invoice",
        summary = listOfNotNull(
            amountMsats?.let(::formatMillisats) ?: "Open amount",
            network.label(),
        ).joinToString(" · "),
        action = "Pay",
        accent = Zap,
        icon = Icons.Filled.ElectricBolt,
    )
    is PaymentTarget.LightningAddress -> PaymentCardPresentation(
        title = "Lightning address",
        summary = domain,
        action = "Open wallet",
        accent = Zap,
        icon = Icons.Filled.ElectricBolt,
    )
    is PaymentTarget.Lnurl -> PaymentCardPresentation(
        title = "LNURL",
        summary = host,
        action = "Open wallet",
        accent = Zap,
        icon = Icons.Filled.ElectricBolt,
    )
    is PaymentTarget.Bitcoin -> PaymentCardPresentation(
        title = if (explicitUri) "Bitcoin payment request" else "Bitcoin address",
        summary = listOfNotNull(
            amountSats?.let(::formatSats),
            network.label(),
            format.label(),
        ).joinToString(" · "),
        action = "Open wallet",
        accent = BitcoinOrange,
        icon = Icons.Filled.CurrencyBitcoin,
    )
}

private fun formatMillisats(msats: Long): String {
    if (msats % 1_000L == 0L) return formatSats(msats / 1_000L)
    val sats = BigDecimal.valueOf(msats).movePointLeft(3).stripTrailingZeros().toPlainString()
    return "$sats sats"
}

private fun formatSats(sats: Long): String =
    "${NumberFormat.getIntegerInstance().format(sats)} ${if (sats == 1L) "sat" else "sats"}"

private fun LightningNetwork.label(): String = when (this) {
    LightningNetwork.MAINNET -> "Mainnet"
    LightningNetwork.TESTNET -> "Testnet"
    LightningNetwork.REGTEST -> "Regtest"
    LightningNetwork.SIGNET -> "Signet"
    LightningNetwork.SIMNET -> "Simnet"
}

private fun BitcoinNetwork.label(): String = when (this) {
    BitcoinNetwork.MAINNET -> "Mainnet"
    BitcoinNetwork.TESTNET -> "Testnet / regtest"
    BitcoinNetwork.REGTEST -> "Regtest"
}

private fun BitcoinAddressFormat.label(): String = when (this) {
    BitcoinAddressFormat.P2PKH -> "Legacy"
    BitcoinAddressFormat.P2SH -> "Script hash"
    BitcoinAddressFormat.SEGWIT -> "SegWit"
    BitcoinAddressFormat.TAPROOT -> "Taproot"
    BitcoinAddressFormat.WITNESS -> "Witness"
}
