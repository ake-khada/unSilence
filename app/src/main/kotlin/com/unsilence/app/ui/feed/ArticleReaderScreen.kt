package com.unsilence.app.ui.feed

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import com.unsilence.app.ui.common.rememberFullWidthImageRequest
import com.unsilence.app.ui.markdown.MarkdownContent
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.toEventModel
import com.unsilence.app.data.model.markdown.NativeMarkdownParser
import com.unsilence.app.data.wallet.ZapRequest
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.SurfaceVariant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleReaderScreen(
    row: FeedRow,
    onDismiss: () -> Unit,
    onNoteClick: (String) -> Unit = {},
    onReact: () -> Unit = {},
    onReactLongPress: () -> Unit = {},
    pinnedEmojis: List<com.unsilence.app.data.memory.CustomEmoji> = emptyList(),
    onReactWithEmoji: (com.unsilence.app.data.memory.CustomEmoji) -> Unit = {},
    onRepost: () -> Unit = {},
    onQuote: (String) -> Unit = {},
    onZap: (request: ZapRequest) -> Unit = {},
    onSaveNwcUri: (String) -> Unit = {},
    hasReacted: Boolean = false,
    hasReposted: Boolean = false,
    hasZapped: Boolean = false,
    isNwcConfigured: Boolean = false,
    isZapLoading: Boolean = false,
    extraZapSats: Long = 0L,
    zapFlash: NoteActionsViewModel.ZapFlashState? = null,
    // Live chrome (mirrors EventCard): null → static row.* counts + no drawer (SearchScreen).
    onAuthorClick: (String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {},
    lookupProfile: (suspend (String) -> com.unsilence.app.data.memory.UserEntity?)? = null,
    profileFlow: ((String) -> StateFlow<com.unsilence.app.data.memory.UserEntity?>)? = null,
    statsFlow: ((String) -> StateFlow<com.unsilence.app.data.memory.EventStats>)? = null,
    zapDetailsForEvent: ((String) -> List<com.unsilence.app.data.memory.ZapDetail>)? = null,
    repostPubkeysForEvent: ((String) -> List<String>)? = null,
    reactionsForEvent: ((String) -> List<com.unsilence.app.data.memory.ReactionInfo>)? = null,
) {
    // Unwrap reposts: for a kind-6/16 wrapper the FeedRow's tags/content belong to
    // the wrapper (embedded JSON string + wrapper tags). Parse to the EventModel so
    // title/image come from the INNER article tags and the body is the inner
    // markdown — not the raw embedded-JSON dump. For non-reposts these resolve to
    // the same values as the raw row.
    val model = remember(row.id, row.content, row.tags) { row.toEventModel() }
    val title = model.article?.title ?: articleTagValue(row.tags, "title")
    val image = model.article?.image ?: articleTagValue(row.tags, "image")
    val context = LocalContext.current

    // Native markdown body (replaces the WebView). Parsed once per article on a
    // background-free pure-Kotlin pass; bounded by ParseLimits (H-spam discipline).
    val document = remember(model.articleContent) {
        NativeMarkdownParser.parse(model.articleContent ?: "")
    }
    // Draw-bound probe at the parse→render boundary (mirrors the note path's
    // PARSE-HEAVY signal). Log.w survives R8 in release.
    LaunchedEffect(document.truncated, model.navigateId) {
        if (document.truncated) {
            Log.w("ARTICLE-PARSE-HEAVY", "id=${model.navigateId} blocks=${document.blocks.size} (capped)")
        }
    }

    // Effective author (inner author on a reposted article — never the reposter),
    // mirroring EventCard.ArticleLayout's resolution.
    val isRepost = model.repost != null
    val authorProfile = profileFlow?.invoke(model.pubkey)?.collectAsStateWithLifecycle()?.value
    val authorLabel = authorProfile?.displayName?.takeIf { it.isNotBlank() }
        ?: authorProfile?.name?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }
        ?: (if (isRepost) null else row.displayName)
        ?: "${model.pubkey.take(6)}…${model.pubkey.takeLast(4)}"

    // Live engagement counts keyed off the effective id; fall back to the static
    // FeedRow snapshot when no statsFlow is wired (e.g. SearchScreen).
    val liveStats = statsFlow?.invoke(model.engagementId)?.collectAsStateWithLifecycle()?.value
    val replyCount    = liveStats?.replyCount    ?: row.replyCount
    val repostCount   = liveStats?.repostCount   ?: row.repostCount
    val reactionCount = liveStats?.reactionCount ?: row.reactionCount
    val zapTotalSats  = liveStats?.zapTotalSats  ?: row.zapTotalSats

    var drawerOpen by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Black),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Scrollable content ─────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // ── Top bar ────────────────────────────────────────────────
                    Row(
                        modifier          = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector        = Icons.Filled.Close,
                                contentDescription = "Close",
                                tint               = Color.White,
                                modifier           = Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // Share lives here — EventActionBar (below) has no Share slot.
                        IconButton(onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_TEXT, "https://njump.me/${model.navigateId}")
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, null))
                        }) {
                            Icon(
                                imageVector        = Icons.Filled.Share,
                                contentDescription = "Share",
                                tint               = Color.White,
                                modifier           = Modifier.size(22.dp),
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                    // ── Author header (effective author, live profile) ─────────
                    AuthorHeader(
                        pubkey        = model.pubkey,
                        picture       = authorProfile?.picture ?: if (isRepost) null else row.authorPicture,
                        displayName   = authorLabel,
                        nip05         = authorProfile?.nip05 ?: if (isRepost) null else row.authorNip05,
                        createdAt     = model.createdAt,
                        onAuthorClick = onAuthorClick,
                        onNoteClick   = {},
                        lookupProfile = lookupProfile,
                        profileFlow   = profileFlow,
                        // AuthorHeader applies its own horizontal/vertical padding internally;
                        // pass it bare (as EventCard.ArticleLayout does) — a modifier here
                        // stacks and doubles the row's padding.
                    )

                    // ── Banner image — full image at its natural aspect (no crop) ──
                    if (!image.isNullOrBlank()) {
                        SubcomposeAsyncImage(
                            model              = rememberFullWidthImageRequest(image, aspectRatio = 16f / 9f),
                            contentDescription = null,
                            contentScale       = ContentScale.FillWidth,
                            modifier           = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(
                                    bottomStart = Sizing.mediaCornerRadius,
                                    bottomEnd   = Sizing.mediaCornerRadius,
                                )),
                        )
                    }

                    // ── Title (centered; equal gap to banner above and body below) ──
                    if (!title.isNullOrBlank()) {
                        Text(
                            text       = title,
                            color      = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize   = AppType.title,
                            lineHeight = 30.sp,
                            textAlign  = TextAlign.Center,
                            modifier   = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.medium)
                                .padding(vertical = Spacing.large),
                        )
                    }

                    // ── Body content (native markdown) ─────────────────────────
                    // SelectionContainer makes the article body selectable; cyan
                    // links/hashtags stay tappable under selection (LinkAnnotation
                    // coexists with selection — verified on device).
                    SelectionContainer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.medium)
                            .padding(bottom = Spacing.xl),
                    ) {
                        MarkdownContent(
                            document             = document,
                            onHashtagClick       = onHashtagClick,
                            suppressLeadingTitle = title,
                        )
                    }
                }

                // ── Sticky bottom: engagement drawer (expands upward) + action bar ──
                // Same chrome as EventCard. The card renders the drawer BELOW an inline
                // bar; here the bar is sticky-bottom, so the drawer sits above it and the
                // whole container grows upward when opened.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceVariant)
                        .navigationBarsPadding(),
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = drawerOpen,
                        enter   = androidx.compose.animation.expandVertically(),
                        exit    = androidx.compose.animation.shrinkVertically(),
                    ) {
                        // The card's drawer scrolls with the feed LazyColumn; here it's
                        // bottom-anchored, so cap its height and give it its own scroll —
                        // otherwise a heavily-zapped article's list runs off-screen.
                        Box(
                            modifier = Modifier
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            EngagementDrawer(
                                eventId               = model.engagementId,
                                statsFlow             = statsFlow,
                                zapDetailsForEvent    = zapDetailsForEvent,
                                repostPubkeysForEvent = repostPubkeysForEvent,
                                reactionsForEvent     = reactionsForEvent,
                                profileFlow           = profileFlow,
                                lookupProfile         = lookupProfile,
                                onProfileTap          = onAuthorClick,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    EventActionBar(
                        noteId           = model.navigateId,
                        replyCount       = replyCount,
                        repostCount      = repostCount,
                        reactionCount    = reactionCount,
                        zapTotalSats     = zapTotalSats,
                        hasReacted       = hasReacted,
                        hasReposted      = hasReposted,
                        hasZapped        = hasZapped,
                        isNwcConfigured  = isNwcConfigured,
                        isZapLoading     = isZapLoading,
                        extraZapSats     = extraZapSats,
                        zapFlash         = zapFlash,
                        drawerOpen       = drawerOpen,
                        onChevronTap     = { drawerOpen = !drawerOpen },
                        onNoteClick      = { onNoteClick(model.navigateId) },
                        onComment        = { onNoteClick(model.navigateId) },
                        onReact          = onReact,
                        onReactLongPress = onReactLongPress,
                        pinnedEmojis     = pinnedEmojis,
                        onReactWithEmoji = onReactWithEmoji,
                        onRepost         = onRepost,
                        onQuote          = onQuote,
                        onZap            = onZap,
                        onSaveNwcUri     = onSaveNwcUri,
                        modifier         = Modifier.padding(top = Spacing.small),
                    )
                }
            }
        }
    }
}

private fun articleTagValue(tagsJson: String, key: String): String? = runCatching {
    Json.parseToJsonElement(tagsJson).jsonArray
        .firstOrNull { tag ->
            tag.jsonArray.getOrNull(0)?.jsonPrimitive?.content == key
        }
        ?.jsonArray?.getOrNull(1)?.jsonPrimitive?.content
        ?.takeIf { it.isNotBlank() }
}.getOrNull()
