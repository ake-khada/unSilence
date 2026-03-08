package com.unsilence.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub

private val IconMuted    = Color(0xFF555555)
private val LogoutRed    = Color(0xFFCF6679)
private val DrawerWidth  = 300.dp
private val ItemHeight   = Sizing.topBarHeight   // 52.dp
private val SubItemHeight = 44.dp
private val IndentPad    = 36.dp

@Composable
fun AppDrawer(
    keyManager: KeyManager,
    onLogout: () -> Unit,
) {
    val pubkeyHex = keyManager.getPublicKeyHex()
    val npub      = pubkeyHex?.let { hex ->
        runCatching { hex.hexToByteArray().toNpub() }.getOrNull()
    }
    val npubShort = npub?.let { "${it.take(12)}…${it.takeLast(6)}" } ?: "Not logged in"

    var feedsExpanded    by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .width(DrawerWidth)
            .fillMaxHeight()
            .background(Black)
            .verticalScroll(rememberScrollState()),
    ) {

        // ── User section ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.medium, vertical = Spacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(Sizing.avatar)
                    .clip(CircleShape),
            ) {
                if (pubkeyHex != null) {
                    IdentIcon(pubkey = pubkeyHex, modifier = Modifier.size(Sizing.avatar))
                } else {
                    Box(modifier = Modifier.size(Sizing.avatar).background(IconMuted))
                }
            }

            Spacer(Modifier.width(Spacing.small))

            Column {
                if (pubkeyHex != null) {
                    Text(
                        text       = "Anonymous",   // display name — profile fetch in later sprint
                        color      = Color.White,
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text     = npubShort,
                    color    = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // ── Quick-action icons: Tor, Theme, QR, Zap ─────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.medium, end = Spacing.medium, bottom = Spacing.small),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            QuickIcon(Icons.Filled.Hub,         "Tor",    onClick = {})
            QuickIcon(Icons.Filled.WbTwilight,  "Theme",  onClick = {})
            QuickIcon(Icons.Filled.QrCode,      "QR",     onClick = {})
            QuickIcon(Icons.Filled.ElectricBolt,"Wallet", onClick = {})
        }

        HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(vertical = Spacing.small))

        // ── Menu items ────────────────────────────────────────────────────────

        DrawerItem(icon = Icons.Filled.Person, label = "Profile", onClick = {})

        // Feeds — expandable
        DrawerItem(
            icon        = Icons.Filled.DynamicFeed,
            label       = "Feeds",
            trailing    = if (feedsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            onClick     = { feedsExpanded = !feedsExpanded },
        )
        AnimatedVisibility(
            visible = feedsExpanded,
            enter   = expandVertically(),
            exit    = shrinkVertically(),
        ) {
            Column {
                SubItem(label = "Following",     onClick = {})
                SubItem(label = "Global",         onClick = {})
                SubItem(label = "+ Add Feed/Set", onClick = {}, color = Cyan)
            }
        }

        DrawerItem(icon = Icons.Filled.Search,       label = "Search",  onClick = {})
        DrawerItem(icon = Icons.Filled.ElectricBolt, label = "Wallet",  onClick = {})
        DrawerItem(icon = Icons.Filled.List,          label = "Lists",   onClick = {})
        DrawerItem(icon = Icons.Filled.Drafts,        label = "Drafts",  onClick = {})

        // Settings — expandable
        DrawerItem(
            icon     = Icons.Filled.Settings,
            label    = "Settings",
            trailing = if (settingsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            onClick  = { settingsExpanded = !settingsExpanded },
        )
        AnimatedVisibility(
            visible = settingsExpanded,
            enter   = expandVertically(),
            exit    = shrinkVertically(),
        ) {
            Column {
                SubItem("Relays",         onClick = {})
                SubItem("Media Servers",  onClick = {})
                SubItem("Keys",           onClick = {})
                SubItem("Safety",         onClick = {})
                SubItem("Social Graph",   onClick = {})
                SubItem("Custom Emojis",  onClick = {})
                SubItem("Console",        onClick = {})
            }
        }

        Spacer(Modifier.height(32.dp))

        HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(vertical = Spacing.small))

        // ── Logout ────────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ItemHeight)
                .clickable { onLogout() }
                .padding(horizontal = Spacing.medium),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text       = "Logout",
                color      = LogoutRed,
                fontSize   = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(Spacing.large))
    }
}

// ── Private sub-composables ───────────────────────────────────────────────────

@Composable
private fun QuickIcon(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Icon(
        imageVector        = icon,
        contentDescription = contentDescription,
        tint               = IconMuted,
        modifier           = Modifier
            .size(Sizing.navIcon)   // 20.dp
            .clickable(onClick = onClick),
    )
}

@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    trailing: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ItemHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = IconMuted,
            modifier           = Modifier.size(Sizing.navIcon),
        )
        Spacer(Modifier.width(Spacing.medium))
        Text(
            text     = label,
            color    = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Icon(
                imageVector        = trailing,
                contentDescription = null,
                tint               = IconMuted,
                modifier           = Modifier.size(Sizing.navIcon),
            )
        }
    }
}

@Composable
private fun SubItem(label: String, onClick: () -> Unit, color: Color = TextSecondary) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SubItemHeight)
            .clickable(onClick = onClick)
            .padding(start = IndentPad, end = Spacing.medium),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = label, color = color, fontSize = 13.sp)
    }
}
