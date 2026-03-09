package com.unsilence.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing

@Composable
fun SettingsScreen(onDismiss: () -> Unit, onLogout: () -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .background(Black)
                    .statusBarsPadding()
                    .height(Sizing.topBarHeight)
                    .padding(horizontal = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint               = Color.White,
                        modifier           = Modifier.size(22.dp),
                    )
                }
                Text(
                    text       = "Settings",
                    color      = Color.White,
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier   = Modifier.weight(1f),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            Spacer(Modifier.height(Spacing.small))

            // ── Menu items ─────────────────────────────────────────────────────
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                SettingsItem(icon = Icons.Filled.ElectricBolt, label = "Wallet",       onClick = {})
                SettingsItem(icon = Icons.Filled.Drafts,       label = "Drafts",       onClick = {})
                SettingsItem(icon = Icons.Filled.Key,          label = "Keys",         onClick = {})
                SettingsItem(icon = Icons.Filled.Security,     label = "Safety",       onClick = {})
                SettingsItem(icon = Icons.Filled.AccountTree,  label = "Social Graph", onClick = {})
                SettingsItem(icon = Icons.Filled.EmojiEmotions,label = "Custom Emojis",onClick = {})
                SettingsItem(icon = Icons.Filled.Code,         label = "Console",      onClick = {})

                Spacer(Modifier.height(Spacing.large))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                // ── Logout ────────────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clickable { onLogout() }
                        .padding(horizontal = Spacing.medium),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text     = "Logout",
                        color    = Color(0xFFCF6679),
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = Color(0xFF888888),
            modifier           = Modifier.size(Sizing.navIcon),
        )
        Spacer(Modifier.width(Spacing.medium))
        Text(
            text     = label,
            color    = Color.White,
            fontSize = 16.sp,
        )
    }
    HorizontalDivider(
        color     = MaterialTheme.colorScheme.surfaceVariant,
        thickness = 0.5.dp,
        modifier  = Modifier.padding(start = Spacing.medium + Sizing.navIcon + Spacing.medium),
    )
}
