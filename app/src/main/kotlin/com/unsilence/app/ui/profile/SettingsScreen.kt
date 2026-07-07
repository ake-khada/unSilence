package com.unsilence.app.ui.profile

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.BuildConfig
import com.unsilence.app.data.drafts.Draft
import com.unsilence.app.data.drafts.DraftContext
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.common.LocalOpenZapSettings
import com.unsilence.app.ui.compose.ArticleCommentTarget
import com.unsilence.app.ui.compose.ComposeScreen
import com.unsilence.app.ui.drafts.DraftsScreen
import com.unsilence.app.ui.drafts.DraftsViewModel
import com.unsilence.app.ui.feed.AvatarImage
import com.unsilence.app.ui.relays.RelayDetailScreen
import com.unsilence.app.ui.relays.RelayDiscoveryScreen
import com.unsilence.app.ui.relays.RelayManagementScreen
import com.unsilence.app.ui.settings.MediaUploadSettingsScreen
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BorderFaint
import com.unsilence.app.ui.theme.BorderSubtle
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Like
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.Text4
import com.unsilence.app.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    onDismiss: () -> Unit,
    onLogout: () -> Unit,
    onEditProfile: () -> Unit = {},
    onOpenProfile: (pubkeyHex: String) -> Unit = {},
    onBrowseRelay: (url: String, label: String) -> Unit = { _, _ -> },
    viewModel: SettingsViewModel = hiltViewModel(
        key = "settings-${LocalAppSessionKey.current}",
    ),
    draftsViewModel: DraftsViewModel = hiltViewModel(
        key = "drafts-settings-${LocalAppSessionKey.current}",
    ),
) {
    BackHandler(onBack = onDismiss)
    var showRelays by remember { mutableStateOf(false) }
    var relayDetailUrl by remember { mutableStateOf<String?>(null) }
    var showDiscovery by remember { mutableStateOf(false) }
    var showMediaUpload by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showSocialGraph by remember { mutableStateOf(false) }
    var showCustomEmojis by remember { mutableStateOf(false) }
    var showDrafts by remember { mutableStateOf(false) }
    var draftToResume by remember { mutableStateOf<Draft?>(null) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    val openZapSettings = LocalOpenZapSettings.current
    val drafts by draftsViewModel.drafts.collectAsStateWithLifecycle()

    // One-shot snapshots, read on open (lean — no ticker).
    val profile = remember(viewModel) { viewModel.ownProfile() }
    val onlineCount = remember(viewModel) { viewModel.onlineRelayCount() }

    Box(modifier = Modifier.fillMaxSize().background(Black)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Title bar ── (gesture-dismissed; no back arrow) ─────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(Sizing.topBarHeight)
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Settings",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {

                // ── Account card ───────────────────────────────────────────────
                // Tap → Edit Profile (the account hub's real action). The profile page keeps
                // a de-emphasized "Edit Profile" entry too. PHASE: also home for Keys when it ships.
                Row(
                    modifier = Modifier
                        .padding(horizontal = 14.dp)
                        .padding(top = Spacing.small, bottom = Spacing.small)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Surface1)
                        .border(1.dp, BorderFaint, RoundedCornerShape(14.dp))
                        .clickable(onClick = onEditProfile)
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AvatarImage(
                        pubkey = viewModel.ownPubkey ?: "",
                        picture = profile?.picture,
                        modifier = Modifier.size(40.dp),   // layout size comes from modifier, not sizeDp
                        sizeDp = 40.dp,
                    )
                    Spacer(Modifier.width(Spacing.medium))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile?.displayName?.takeIf { it.isNotBlank() }
                                ?: profile?.name?.takeIf { it.isNotBlank() }
                                ?: "Your profile",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            text = viewModel.ownNpub?.let { "${it.take(12)}…${it.takeLast(6)}" } ?: "",
                            color = Text3,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = Text3, modifier = Modifier.size(16.dp))
                }

                // ── NETWORK ────────────────────────────────────────────────────
                GroupLabel("Network")
                SettingsRow(Icons.Filled.Dns, "Relays", "Where your notes are published & read",
                    badge = if (onlineCount > 0) "$onlineCount online" else null) { showRelays = true }
                SettingsRow(Icons.Filled.PhotoLibrary, "Media uploads", "Where images & video are hosted") { showMediaUpload = true }

                // ── WALLET ─────────────────────────────────────────────────────
                GroupLabel("Wallet")
                SettingsRow(Icons.Filled.ElectricBolt, "Zaps", "Default amount & wallet connection") { openZapSettings() }

                // ── CONTENT & SAFETY ───────────────────────────────────────────
                GroupLabel("Content & safety")
                SettingsRow(Icons.Filled.Security, "Filters", "Mute words, hide unwanted content") { showFilters = true }
                SettingsRow(
                    Icons.Filled.Drafts,
                    "Drafts",
                    "Saved notes for this account",
                    badge = drafts.takeIf { it.isNotEmpty() }?.size?.toString(),
                ) { showDrafts = true }
                SettingsRow(Icons.Filled.EmojiEmotions, "Custom emojis", "Manage your emoji packs") { showCustomEmojis = true }
                SettingsRow(Icons.Filled.AccountTree, "Social graph", "Web-of-trust provider and coverage") { showSocialGraph = true }

                // ── ADVANCED ───────────────────────────────────────────────────
                GroupLabel("Advanced")
                SoonRow(Icons.Filled.Key, "Keys")
                SoonRow(Icons.Filled.Code, "Console")

                // ── Danger zone ────────────────────────────────────────────────
                Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = Spacing.large)) {
                    HorizontalDivider(color = BorderFaint)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showLogoutConfirm = true }
                            .padding(top = Spacing.medium, bottom = Spacing.micro),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, null, tint = Like, modifier = Modifier.size(17.dp))
                        Text("Log out", color = Like, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Text(
                        text = "unSilence v${BuildConfig.VERSION_NAME}",
                        color = Text4,
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 14.dp, bottom = Spacing.xl),
                    )
                }
            }
        }
    }

    if (showLogoutConfirm) {
        LogoutConfirmSheet(
            onConfirm = { showLogoutConfirm = false; onLogout() },
            onDismiss = { showLogoutConfirm = false },
        )
    }
    if (showRelays) RelayManagementScreen(
        onDismiss = { showRelays = false },
        onOpenDetail = { url -> relayDetailUrl = url },
        onOpenDiscovery = { showDiscovery = true },
    )
    if (showDiscovery) RelayDiscoveryScreen(
        onDismiss = { showDiscovery = false },
        onOpenDetail = { url -> relayDetailUrl = url },
    )
    relayDetailUrl?.let { url ->
        RelayDetailScreen(relayUrl = url, onDismiss = { relayDetailUrl = null }, onOpenProfile = onOpenProfile, onBrowse = onBrowseRelay)
    }
    if (showMediaUpload) MediaUploadSettingsScreen(onDismiss = { showMediaUpload = false })
    if (showFilters) FiltersScreen(onDismiss = { showFilters = false })
    if (showSocialGraph) SocialGraphScreen(onDismiss = { showSocialGraph = false })
    if (showCustomEmojis) com.unsilence.app.ui.settings.CustomEmojisScreen(onDismiss = { showCustomEmojis = false })
    if (showDrafts) {
        DraftsScreen(
            onDismiss = { showDrafts = false },
            onResume = { draft ->
                showDrafts = false
                draftToResume = draft
            },
            viewModel = draftsViewModel,
        )
    }
    draftToResume?.let { draft ->
        ComposeScreen(
            onDismiss = { draftToResume = null },
            replyToEventId = (draft.context as? DraftContext.Reply)?.parentId,
            quoteEventId = (draft.context as? DraftContext.Quote)?.eventId,
            articleCommentTarget = (draft.context as? DraftContext.ArticleComment)?.toArticleCommentTarget(),
            initialDraft = draft,
        )
    }
}

