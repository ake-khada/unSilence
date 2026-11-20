package com.unsilence.app.ui.drafts

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateInt
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import coil3.compose.AsyncImage
import com.unsilence.app.data.drafts.Draft
import com.unsilence.app.data.drafts.DraftBlock
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.network.parseAllowedUntrustedHttpUrl
import com.unsilence.app.ui.common.rememberWidthImageRequest
import com.unsilence.app.ui.shared.NostrAddressDisplay
import com.unsilence.app.ui.shared.selfDeclaredNostrAddressPresentation
import com.unsilence.app.ui.feed.AuthorNameRow
import com.unsilence.app.ui.feed.AvatarImage
import com.unsilence.app.ui.feed.collectProfileAsState
import com.unsilence.app.ui.shared.CardDataFlow
import com.unsilence.app.ui.shared.FeedDivider
import com.unsilence.app.ui.shared.LocalMediaLoadPermissions
import com.unsilence.app.ui.shared.rememberMediaAdmission
import com.unsilence.app.ui.theme.AppTextStyles
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BrandDeep
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.thread.threadReplyIndent

private const val DraftMotionMillis = 220
private const val KeyboardLeadMillis = 32

internal val LocalDraftTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }
internal val LocalDraftEntry = staticCompositionLocalOf { "" }

@Composable
internal fun Modifier.draftBounds(origin: String?): Modifier {
    val scope = LocalDraftTransition.current ?: return this
    if (origin == null || rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor == 0f) return this
    val visibility = LocalNavAnimatedContentScope.current
    return with(scope) {
        sharedBounds(
            rememberSharedContentState(origin),
            visibility,
            boundsTransform = { _, _ -> tween(DraftMotionMillis) },
            // Keep the first line at the top as the card grows into a much taller viewport.
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.TopStart),
        )
    }
}

/** Let the field request focus near the end of entry. Latch it so cancelling Back cannot refocus the editor. */
@Composable
internal fun rememberDraftReadyForFocus(origin: String?): Boolean {
    if (origin == null || LocalDraftTransition.current == null) return true
    val entry = LocalNavAnimatedContentScope.current.transition
    var opened by remember(origin, entry) { mutableStateOf(false) }
    if (opened) return true
    val cue by entry.animateInt(
        transitionSpec = {
            snap(delayMillis = if (targetState == EnterExitState.Visible) DraftMotionMillis - KeyboardLeadMillis else 0)
        },
        label = "Draft keyboard",
    ) { if (it == EnterExitState.Visible) 1 else 0 }
    if (cue == 1) SideEffect { opened = true }
    return cue == 1
}

@Composable
internal fun DraftAuthorHeader(
    pubkey: String?,
    profile: UserEntity?,
    showDraftMark: Boolean,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (pubkey != null) AvatarImage(pubkey, profile?.picture, Modifier.size(Sizing.avatar))
        Spacer(Modifier.width(Spacing.small))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            AuthorNameRow(
                pubkey = pubkey.orEmpty(),
                authorLabel = profile?.displayName?.takeIf { it.isNotBlank() }
                    ?: profile?.name?.takeIf { it.isNotBlank() }
                    ?: pubkey?.let { "${it.take(6)}…${it.takeLast(4)}" } ?: "You",
                nostrAddress = remember(pubkey, profile?.nip05) {
                    pubkey?.let { selfDeclaredNostrAddressPresentation(profile?.nip05, NostrAddressDisplay.DOMAIN) }
                },
            )
        }
        if (showDraftMark) Text("Draft", style = AppTextStyles.footnote, color = BrandDeep,
            fontWeight = FontWeight.Medium)
        actions()
    }
}

@Composable
internal fun InlineDraftCard(
    draft: Draft,
    pubkey: String?,
    profile: UserEntity?,
    place: String,
    onResume: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    onHide: (() -> Unit)? = null,
    onShowDrafts: (() -> Unit)? = null,
) {
    val origin = "${LocalDraftEntry.current}/$place/${draft.key}"
    Column(modifier.fillMaxWidth().draftBounds(origin).clipToBounds().background(Black)
        .clickable(onClickLabel = "Resume draft") { onResume(draft.key, origin) }
        .padding(horizontal = Spacing.medium, vertical = Spacing.small)) {
        DraftAuthorHeader(pubkey, profile, showDraftMark = true) {
            if (onHide != null) IconButton(onClick = onHide) {
                Icon(Icons.Filled.Close, "Hide from feed", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
        if (draft.isSensitive) {
            Text("Sensitive content", style = AppTextStyles.body, color = TextSecondary)
        } else {
            if (draft.previewText.isNotEmpty()) Text(draft.previewText, style = AppTextStyles.bodyLarge,
                color = Color.White, maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.small))
            val poster = remember(draft.blocks) {
                draft.blocks.filterIsInstance<DraftBlock.Attachment>().firstNotNullOfOrNull { attachment ->
                    val url = attachment.thumbnailUrl ?: attachment.url.takeIf { attachment.mimeType.startsWith("image/") }
                    parseAllowedUntrustedHttpUrl(url)?.toString()
                }
            }
            if (poster != null && rememberMediaAdmission(poster, LocalMediaLoadPermissions.current.images)) {
                AsyncImage(
                    model = rememberWidthImageRequest(poster, 96.dp, aspectRatio = 1f),
                    contentDescription = "Draft attachment",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(top = Spacing.small).size(96.dp),
                )
            }
            val details = listOfNotNull(
                draft.poll?.let { "Poll" },
                draftDetail(draft),
            ).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, style = AppTextStyles.footnote, color = TextSecondary)
        }
        if (onShowDrafts != null) TextButton(onClick = onShowDrafts) {
            Text("All drafts", color = BrandDeep)
        }
    }
    FeedDivider()
}

@Composable
internal fun InlineReplyDrafts(
    drafts: List<Draft>?,
    pubkey: String?,
    profileFlow: (String) -> CardDataFlow<UserEntity?>,
    place: String,
    depth: Int,
    onResume: (String, String) -> Unit,
) {
    if (drafts.isNullOrEmpty()) return
    val profile = pubkey?.let { collectProfileAsState(it, profileFlow) }
    for (draft in drafts) key(draft.key) {
        InlineDraftCard(draft, pubkey, profile, place, onResume, Modifier.threadReplyIndent(depth))
    }
}
