package com.unsilence.app.ui.profile

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.ui.feed.AvatarImage
import com.unsilence.app.ui.feed.SheetActionRow
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.TextSecondary

@Composable
fun FiltersScreen(
    onDismiss: () -> Unit,
    viewModel: FiltersViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onDismiss)
    val muteList by viewModel.muteList.collectAsStateWithLifecycle()
    val sensitiveMode by viewModel.sensitiveContentMode.collectAsStateWithLifecycle()

    var detailPubkey by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top bar ───────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Black)
                    .statusBarsPadding()
                    .height(Sizing.topBarHeight)
                    .padding(horizontal = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
                Text(
                    text = "Filters",
                    color = Color.White,
                    fontSize = AppType.subheading,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = Spacing.small),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            LazyColumn(modifier = Modifier.fillMaxSize()) {

                // ── Sensitive content section ─────────────────────────
                item {
                    SectionHeader("Sensitive content (NIP-36)")
                }
                item {
                    SensitiveModeRow("Hide", "Filter out entirely", SensitiveContentMode.HIDE, sensitiveMode) {
                        viewModel.setSensitiveContentMode(SensitiveContentMode.HIDE)
                    }
                }
                item {
                    SensitiveModeRow("Blur", "Blurred preview, tap to reveal", SensitiveContentMode.BLUR, sensitiveMode) {
                        viewModel.setSensitiveContentMode(SensitiveContentMode.BLUR)
                    }
                }
                item {
                    SensitiveModeRow("Show", "No filtering or overlay", SensitiveContentMode.SHOW, sensitiveMode) {
                        viewModel.setSensitiveContentMode(SensitiveContentMode.SHOW)
                    }
                }

                // ── Muted users section ──────────────────────────────
                item {
                    Spacer(Modifier.height(Spacing.large))
                    SectionHeader("Muted users")
                }

                val allMutedPubkeys = muteList?.let {
                    (it.pubkeys + it.privatePubkeys).toList().sorted()
                } ?: emptyList()

                if (allMutedPubkeys.isEmpty()) {
                    item {
                        Text(
                            text = "No muted users",
                            color = TextSecondary,
                            fontSize = AppType.body,
                            modifier = Modifier.padding(horizontal = Spacing.large, vertical = Spacing.medium),
                        )
                    }
                } else {
                    items(allMutedPubkeys, key = { it }) { pubkey ->
                        val profile = remember(pubkey) { viewModel.getProfile(pubkey) }
                        MutedUserRow(
                            pubkey = pubkey,
                            profile = profile,
                            onClick = { detailPubkey = pubkey },
                        )
                    }
                }

                // ── Muted hashtags section ───────────────────────────
                val allHashtags = muteList?.let {
                    (it.hashtags + it.privateHashtags).toList().sorted()
                } ?: emptyList()

                if (allHashtags.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(Spacing.large))
                        SectionHeader("Muted hashtags")
                    }
                    items(allHashtags, key = { "t:$it" }) { tag ->
                        MutedTagRow(tag = "#$tag")
                    }
                }

                // ── Muted words section ──────────────────────────────
                val allWords = muteList?.let {
                    (it.words + it.privateWords).toList().sorted()
                } ?: emptyList()

                if (allWords.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(Spacing.large))
                        SectionHeader("Muted words")
                    }
                    items(allWords, key = { "w:$it" }) { word ->
                        MutedTagRow(tag = word)
                    }
                }

                item { Spacer(Modifier.height(Spacing.xxl)) }
            }
        }
    }

    // ── Muted user detail sheet ──────────────────────────────────────
    detailPubkey?.let { pubkey ->
        val profile = remember(pubkey) { viewModel.getProfile(pubkey) }
        MutedUserDetailSheet(
            pubkey = pubkey,
            profile = profile,
            onDismiss = { detailPubkey = null },
            onUnmute = {
                viewModel.unmuteUser(pubkey)
                detailPubkey = null
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = TextSecondary,
        fontSize = AppType.caption,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = Spacing.large, vertical = Spacing.small),
    )
}

@Composable
private fun SensitiveModeRow(
    label: String,
    subtitle: String,
    mode: SensitiveContentMode,
    current: SensitiveContentMode,
    onSelect: () -> Unit,
) {
    val selected = mode == current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = Spacing.large, vertical = Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (selected) Cyan else Color.White,
                fontSize = AppType.body,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = AppType.caption,
            )
        }
        if (selected) {
            Text("✓", color = Cyan, fontSize = AppType.body)
        }
    }
}

@Composable
private fun MutedUserRow(
    pubkey: String,
    profile: UserEntity?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.large, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarImage(
            pubkey = pubkey,
            picture = profile?.picture,
            sizeDp = 36.dp,
        )
        Spacer(Modifier.width(Spacing.medium))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile?.displayName?.takeIf { it.isNotBlank() }
                    ?: profile?.name?.takeIf { it.isNotBlank() }
                    ?: "${pubkey.take(8)}…",
                color = Color.White,
                fontSize = AppType.body,
            )
            profile?.nip05?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = TextSecondary, fontSize = AppType.caption)
            }
        }
    }
    HorizontalDivider(
        color = Color.White.copy(alpha = 0.06f),
        modifier = Modifier.padding(start = Spacing.large + 36.dp + Spacing.medium),
    )
}

@Composable
private fun MutedTagRow(tag: String) {
    Text(
        text = tag,
        color = Color.White,
        fontSize = AppType.body,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.large, vertical = Spacing.medium),
    )
    HorizontalDivider(
        color = Color.White.copy(alpha = 0.06f),
        modifier = Modifier.padding(start = Spacing.large),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MutedUserDetailSheet(
    pubkey: String,
    profile: UserEntity?,
    onDismiss: () -> Unit,
    onUnmute: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary) },
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarImage(
                    pubkey = pubkey,
                    picture = profile?.picture,
                    sizeDp = 36.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = profile?.displayName?.takeIf { it.isNotBlank() }
                            ?: profile?.name?.takeIf { it.isNotBlank() }
                            ?: "${pubkey.take(8)}…",
                        fontSize = AppType.body,
                        color = Color.White,
                    )
                    profile?.nip05?.takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = AppType.caption, color = TextSecondary)
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            SheetActionRow(Icons.AutoMirrored.Filled.VolumeOff, "Unmute") {
                onUnmute()
            }
        }
    }
}