private fun DraftContext.ArticleComment.toArticleCommentTarget(): ArticleCommentTarget =
    ArticleCommentTarget(
        articleId = articleId,
        articleCoord = articleCoord,
        articlePubkey = articlePubkey,
        articleRelayHint = articleRelayHint,
        parentId = parentId,
        parentKind = parentKind,
        parentPubkey = parentPubkey,
        parentRelayHint = parentRelayHint,
    )

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = Text3,
        fontSize = 9.5.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 7.dp),
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    description: String,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(description, color = Text3, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (badge != null) {
            Text(
                text = badge,
                color = Mint,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Mint.copy(alpha = 0.10f))
                    .border(1.dp, Mint.copy(alpha = 0.25f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(Spacing.small))
        }
        Icon(Icons.Filled.ChevronRight, null, tint = TextSecondary, modifier = Modifier.size(15.dp))
    }
}

/** Soon row — single line, dimmed, transparent icon tile, "Soon" chip. Not tappable. */
@Composable
private fun SoonRow(icon: ImageVector, title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(0.45f)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(
            text = "SOON",
            color = Text3,
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.8.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .border(1.dp, BorderSubtle, RoundedCornerShape(999.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogoutConfirmSheet(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.large, vertical = Spacing.small)) {
            Text("Log out?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "You'll need your key to sign back in. Your notes stay on the relays.",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = Spacing.small),
            )
            Spacer(Modifier.height(Spacing.large))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Like)
                    .clickable(onClick = onConfirm)
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text("Log out", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text("Cancel", color = TextSecondary, fontSize = 14.sp)
            }
            Spacer(Modifier.height(Spacing.large))
        }
    }
}
