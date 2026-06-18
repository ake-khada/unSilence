package com.unsilence.app.ui.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.unsilence.app.data.memory.CustomEmoji
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.BrandDeep
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.TextSecondary

/**
 * Full emoji picker sheet — ModalBottomSheet with search, grid, and pin support.
 * When [categories] is provided, emojis are grouped under set-name headers.
 * Pinned emojis float to the top as their own section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmojiPickerSheet(
    emojis: List<CustomEmoji>,
    pinnedShortcodes: Set<String>,
    onSelect: (CustomEmoji) -> Unit,
    onTogglePin: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    categories: List<Pair<String, List<CustomEmoji>>> = emptyList(),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.medium)) {
            // Header: title + settings overflow
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Custom Emoji",
                    color = Color.White,
                    fontSize = AppType.subheading,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    onDismiss()
                    onOpenSettings()
                }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Settings",
                        tint = TextSecondary,
                    )
                }
            }

            if (emojis.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "No custom emoji yet",
                        color = TextSecondary,
                        fontSize = AppType.body,
                    )
                    Spacer(Modifier.height(Spacing.small))
                    Box(
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .clickable {
                                onDismiss()
                                onOpenSettings()
                            }
                            .padding(horizontal = Spacing.medium),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Manage in Settings",
                            color = BrandDeep,
                            fontSize = AppType.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            } else {
                // Search
                var query by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search emoji\u2026", color = TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandDeep,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                        cursorColor = BrandDeep,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    ),
                )
                Spacer(Modifier.height(Spacing.small))

                if (categories.isNotEmpty() && query.isBlank()) {
                    // Grouped rendering — pinned section first, then categories
                    val pinnedEmojis = remember(emojis, pinnedShortcodes) {
                        if (pinnedShortcodes.isEmpty()) emptyList()
                        else emojis.filter { it.shortcode in pinnedShortcodes }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier.heightIn(max = 400.dp),
                        contentPadding = PaddingValues(bottom = Spacing.medium),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        // Pinned section
                        if (pinnedEmojis.isNotEmpty()) {
                            item(
                                key = "header:pinned",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                CategoryHeader("Pinned")
                            }
                            items(pinnedEmojis.size, key = { "p:$it" }) { i ->
                                val emoji = pinnedEmojis[i]
                                EmojiGridCell(
                                    emoji = emoji,
                                    isPinned = true,
                                    onTap = { onSelect(emoji) },
                                    onLongPress = { onTogglePin(emoji.shortcode) },
                                )
                            }
                        }
                        // Category sections
                        categories.forEachIndexed { idx, (title, catEmojis) ->
                            item(
                                key = "header:$idx",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                CategoryHeader(title)
                            }
                            items(catEmojis.size, key = { "$idx:$it" }) { i ->
                                val emoji = catEmojis[i]
                                EmojiGridCell(
                                    emoji = emoji,
                                    isPinned = emoji.shortcode in pinnedShortcodes,
                                    onTap = { onSelect(emoji) },
                                    onLongPress = { onTogglePin(emoji.shortcode) },
                                )
                            }
                        }
                    }
                } else {
                    // Flat rendering — search active or no categories
                    val filtered = remember(emojis, query, pinnedShortcodes) {
                        val sorted = emojis.sortedWith(
                            compareByDescending<CustomEmoji> { it.shortcode in pinnedShortcodes }
                                .thenBy { it.shortcode },
                        )
                        if (query.isBlank()) sorted
                        else sorted.filter { it.shortcode.contains(query.trim(), ignoreCase = true) }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier.heightIn(max = 400.dp),
                        contentPadding = PaddingValues(bottom = Spacing.medium),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        items(filtered, key = { it.shortcode }) { emoji ->
                            EmojiGridCell(
                                emoji = emoji,
                                isPinned = emoji.shortcode in pinnedShortcodes,
                                onTap = { onSelect(emoji) },
                                onLongPress = { onTogglePin(emoji.shortcode) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(title: String) {
    Text(
        text = title,
        color = Text3,
        fontSize = AppType.caption,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = Spacing.small, bottom = 2.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmojiGridCell(
    emoji: CustomEmoji,
    isPinned: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
            )
            .padding(vertical = Spacing.micro),
    ) {
        Box(contentAlignment = Alignment.Center) {
            AsyncImage(
                model = emoji.url,
                contentDescription = emoji.shortcode,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            text = if (isPinned) "\u2605 ${emoji.shortcode}" else emoji.shortcode,
            color = if (isPinned) BrandDeep else TextSecondary,
            fontSize = AppType.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
