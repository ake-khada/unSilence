package com.unsilence.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.unsilence.app.data.memory.CustomEmoji
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.ui.feed.EmojiPickerSheet
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BrandDeep
import com.unsilence.app.ui.theme.BrandSoft
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Warn

@Composable
fun CustomEmojisScreen(onDismiss: () -> Unit) {
    val viewModel: CustomEmojiSettingsViewModel = hiltViewModel()
    BackHandler(onBack = onDismiss)

    val pinnedEmojis    by viewModel.pinnedEmojis.collectAsStateWithLifecycle()
    val subscribedSets  by viewModel.subscribedSets.collectAsStateWithLifecycle()
    val discoverSets    by viewModel.discoverSets.collectAsStateWithLifecycle()
    val pasteState      by viewModel.pasteState.collectAsStateWithLifecycle()

    // Picker state for pinned strip
    var showPicker by remember { mutableStateOf(false) }

    // Paste field
    var pasteText by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top bar ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Black)
                    .statusBarsPadding()
                    .height(Sizing.topBarHeight)
                    .padding(horizontal = Spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Custom Emojis",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // ── Content ─────────────────────────────────────────────────────
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Spacing.xxl),
            ) {
                // ═══════════ ZONE 1 — PASTE BAR ═══════════
                item(key = "paste") {
                    PasteBar(
                        text = pasteText,
                        onTextChange = { pasteText = it },
                        pasteState = pasteState,
                        onAdd = {
                            viewModel.subscribeByNaddr(pasteText)
                            pasteText = ""
                        },
                    )
                }

                // ═══════════ ZONE 2 — PINNED STRIP ═══════════
                item(key = "pinned-header") {
                    SectionHeader(
                        title = "Pinned",
                        modifier = Modifier.padding(top = Spacing.large),
                    )
                }
                item(key = "pinned-strip") {
                    PinnedStrip(
                        emojis = pinnedEmojis,
                        onTap = { showPicker = true },
                    )
                }

                // ═══════════ ZONE 3 — YOUR SETS ═══════════
                item(key = "sets-header") {
                    SectionHeader(
                        title = "Your sets",
                        count = subscribedSets.size.takeIf { it > 0 },
                        modifier = Modifier.padding(top = Spacing.large),
                    )
                }
                if (subscribedSets.isEmpty()) {
                    item(key = "sets-empty") {
                        Text(
                            text = "No sets yet. Paste a link above or browse below to add one.",
                            color = TextSecondary,
                            fontSize = AppType.bodySmall,
                            modifier = Modifier.padding(
                                horizontal = Spacing.medium,
                                vertical = Spacing.small,
                            ),
                        )
                    }
                } else {
                    items(
                        items = subscribedSets,
                        key = { "${it.authorPubkey}:${it.dTag}" },
                    ) { row ->
                        SubscribedSetCard(
                            row = row,
                            onRemove = { viewModel.unsubscribeSet(row.authorPubkey, row.dTag) },
                            onRetry = { viewModel.retryUnresolved(row.authorPubkey, row.dTag) },
                        )
                    }
                }

                // ═══════════ ZONE 4 — DISCOVER ═══════════
                if (discoverSets.isNotEmpty()) {
                    item(key = "discover-header") {
                        SectionHeader(
                            title = "Discover",
                            modifier = Modifier.padding(top = Spacing.large),
                        )
                    }
                    items(
                        items = discoverSets,
                        key = { "discover:${it.authorPubkey}:${it.dTag}" },
                    ) { row ->
                        AnimatedVisibility(
                            visible = true,
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            DiscoverSetCard(
                                row = row,
                                onAdd = { viewModel.subscribeDiscoverSet(row.authorPubkey, row.dTag) },
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Emoji picker (for pinned strip) ─────────────────────────────────
    if (showPicker) {
        val pickerEmojis     by viewModel.resolvedEmojis.collectAsStateWithLifecycle()
        val pickerCategories by viewModel.emojiCategories.collectAsStateWithLifecycle()
        val pinnedShortcodes by viewModel.pinnedShortcodes.collectAsStateWithLifecycle()

        EmojiPickerSheet(
            emojis = pickerEmojis,
            pinnedShortcodes = pinnedShortcodes,
            onSelect = { /* no-op — picker in settings is for pin management only */ },
            onTogglePin = viewModel::toggleEmojiPin,
            onOpenSettings = { /* already on settings screen */ },
            onDismiss = { showPicker = false },
            categories = pickerCategories,
        )
    }
}

// ── ZONE 1: Paste bar ───────────────────────────────────────────────────────

@Composable
private fun PasteBar(
    text: String,
    onTextChange: (String) -> Unit,
    pasteState: PasteState,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.medium),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = {
                    Text(
                        "Paste a set link (naddr1…)",
                        color = Text3,
                        fontSize = AppType.body,
                    )
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = BrandDeep,
                    focusedBorderColor = BrandDeep,
                    unfocusedBorderColor = Text3.copy(alpha = 0.3f),
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = AppType.body),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (text.isNotBlank()) onAdd() },
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.small))
            Button(
                onClick = onAdd,
                enabled = text.isNotBlank() && pasteState !is PasteState.Resolving,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandDeep,
                    contentColor = Color.White,
                    disabledContainerColor = BrandDeep.copy(alpha = 0.38f),
                    disabledContentColor = Color.White.copy(alpha = 0.38f),
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = Spacing.medium, vertical = Spacing.small),
            ) {
                Text("Add", fontSize = AppType.body)
            }
        }

        // Status feedback
        when (pasteState) {
            is PasteState.Idle -> {}
            is PasteState.Resolving -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = Spacing.small),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = BrandDeep,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(Spacing.small))
                    Text("Subscribing…", color = TextSecondary, fontSize = AppType.bodySmall)
                }
            }
            is PasteState.Error -> {
                Text(
                    text = pasteState.message,
                    color = Color(0xFFCF6679),
                    fontSize = AppType.bodySmall,
                    modifier = Modifier.padding(top = Spacing.small),
                )
            }
            is PasteState.Subscribed -> {
                Text(
                    text = "Subscribed",
                    color = BrandDeep,
                    fontSize = AppType.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = Spacing.small),
                )
            }
        }
    }
}

