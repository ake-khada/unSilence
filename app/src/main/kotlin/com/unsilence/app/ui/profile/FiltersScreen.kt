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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.SensitiveContentMode
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.ui.feed.AvatarImage
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.Mint
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap

private enum class MuteTab { USERS, WORDS, HASHTAGS }

@Composable
fun FiltersScreen(
    onDismiss: () -> Unit,
    viewModel: FiltersViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onDismiss)
    val muteList by viewModel.muteList.collectAsStateWithLifecycle()
    val sensitiveMode by viewModel.sensitiveContentMode.collectAsStateWithLifecycle()
    val publishSafe by viewModel.publishSafe.collectAsStateWithLifecycle()
    val profileVersion by viewModel.profileVersion.collectAsStateWithLifecycle()

    var activeTab by remember { mutableStateOf(MuteTab.USERS) }
    var searchQuery by remember { mutableStateOf("") }
    var addInput by remember { mutableStateOf("") }
    var showAdded by remember { mutableStateOf(false) }

    // Clear inputs on tab switch
    LaunchedEffect(activeTab) { searchQuery = ""; addInput = ""; showAdded = false }

    // Auto-clear checkmark after 1.2s
    LaunchedEffect(showAdded) {
        if (showAdded) { delay(1200); showAdded = false }
    }

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
                    .padding(horizontal = Spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Filters",
                    color = Color.White,
                    fontSize = AppType.subheading,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            LazyColumn(modifier = Modifier.fillMaxSize()) {

                // ── Sync status ──────────────────────────────────────
                item {
                    val total = muteList?.totalCount ?: 0
                    val privateCount = muteList?.let {
                        it.privatePubkeys.size + it.privateHashtags.size +
                            it.privateWords.size + it.privateEventIds.size
                    } ?: 0
                    Text(
                        text = "$total mutes" + if (privateCount > 0) " · $privateCount private" else "",
                        color = TextSecondary,
                        fontSize = AppType.caption,
                        modifier = Modifier.padding(
                            horizontal = Spacing.medium,
                            vertical = Spacing.medium,
                        ),
                    )
                }

                // ── Mute sync warning ────────────────────────────────
                if (!publishSafe) {
                    item {
                        MuteSyncBanner(
                            isAmberMode = viewModel.isAmberMode,
                            onRetry = { viewModel.retryAmberPermissions() },
                            modifier = Modifier.padding(
                                horizontal = Spacing.large,
                                vertical = Spacing.small,
                            ),
                        )
                    }
                }

                // ── Sensitive content toggle ─────────────────────────
                item {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    SectionLabel("SENSITIVE CONTENT")
                    SegmentedToggle(sensitiveMode) { viewModel.setSensitiveContentMode(it) }
                    Spacer(Modifier.height(Spacing.medium))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                }

                // ── Mute tabs ────────────────────────────────────────
                item {
                    Spacer(Modifier.height(Spacing.medium))
                    MuteTabRow(muteList, activeTab) { activeTab = it }
                    Spacer(Modifier.height(Spacing.small))
                }

                // ── Search / Add bar ─────────────────────────────────
                item {
                    when (activeTab) {
                        MuteTab.USERS -> SearchBar(searchQuery) { searchQuery = it }
                        MuteTab.WORDS -> AddBar(
                            value = addInput,
                            onValueChange = { addInput = it },
                            onAdd = {
                                val word = addInput.trim()
                                if (word.isNotEmpty()) {
                                    viewModel.muteWord(word)
                                    addInput = ""
                                    showAdded = true
                                }
                            },
                            placeholder = "Add a word",
                            showAdded = showAdded,
                        )
                        MuteTab.HASHTAGS -> AddBar(
                            value = addInput,
                            onValueChange = { addInput = it.removePrefix("#") },
                            onAdd = {
                                val tag = addInput.trim().removePrefix("#")
                                if (tag.isNotEmpty()) {
                                    viewModel.muteHashtag(tag)
                                    addInput = ""
                                    showAdded = true
                                }
                            },
                            placeholder = "Add a hashtag",
                            showAdded = showAdded,
                        )
                    }
                    Spacer(Modifier.height(Spacing.small))
                }

                // ── Tab content ──────────────────────────────────────
                when (activeTab) {
                    MuteTab.USERS -> {
                        // Newest first: mute entries are appended in chronological order
                        // (LinkedHashSet — see MuteList contract), so reverse = most-recent
                        // mute on top. NEVER sort by name/alpha — insertion order is the feature.
                        val allPubkeys = muteList?.let {
                            (it.pubkeys + it.privatePubkeys).toList().reversed()
                        } ?: emptyList()
                        val filtered = if (searchQuery.isBlank()) allPubkeys
                        else allPubkeys.filter { pk ->
                            val profile = viewModel.getProfile(pk)
                            val name = profile?.displayName ?: profile?.name ?: pk
                            name.contains(searchQuery, ignoreCase = true) ||
                                pk.startsWith(searchQuery, ignoreCase = true)
                        }

                        if (filtered.isEmpty()) {
                            item { EmptyLabel(if (searchQuery.isBlank()) "No muted users" else "No matches") }
                        } else {
                            items(filtered, key = { it }) { pubkey ->
                                val profile = remember(pubkey, profileVersion) { viewModel.getProfile(pubkey) }
                                MutedUserRow(
                                    pubkey = pubkey,
                                    profile = profile,
                                    onRemove = { viewModel.unmuteUser(pubkey) },
                                )
                            }
                        }
                    }
                    MuteTab.WORDS -> {
                        val allWords = muteList?.let {
                            (it.words + it.privateWords).toList().reversed()  // newest first, never alpha
                        } ?: emptyList()

                        if (allWords.isEmpty()) {
                            item { EmptyLabel("No muted words") }
                        } else {
                            items(allWords, key = { "w:$it" }) { word ->
                                MutedTagRow(
                                    text = word,
                                    onRemove = { viewModel.unmuteWord(word) },
                                )
                            }
                        }
                    }
                    MuteTab.HASHTAGS -> {
                        val allHashtags = muteList?.let {
                            (it.hashtags + it.privateHashtags).toList().reversed()  // newest first, never alpha
                        } ?: emptyList()

                        if (allHashtags.isEmpty()) {
                            item { EmptyLabel("No muted hashtags") }
                        } else {
                            items(allHashtags, key = { "t:$it" }) { tag ->
                                MutedTagRow(
                                    text = "#$tag",
                                    onRemove = { viewModel.unmuteHashtag(tag) },
                                )
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(Spacing.xxl)) }
            }
        }
    }
}

