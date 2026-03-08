package com.unsilence.app.ui.profile

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.feed.NoteCard
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary

private val BANNER_HEIGHT       = 150.dp
// 85dp — φ⁴ position on the golden ratio scale, same as bar heights
private val PROFILE_AVATAR_SIZE = 85.dp

@Composable
fun ProfileScreen(viewModel: ProfileViewModel = hiltViewModel()) {
    val user   by viewModel.userFlow.collectAsStateWithLifecycle(initialValue = null)
    val posts  by viewModel.postsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val clipboard = LocalClipboardManager.current

    val displayName = user?.displayName?.takeIf { it.isNotBlank() }
        ?: user?.name?.takeIf { it.isNotBlank() }
        ?: viewModel.pubkeyHex?.take(8)?.let { "$it…" }

    val npubShort = viewModel.npub?.let {
        "${it.take(8)}…${it.takeLast(8)}"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        LazyColumn(
            modifier            = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            // ── Header ──────────────────────────────────────────────────────
            item {
                // Banner + avatar overlap composite.
                // Total height = banner (150dp) + half avatar (42.5dp) so the avatar
                // straddles the banner's bottom edge with equal overlap on each side.
                Box(
                    modifier            = Modifier
                        .fillMaxWidth()
                        .height(BANNER_HEIGHT + PROFILE_AVATAR_SIZE / 2),
                    contentAlignment    = Alignment.BottomCenter,
                ) {
                    // Banner — fills the top 150dp of the Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(BANNER_HEIGHT)
                            .align(Alignment.TopStart),
                    ) {
                        val bannerUrl = user?.banner
                        if (!bannerUrl.isNullOrBlank()) {
                            AsyncImage(
                                model              = bannerUrl,
                                contentDescription = null,
                                contentScale       = ContentScale.Crop,
                                modifier           = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF1A1A1A)),
                            )
                        }
                    }

                    // Avatar — centered at the banner's bottom edge
                    ProfileAvatar(
                        pubkeyHex  = viewModel.pubkeyHex,
                        pictureUrl = user?.picture,
                        modifier   = Modifier
                            .size(PROFILE_AVATAR_SIZE)
                            .clip(CircleShape)
                            // 2dp Black ring so the avatar reads against both
                            // the banner image and the content below
                            .border(2.dp, Black, CircleShape),
                    )
                }

                Spacer(Modifier.height(Spacing.medium))

                // Display name
                if (displayName != null) {
                    Text(
                        text       = displayName,
                        color      = Color.White,
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center,
                        modifier   = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.medium),
                    )
                    Spacer(Modifier.height(Spacing.small))
                }

                // npub — tappable to copy full npub
                if (npubShort != null) {
                    Text(
                        text      = npubShort,
                        color     = TextSecondary,
                        fontSize  = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.medium)
                            .clickable {
                                viewModel.npub?.let { full ->
                                    clipboard.setText(AnnotatedString(full))
                                }
                            },
                    )
                    Spacer(Modifier.height(Spacing.small))
                }

                // Bio / about
                val about = user?.about?.takeIf { it.isNotBlank() }
                if (about != null) {
                    Text(
                        text      = about,
                        color     = Color.White,
                        fontSize  = 14.sp,
                        textAlign = TextAlign.Center,
                        maxLines  = 3,
                        overflow  = TextOverflow.Ellipsis,
                        modifier  = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.medium),
                    )
                    Spacer(Modifier.height(Spacing.small))
                }

                // Following / Followers stats row
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    StatLabel(label = "Following", value = viewModel.following)
                    Spacer(Modifier.size(20.dp))
                    StatLabel(label = "Followers", value = viewModel.followers)
                }

                Spacer(Modifier.height(Spacing.large))
            }

            // ── Posts section header ─────────────────────────────────────────
            item {
                Text(
                    text       = "Posts",
                    color      = Color.White,
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium)
                        .padding(bottom = Spacing.small),
                )
            }

            // ── Post list ────────────────────────────────────────────────────
            if (posts.isEmpty()) {
                item {
                    Text(
                        text     = "No posts yet",
                        color    = TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.medium),
                    )
                }
            } else {
                items(
                    items = posts,
                    key   = { it.id },
                ) { row ->
                    NoteCard(row = row)
                }
            }

            item { Spacer(Modifier.height(Spacing.xl)) }
        }
    }
}

// ── Private sub-composables ───────────────────────────────────────────────────

/**
 * Circular avatar: IdentIcon as background fallback,
 * AsyncImage overlay when a picture URL is available — same pattern as NoteCard.
 */
@Composable
private fun ProfileAvatar(
    pubkeyHex: String?,
    pictureUrl: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (pubkeyHex != null) {
            IdentIcon(
                pubkey   = pubkeyHex,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF333333)))
        }
        if (!pictureUrl.isNullOrBlank()) {
            AsyncImage(
                model              = pictureUrl,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun StatLabel(label: String, value: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text      = if (value != null) formatStatCount(value) else "—",
            color     = Color.White,
            fontSize  = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text     = label,
            color    = TextSecondary,
            fontSize = 13.sp,
        )
    }
}

private fun formatStatCount(n: Int): String = when {
    n < 1_000  -> "$n"
    n < 10_000 -> "%.1fk".format(n / 1_000f)
    else        -> "${n / 1_000}k"
}
