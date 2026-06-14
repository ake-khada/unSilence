package com.unsilence.app.ui.feed

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import com.unsilence.app.ui.common.rememberFullWidthImageRequest
import com.unsilence.app.ui.markdown.MarkdownContent
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.toEventModel
import com.unsilence.app.data.model.markdown.MarkdownDocument
import com.unsilence.app.data.model.markdown.NativeMarkdownParser
import com.unsilence.app.data.wallet.ZapRequest
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BrandDeep
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.SurfaceVariant
import com.unsilence.app.ui.theme.TextSecondary
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
    val hashtags = model.article?.hashtags ?: emptyList()
    val context = LocalContext.current

    // Longform has no standard reading-time tag — derive it from the body word
    // count at ~200 wpm (rounded up, min 1 min). Shown as a caption under the title.
    val readingMinutes = remember(model.articleContent) {
        val words = model.articleContent?.split(Regex("\\s+"))?.count { it.isNotBlank() } ?: 0
        if (words == 0) null else ((words + 199) / 200)
    }

    // Tapping any hashtag (inline body span or topic chip) leaves the article for
    // that tag's feed — close the reader first, else the destination opens hidden
    // behind this Dialog and the tap looks dead.
    val onHashtagTap: (String) -> Unit = { tag -> onHashtagClick(tag); onDismiss() }

    // Native markdown body (replaces the WebView). Parsed off the composition
    // thread (Dispatchers.Default) so a max-size article can't hitch the open;
    // bounded by ParseLimits (H-spam discipline). Results are memoized in a small
    // module LRU so reopening an article is instant (cache hit → no loader, no
    // re-parse). The producer re-checks the cache by key so the shared reader
    // instance reparses correctly when `row` switches to a different article.
    val articleKey = remember(model.navigateId, model.articleContent) {
        "${model.navigateId}:${(model.articleContent ?: "").hashCode()}"
    }
    val document by produceState<MarkdownDocument?>(
        initialValue = cachedArticleDoc(articleKey),
        key1 = articleKey,
    ) {
        val cached = cachedArticleDoc(articleKey)
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = null
        val parsed = withContext(Dispatchers.Default) {
            NativeMarkdownParser.parse(model.articleContent ?: "")
        }
        putArticleDoc(articleKey, parsed)
        value = parsed
    }
    // Draw-bound probe at the parse→render boundary (mirrors the note path's
    // PARSE-HEAVY signal). Log.w survives R8 in release.
    LaunchedEffect(document?.truncated, model.navigateId) {
        if (document?.truncated == true) {
            Log.w("ARTICLE-PARSE-HEAVY", "id=${model.navigateId} blocks=${document?.blocks?.size} (capped)")
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

                    // ── Title + reading time (centered; equal gap to banner / body) ──
                    if (!title.isNullOrBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.medium)
                                .padding(vertical = Spacing.large),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text       = title,
                                color      = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize   = AppType.title,
                                lineHeight = 30.sp,
                                textAlign  = TextAlign.Center,
                                modifier   = Modifier.fillMaxWidth(),
                            )
                            if (readingMinutes != null) {
                                Text(
                                    text     = "$readingMinutes min read",
                                    color    = TextSecondary,
                                    fontSize = AppType.caption,
                                    modifier = Modifier.padding(top = Spacing.small),
                                )
                            }
                        }
                    }

                    // ── Body content (native markdown) ─────────────────────────
                    // SelectionContainer makes the article body selectable; cyan
                    // links/hashtags stay tappable under selection (LinkAnnotation
                    // coexists with selection — verified on device).
                    val doc = document
                    if (doc != null) {
                        SelectionContainer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.medium)
                                .padding(bottom = Spacing.xl),
                        ) {
                            MarkdownContent(
                                document             = doc,
                                onHashtagClick       = onHashtagTap,
                                suppressLeadingTitle = title,
                            )
                        }
                    } else {
                        // Off-main parse in flight (only shows on the first open of a
                        // big, uncached article — usually sub-frame, so it rarely
                        // flashes). Quiet centered spinner sized ~a body's worth.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp)
                                .padding(bottom = Spacing.xl),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color       = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp,
                                modifier    = Modifier.size(28.dp),
                            )
                        }
                    }

                    // ── Topic hashtags (longform `t` tags — a separate publish field,
                    //    not body text) as tappable chips in one horizontally-scrollable
                    //    row at the article end, with edge fades hinting more off-screen
                    //    (mirrors the relay category rail). ─────────────────────────────
                    if (hashtags.isNotEmpty()) {
                        val tagListState = rememberLazyListState()
                        Box(modifier = Modifier.padding(bottom = Spacing.xl)) {
                            LazyRow(
                                state                 = tagListState,
                                contentPadding        = PaddingValues(horizontal = Spacing.medium),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                            ) {
                                items(hashtags) { tag ->
                                    Text(
                                        text     = "#$tag",
                                        color    = BrandDeep,
                                        fontSize = AppType.footnote,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(Surface2)
                                            .clickable { onHashtagTap(tag) }
                                            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                                    )
                                }
                            }
                            // Edge fades — left appears once scrolled, right while more
                            // remains. matchParentSize + background only → never block taps.
                            if (tagListState.canScrollBackward) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(Brush.horizontalGradient(0f to Black, 0.14f to Color.Transparent)),
                                )
                            }
                            if (tagListState.canScrollForward) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(Brush.horizontalGradient(0.86f to Color.Transparent, 1f to Black)),
                                )
                            }
                        }
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

// ── Parsed-article LRU (off-main parse memoization) ──────────────────────────
// Reopening an article is instant (cache hit → no loader, no re-parse). Bounded
// so a long reading session can't grow unbounded. accessOrder=true → true LRU;
// synchronized for the Default-dispatcher writes vs Main-thread reads.
private const val ARTICLE_DOC_CACHE_CAP = 12
private val articleDocCache = object : LinkedHashMap<String, MarkdownDocument>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, MarkdownDocument>): Boolean =
        size > ARTICLE_DOC_CACHE_CAP
}
private fun cachedArticleDoc(key: String): MarkdownDocument? =
    synchronized(articleDocCache) { articleDocCache[key] }
private fun putArticleDoc(key: String, doc: MarkdownDocument) {
    synchronized(articleDocCache) { articleDocCache[key] = doc }
}