// ── Section label ────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title,
        color = TextSecondary,
        fontSize = AppType.caption,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = AppType.caption * 0.08f,
        modifier = Modifier.padding(horizontal = Spacing.large, vertical = Spacing.small),
    )
}

// ── Segmented toggle for sensitive content mode ─────────────────────────

@Composable
private fun SegmentedToggle(
    current: SensitiveContentMode,
    onSelect: (SensitiveContentMode) -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.large)
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.15f), shape),
    ) {
        SensitiveContentMode.entries.forEach { mode ->
            val selected = mode == current
            val label = when (mode) {
                SensitiveContentMode.HIDE -> "Hide"
                SensitiveContentMode.BLUR -> "Blur"
                SensitiveContentMode.SHOW -> "Show"
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (selected) Brand.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(vertical = Spacing.small),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (selected) Brand else TextSecondary,
                    fontSize = AppType.body,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

// ── Mute tab row ────────────────────────────────────────────────────────

@Composable
private fun MuteTabRow(muteList: MuteList?, active: MuteTab, onSelect: (MuteTab) -> Unit) {
    val userCount = muteList?.let { it.pubkeys.size + it.privatePubkeys.size } ?: 0
    val wordCount = muteList?.let { it.words.size + it.privateWords.size } ?: 0
    val hashtagCount = muteList?.let { it.hashtags.size + it.privateHashtags.size } ?: 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.large),
        horizontalArrangement = Arrangement.spacedBy(Spacing.large),
    ) {
        TabLabel("Users", userCount, active == MuteTab.USERS) { onSelect(MuteTab.USERS) }
        TabLabel("Words", wordCount, active == MuteTab.WORDS) { onSelect(MuteTab.WORDS) }
        TabLabel("Hashtags", hashtagCount, active == MuteTab.HASHTAGS) { onSelect(MuteTab.HASHTAGS) }
    }
}

@Composable
private fun TabLabel(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "$label $count",
            color = if (selected) Brand else TextSecondary,
            fontSize = AppType.body,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (selected) {
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .height(2.dp)
                    .width(32.dp)
                    .background(Brand, RoundedCornerShape(1.dp)),
            )
        }
    }
}