// ── ZONE 2: Pinned strip ────────────────────────────────────────────────────

@Composable
private fun PinnedStrip(
    emojis: List<CustomEmoji>,
    onTap: () -> Unit,
) {
    if (emojis.isEmpty()) {
        Text(
            text = "Long-press emoji in the picker to pin them here",
            color = TextSecondary,
            fontSize = AppType.caption,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        )
    } else {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .padding(vertical = Spacing.small),
            contentPadding = PaddingValues(horizontal = Spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            items(emojis, key = { it.shortcode }) { emoji ->
                AsyncImage(
                    model = emoji.url,
                    contentDescription = emoji.shortcode,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

// ── ZONE 3: Subscribed set card ─────────────────────────────────────────────

@Composable
private fun SubscribedSetCard(
    row: SubscribedSetRow,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 2x2 emoji thumbnail
        EmojiGridThumbnail(emojis = row.emojis, unresolved = row.unresolved)

        Spacer(Modifier.width(Spacing.medium))

        // Title + author + meta
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title ?: row.dTag,
                color = Color.White,
                fontSize = AppType.body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                AuthorChip(profile = row.authorProfile, pubkey = row.authorPubkey)
                if (!row.unresolved) {
                    Text(
                        text = " · ${row.emojis.size} emojis",
                        color = Text3,
                        fontSize = AppType.caption,
                    )
                }
            }
            if (row.unresolved) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClick = onRetry),
                ) {
                    Text(
                        text = "couldn't reach · ",
                        color = Warn,
                        fontSize = AppType.caption,
                    )
                    Text(
                        text = "retry",
                        color = Warn,
                        fontSize = AppType.caption,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // Remove button
        TextButton(onClick = onRemove) {
            Text("Remove", color = TextSecondary, fontSize = AppType.bodySmall)
        }
    }
}

// ── ZONE 4: Discover set card ───────────────────────────────────────────────

@Composable
private fun DiscoverSetCard(
    row: DiscoverSetRow,
    onAdd: () -> Unit,
) {
    var added by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiGridThumbnail(emojis = row.emojis, unresolved = false)

        Spacer(Modifier.width(Spacing.medium))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                color = Color.White,
                fontSize = AppType.body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                AuthorChip(profile = row.authorProfile, pubkey = row.authorPubkey)
                Text(
                    text = " · ${row.emojis.size} emojis",
                    color = Text3,
                    fontSize = AppType.caption,
                )
            }
        }

        if (added) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Added",
                tint = Mint,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Button(
                onClick = {
                    added = true
                    onAdd()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandSoft,
                    contentColor = BrandDeep,
                ),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = Spacing.medium, vertical = Spacing.micro),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Add", fontSize = AppType.bodySmall)
            }
        }
    }
}

// ── Shared composables ──────────────────────────────────────────────────────

@Composable
private fun SectionHeader(
    title: String,
    count: Int? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = Spacing.medium)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = Color.White,
                fontSize = AppType.subheading,
                fontWeight = FontWeight.SemiBold,
            )
            if (count != null) {
                Spacer(Modifier.width(Spacing.small))
                Box(
                    modifier = Modifier
                        .background(Surface2, RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = count.toString(),
                        color = TextSecondary,
                        fontSize = AppType.caption,
                    )
                }
            }
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = Text3,
                fontSize = AppType.caption,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(Spacing.small))
    }
}

@Composable
private fun EmojiGridThumbnail(emojis: List<CustomEmoji>, unresolved: Boolean) {
    val tileSize = 18.dp
    val gap = 2.dp
    val placeholderAlphas = listOf(1.0f, 0.7f, 0.5f, 0.3f)

    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        for (rowIdx in 0..1) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (colIdx in 0..1) {
                    val idx = rowIdx * 2 + colIdx
                    val emoji = emojis.getOrNull(idx)
                    if (emoji != null && !unresolved) {
                        AsyncImage(
                            model = emoji.url,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(tileSize),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(tileSize)
                                .background(
                                    BrandDeep.copy(alpha = placeholderAlphas[idx]),
                                    RoundedCornerShape(3.dp),
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthorChip(profile: UserEntity?, pubkey: String) {
    val name = profile?.displayName?.takeIf { it.isNotBlank() }
        ?: profile?.name?.takeIf { it.isNotBlank() }
        ?: pubkey.take(8) + "…"

    Row(verticalAlignment = Alignment.CenterVertically) {
        val picUrl = profile?.picture
        if (picUrl != null) {
            AsyncImage(
                model = rememberAvatarImageRequest(picUrl, 14.dp),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape),
            )
        } else {
            IdentIcon(
                pubkey = pubkey,
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = name,
            color = Text3,
            fontSize = AppType.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
