package com.unsilence.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.unsilence.app.data.db.entity.UserEntity
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.feed.NoteActionsViewModel
import com.unsilence.app.ui.feed.NoteCard
import com.unsilence.app.ui.feed.engagementId
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Cyan
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

private val TAB_LABELS = listOf("People", "Notes")

@Composable
fun SearchScreen(
    onNoteClick: (String) -> Unit = {},
    onAuthorClick: (pubkey: String) -> Unit = {},
    onQuote: (String) -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
    actionsViewModel: NoteActionsViewModel = hiltViewModel(),
) {
    val state           by viewModel.uiState.collectAsStateWithLifecycle()
    val reactedIds      by actionsViewModel.reactedEventIds.collectAsStateWithLifecycle()
    val repostedIds     by actionsViewModel.repostedEventIds.collectAsStateWithLifecycle()
    val zappedIds       by actionsViewModel.zappedEventIds.collectAsStateWithLifecycle()
    val isNwcConfigured = actionsViewModel.isNwcConfigured

    var selectedTab by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        // ── Search bar ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.medium, vertical = Spacing.small)
                .border(1.dp, TextSecondary, RoundedCornerShape(8.dp))
                .padding(horizontal = Spacing.small, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector        = Icons.Filled.Search,
                contentDescription = null,
                tint               = TextSecondary,
                modifier           = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.small))
            BasicTextField(
                value         = state.query,
                onValueChange = { viewModel.search(it) },
                textStyle     = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush   = SolidColor(Cyan),
                singleLine    = true,
                modifier      = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (state.query.isEmpty()) {
                        Text(
                            text     = "Search notes and people…",
                            color    = TextSecondary,
                            fontSize = 15.sp,
                        )
                    }
                    inner()
                },
            )
        }

        // ── Tab row ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.medium),
        ) {
            TAB_LABELS.forEachIndexed { index, label ->
                TextButton(onClick = { selectedTab = index }) {
                    Text(
                        text       = label,
                        color      = if (selectedTab == index) Cyan else TextSecondary,
                        fontSize   = 14.sp,
                        fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)

        // ── Results ───────────────────────────────────────────────────────────
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                !state.hasSearched -> {
                    Text(
                        text     = "Search for notes and people",
                        color    = TextSecondary,
                        fontSize = 15.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                state.loading && state.noteResults.isEmpty() && state.peopleResults.isEmpty() -> {
                    CircularProgressIndicator(
                        color    = Cyan,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                selectedTab == 0 -> {
                    if (state.peopleResults.isEmpty()) {
                        Text(
                            text     = "No people found for \"${state.query}\"",
                            color    = TextSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.peopleResults, key = { it.pubkey }) { user ->
                                ProfileCard(user = user, onClick = { onAuthorClick(user.pubkey) })
                                HorizontalDivider(
                                    color     = MaterialTheme.colorScheme.surfaceVariant,
                                    thickness = 0.5.dp,
                                )
                            }
                        }
                    }
                }

                else -> {  // Notes tab
                    if (state.noteResults.isEmpty()) {
                        Text(
                            text     = "No notes found for \"${state.query}\"",
                            color    = TextSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.noteResults, key = { it.id }) { row ->
                                NoteCard(
                                    row             = row,
                                    onNoteClick     = onNoteClick,
                                    onAuthorClick   = onAuthorClick,
                                    hasReacted      = row.engagementId in reactedIds,
                                    hasReposted     = row.engagementId in repostedIds,
                                    hasZapped       = row.engagementId in zappedIds,
                                    isNwcConfigured = isNwcConfigured,
                                    onReact         = { actionsViewModel.react(row.id, row.pubkey) },
                                    onRepost        = { actionsViewModel.repost(row.id, row.pubkey, row.relayUrl) },
                                    onQuote         = onQuote,
                                    onZap           = { amt -> actionsViewModel.zap(row.id, row.pubkey, row.relayUrl, amt) },
                                    onSaveNwcUri    = { uri -> actionsViewModel.saveNwcUri(uri) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Profile card ──────────────────────────────────────────────────────────────

@Composable
private fun ProfileCard(user: UserEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(Sizing.avatar + 8.dp)
                .clip(CircleShape),
        ) {
            IdentIcon(pubkey = user.pubkey, modifier = Modifier.fillMaxSize())
            if (!user.picture.isNullOrBlank()) {
                AsyncImage(
                    model              = user.picture,
                    contentDescription = null,
                    modifier           = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(Modifier.width(Spacing.small))

        Column(modifier = Modifier.weight(1f)) {
            val displayName = user.displayName?.takeIf { it.isNotBlank() }
                ?: user.name?.takeIf { it.isNotBlank() }
            if (displayName != null) {
                Text(
                    text       = displayName,
                    color      = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 14.sp,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
            }
            Text(
                text     = "${user.pubkey.take(6)}…${user.pubkey.takeLast(4)}",
                color    = TextSecondary,
                fontSize = 12.sp,
            )
            if (!user.about.isNullOrBlank()) {
                Text(
                    text     = user.about,
                    color    = TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