// ── Search bar ──────────────────────────────────────────────────────────

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.large)
            .clip(RoundedCornerShape(8.dp))
            .background(Surface2)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Spacing.small))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            textStyle = TextStyle(color = Color.White, fontSize = AppType.body),
            cursorBrush = SolidColor(Brand),
            singleLine = true,
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text("Search", color = TextSecondary, fontSize = AppType.body)
                }
                inner()
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextSecondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ── Muted user row ──────────────────────────────────────────────────────

@Composable
private fun MutedUserRow(
    pubkey: String,
    profile: UserEntity?,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.large, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarImage(
            pubkey = pubkey,
            picture = profile?.picture,
            modifier = Modifier.size(36.dp),
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
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Unmute",
                tint = TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
    HorizontalDivider(
        color = Color.White.copy(alpha = 0.06f),
        modifier = Modifier.padding(start = Spacing.large + 36.dp + Spacing.medium),
    )
}

// ── Add bar (Words / Hashtags tabs) ─────────────────────────────────────

@Composable
private fun AddBar(
    value: String,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
    placeholder: String,
    showAdded: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.large)
            .clip(RoundedCornerShape(8.dp))
            .background(Surface2)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Spacing.small))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = Color.White, fontSize = AppType.body),
            cursorBrush = SolidColor(Brand),
            singleLine = true,
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, color = TextSecondary, fontSize = AppType.body)
                }
                inner()
            },
        )
        if (showAdded) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Added",
                tint = Mint,
                modifier = Modifier.size(18.dp),
            )
        } else if (value.isNotBlank()) {
            Text(
                text = "Add",
                color = Brand,
                fontSize = AppType.bodySmall,
                modifier = Modifier.clickable(onClick = onAdd),
            )
        }
    }
}

// ── Muted tag/word row ──────────────────────────────────────────────────

@Composable
private fun MutedTagRow(text: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.large, vertical = Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = AppType.body,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove",
                tint = TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
    HorizontalDivider(
        color = Color.White.copy(alpha = 0.06f),
        modifier = Modifier.padding(start = Spacing.large),
    )
}

// ── Empty label ─────────────────────────────────────────────────────────

@Composable
private fun EmptyLabel(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = AppType.body,
        modifier = Modifier.padding(horizontal = Spacing.large, vertical = Spacing.large),
    )
}

// ── Mute sync warning banner ────────────────────────────────────────────

@Composable
private fun MuteSyncBanner(
    isAmberMode: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Spacing.small))
            .background(Zap.copy(alpha = 0.12f))
            .padding(Spacing.medium),
    ) {
        Text(
            text = if (isAmberMode) {
                "Mute sync disabled \u2014 Amber denied permission to encrypt your mute list. " +
                    "Mutes work locally but won\u2019t sync."
            } else {
                "Mute sync disabled \u2014 encryption check failed. " +
                    "Mutes work locally but won\u2019t sync to relays or other clients."
            },
            color = Zap,
            fontSize = AppType.caption,
        )
        if (isAmberMode) {
            Spacer(Modifier.height(Spacing.small))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(Spacing.small),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Zap,
                    contentColor = Black,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Retry permission request",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = AppType.body,
                )
            }
        }
    }
}
