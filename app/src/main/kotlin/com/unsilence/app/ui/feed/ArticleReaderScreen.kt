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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.SubcomposeAsyncImage
import com.unsilence.app.ui.common.rememberFullWidthImageRequest
import com.unsilence.app.ui.compose.ArticleCommentTarget
import com.unsilence.app.ui.compose.ComposeScreen
import com.unsilence.app.ui.markdown.MarkdownContent
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.shared.EventEngagementSnapshot
import com.unsilence.app.ui.shared.FeedDivider
import com.unsilence.app.ui.shared.PostActionsHost
import com.unsilence.app.ui.shared.pollActionCallbacks
import com.unsilence.app.ui.shared.rememberVideoPlaybackScope
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.toEventModel
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.relay.wotSubjectsForFeedRows
import com.unsilence.app.data.model.markdown.MarkdownDocument
import com.unsilence.app.data.model.markdown.MdBlock
import com.unsilence.app.data.model.markdown.MdInline
import com.unsilence.app.data.model.markdown.NativeMarkdownParser
import com.unsilence.app.data.wallet.ZapRequest
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.theme.AppType
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BrandDeep
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface2
import com.unsilence.app.ui.theme.TextSecondary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleReaderScreen(
    row: FeedRow,
    model: EventModel,
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
    articleReaderVm: ArticleReaderViewModel = hiltViewModel(
        key = "article-reader-${LocalAppSessionKey.current}",
    ),
    commentActionsVm: NoteActionsViewModel = hiltViewModel(
        key = "note-actions-${LocalAppSessionKey.current}",
    ),
) {
    // [model] comes from MES's verified-ingest sidecar. A flattened FeedRow no
    // longer carries enough trust context to authenticate an embedded repost,
    // so reparsing row.content here would either expose wire JSON or lose a
    // legitimate verified article on open.
    val title = model.article?.title ?: articleTagValue(row.tags, "title")
    val image = model.article?.image ?: articleTagValue(row.tags, "image")
    val hashtags = model.article?.hashtags ?: emptyList()
    val context = LocalContext.current
    var commentActionsRow by remember { mutableStateOf<FeedRow?>(null) }
    val pollActions = remember(commentActionsVm) { commentActionsVm.pollActionCallbacks() }

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

    // Profile/thread navigation must close the reader first — otherwise the
    // destination opens BEHIND this Dialog and the tap looks dead.
    val onAuthorTap: (String) -> Unit = { pubkey -> onDismiss(); onAuthorClick(pubkey) }
    val onNoteTap: (String) -> Unit = { id -> onDismiss(); onNoteClick(id) }

    // ── Comments (NIP-22 kind-1111 + legacy kind-1 by a-coordinate) ──────────
    // Dedicated VM owns the comment machinery + display providers; comment ACTIONS
    // /lookups/caches come from a NoteActionsViewModel (same host owner).
    val articleCoord = remember(model.engagementId, model.article?.dTag, model.pubkey) {
        model.article?.dTag?.takeIf { it.isNotBlank() }?.let { "30023:${model.pubkey}:$it" }
    }
    val commentsFlow = remember(articleCoord) { articleReaderVm.commentsFlow(articleCoord ?: "") }
    val comments by commentsFlow.collectAsStateWithLifecycle(emptyList())
    val wotLookups by articleReaderVm.wotLookups.collectAsStateWithLifecycle()
    val feedWotDisplayMode by articleReaderVm.feedWotDisplayMode.collectAsStateWithLifecycle()
    LaunchedEffect(articleCoord) {
        if (articleCoord != null) {
            articleReaderVm.fetchComments(articleCoord, model.engagementId, model.pubkey, row.relayUrl)
        }
    }
    LaunchedEffect(comments) {
        articleReaderVm.hydrateEngagement(comments)
        if (articleCoord != null) {
            articleReaderVm.fetchCommentReplies(comments.map { it.id }, model.pubkey, model.engagementId, row.relayUrl)
        }
    }
    LaunchedEffect(model.pubkey, comments) {
        articleReaderVm.requestWotHydration(
            buildSet {
                add(model.pubkey)
                addAll(wotSubjectsForFeedRows(comments, modelProvider = commentActionsVm::getEventModel))
            }
        )
    }
    // Depth-ordered for display: replies nest under their parent (header count
    // stays comments.size). Same flat list drives count/contributors in MES.
    val depthComments = remember(comments) { flattenArticleComments(comments) }
    val commentRows = remember(depthComments) { depthComments.map { it.row } }

    // Article-comment compose (NIP-22). Hosted locally as an overlay so no callback
    // threading through the 5 reader call sites; reader stays behind the compose.
    var commentTarget by remember { mutableStateOf<ArticleCommentTarget?>(null) }
    var legacyReplyToEventId by remember { mutableStateOf<String?>(null) }
    val articleRelayHint = row.relayUrl.takeIf { it.isNotBlank() }
    val openArticleComment: () -> Unit = {
        if (articleCoord != null) {
            commentTarget = ArticleCommentTarget(
                articleId = model.engagementId,
                articleCoord = articleCoord,
                articlePubkey = model.pubkey,
                articleRelayHint = articleRelayHint,
            )
        } else onNoteTap(model.navigateId)
    }

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
            try {
                NativeMarkdownParser.parse(model.articleContent ?: "")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (oom: OutOfMemoryError) {
                // The process cannot safely recover from genuine heap exhaustion.
                throw oom
            } catch (_: Throwable) {
                markdownParseFailureDocument()
            }
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
    val authorProfile = collectProfileAsState(model.pubkey, profileFlow)
    val sourceProfile = if (isRepost) collectProfileAsState(model.sourcePubkey, profileFlow) else null
    val authorLabel = authorProfile?.displayName?.takeIf { it.isNotBlank() }
        ?: authorProfile?.name?.takeIf { it.isNotBlank() && !looksLikeHexPubkey(it) }
        ?: (if (isRepost) null else row.displayName)
        ?: "${model.pubkey.take(6)}…${model.pubkey.takeLast(4)}"

    // Live engagement counts keyed off the effective id. The host statsFlow (feed/
    // profile VM) when present, else the reader VM's own MES-backed statsFlow — NEVER
    // the static FeedRow snapshot, which would leak a stale count (e.g. quote-posts
    // removed from the comment list but the old reply count lingering).
    val articleStatsFlow = remember(model.engagementId) {
        statsFlow?.invoke(model.engagementId) ?: articleReaderVm.statsFlow(model.engagementId)
    }
    val articleStats by articleStatsFlow.collectAsStateWithLifecycle()
    val sensitiveMode by articleReaderVm.sensitiveContentMode.collectAsStateWithLifecycle()
    val replyCount    = articleStats.replyCount
    val repostCount   = articleStats.repostCount
    val reactionCount = articleStats.reactionCount
    val zapTotalSats  = articleStats.zapTotalSats

    var drawerOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val cardWidthPx = LocalWindowInfo.current.containerSize.width
    val commentVideoScope = rememberVideoPlaybackScope(
        ownerId = "article-comments-${model.engagementId}",
        holder = commentActionsVm.sharedPlayerHolder,
        events = commentRows,
        listState = listState,
        videoModelProvider = commentActionsVm::getVideoRenderModels,
        cachedModelProvider = commentActionsVm::getCachedEventModel,
    )
    // Auto-reveal the engagement drawer when the chevron opens it: it sits at the
    // bottom of the article item, so without this it expands off-screen. Wait for the
    // expand to lay out, then scroll it into view (slides up under the bar).
    val drawerReveal = remember { BringIntoViewRequester() }
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) {
            delay(220)
            runCatching { drawerReveal.bringIntoView() }
        }
    }

    @OptIn(FlowPreview::class)
    LaunchedEffect(commentRows, cardWidthPx) {
        if (commentRows.isEmpty()) return@LaunchedEffect
        commentActionsVm.warmCardWindow(
            rows = commentRows,
            first = 0,
            last = commentRows.lastIndex.coerceAtMost(8),
            cardWidthPx = cardWidthPx,
            hydrateEngagement = false,
        )
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                (item.index - 2).takeIf { it in commentRows.indices }
            }
        }.sample(100).collect { visibleRows ->
            val first = visibleRows.minOrNull() ?: return@collect
            val last = visibleRows.maxOrNull() ?: first
            commentActionsVm.warmCardWindow(
                rows = commentRows,
                first = first,
                last = last,
                cardWidthPx = cardWidthPx,
                hydrateEngagement = false,
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows  = false,
        ),
    ) {
        // Edge-to-edge like the feed/profile: decorFitsSystemWindows=false lets the
        // dialog draw into the system-bar areas, so the black background/content
        // continues behind the gesture pill (no peek-through of the screen behind).
        // Top gets statusBarsPadding; the gesture pill simply overlays the bottom —
        // no navigation-bar padding anywhere.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Black),
        ) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                // ── Pinned top bar (Close / Share stay reachable) ──────────────
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

                // ── Article + engagement in ONE LazyColumn: the action bar is now
                //    inline (non-sticky) and the drawer expands DOWNWARD in the scroll
                //    flow. Comments will append as further items below (phase 3b). ──
                LazyColumn(
                    state    = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    item(key = "article") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // ── Author header (effective author, live profile) ──
                            // AuthorHeader applies its own padding — pass it bare.
                            AuthorHeader(
                                pubkey        = model.pubkey,
                                picture       = authorProfile?.picture?.takeIf { it.isNotBlank() }
                                    ?: if (isRepost) null else row.authorPicture,
                                displayName   = authorLabel,
                                nip05         = authorProfile?.nip05 ?: if (isRepost) null else row.authorNip05,
                                createdAt     = model.createdAt,
                                onAuthorClick = onAuthorTap,
                                onNoteClick   = {},
                                lookupProfile = lookupProfile,
                                profileFlow   = profileFlow,
                                wotLookup     = { key -> wotLookups[key] },
                                feedWotDisplayMode = feedWotDisplayMode,
                                repostSourcePubkey = if (isRepost) model.sourcePubkey else null,
                                repostSourceProfile = sourceProfile,
                            )

                            // ── Banner image — full image at natural aspect (no crop) ──
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

                            // ── Title + reading time (centered; equal gap banner/body) ──
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

                            // ── Body (native markdown; selectable, tappable spans) ──
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
                                // Off-main parse in flight (only the first open of a big,
                                // uncached article — usually sub-frame). Quiet spinner.
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

                            // ── Topic hashtags (`t` tags) — one scrollable chip row
                            //    with edge fades (mirrors the relay category rail). ──
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

                            // ── Inline engagement bar (non-sticky) + downward drawer ──
                            // Mirrors EventCard: bar, then the drawer expands BELOW it in
                            // the scroll flow (no height cap / inner scroll — the
                            // LazyColumn scrolls; comments will sit under this in 3b).
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            EventActionBar(
                                noteId           = model.navigateId,
                                zapTargetId      = model.engagementId,
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
                                onNoteClick      = { onNoteTap(model.navigateId) },
                                onComment        = openArticleComment,
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
                            androidx.compose.animation.AnimatedVisibility(
                                visible = drawerOpen,
                                enter   = androidx.compose.animation.expandVertically(),
                                exit    = androidx.compose.animation.shrinkVertically(),
                            ) {
                                Box(modifier = Modifier.bringIntoViewRequester(drawerReveal)) {
                                    EngagementDrawer(
                                        eventId               = model.engagementId,
                                        statsFlow             = statsFlow,
                                        zapDetailsForEvent    = zapDetailsForEvent,
                                        repostPubkeysForEvent = repostPubkeysForEvent,
                                        reactionsForEvent     = reactionsForEvent,
                                        profileFlow           = profileFlow,
                                        lookupProfile         = lookupProfile,
                                        onProfileTap          = onAuthorTap,
                                    )
                                }
                            }
                        }
                    }

                    // ── Comments (NIP-22 1111 + legacy kind-1, oldest-first) ──
                    if (comments.isNotEmpty()) {
                        item(key = "comments-header") {
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            Text(
                                text     = "${comments.size} ${if (comments.size == 1) "comment" else "comments"}",
                                color    = TextSecondary,
                                fontSize = AppType.footnote,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                            )
                        }
                        items(depthComments, key = { it.row.id }) { dc ->
                            val comment = dc.row
                            val depth = dc.depth
                            val cModel = remember(comment.id) {
                                commentActionsVm.getEventModel(comment.id) ?: comment.toEventModel()
                            }
                            val guideColor = Color.White.copy(alpha = 0.10f)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .drawBehind {
                                        for (d in 1..depth) {
                                            val x = (d * 12).dp.toPx()
                                            drawLine(guideColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                                        }
                                    }
                                    .padding(start = (depth * 12).dp),
                            ) {
                            EventCard(
                                model                 = cModel,
                                row                   = comment,
                                role                  = CardRole.Reply,
                                engagement            = EventEngagementSnapshot(isNwcConfigured = isNwcConfigured),
                                onNoteClick           = onNoteTap,
                                onComment             = {
                                    // kind-1111 comment → NIP-22 reply (kind-1111).
                                    // legacy kind-1 comment → normal kind-1 reply, but opened
                                    // as a LOCAL compose overlay (NIP-22 forbids 1111 replies to
                                    // kind-1; onNoteClick would open the thread behind the reader).
                                    if (comment.kind == 1111 && articleCoord != null) {
                                        commentTarget = ArticleCommentTarget(
                                            articleId = model.engagementId,
                                            articleCoord = articleCoord,
                                            articlePubkey = model.pubkey,
                                            articleRelayHint = articleRelayHint,
                                            parentId = comment.id,
                                            parentKind = comment.kind,
                                            parentPubkey = comment.pubkey,
                                            parentRelayHint = comment.relayUrl.takeIf { it.isNotBlank() },
                                        )
                                    } else {
                                        legacyReplyToEventId = comment.id
                                    }
                                },
                                onAuthorClick         = onAuthorTap,
                                onHashtagClick        = onHashtagTap,
                                onQuote               = onQuote,
                                onArticleClick        = { onNoteTap(it.id) },
                                onReact               = { commentActionsVm.react(comment.id, comment.pubkey) },
                                onReactLongPress      = {},
                                pinnedEmojis          = pinnedEmojis,
                                onReactWithEmoji      = { emoji -> commentActionsVm.react(comment.id, comment.pubkey, ":${emoji.shortcode}:", emoji.url) },
                                onRepost              = { commentActionsVm.repost(comment.id, comment.pubkey, comment.relayUrl) },
                                onZap                 = { req -> commentActionsVm.zap(comment.id, comment.pubkey, comment.relayUrl, req) },
                                onSaveNwcUri          = { uri -> commentActionsVm.saveNwcUri(uri) },
                                lookupProfile         = commentActionsVm::lookupProfile,
                                lookupEvent           = { id, hints -> commentActionsVm.lookupEvent(id, hints) },
                                lookupEventWithAuthor = { id, hints, authorPk -> commentActionsVm.lookupEvent(id, hints, authorPk) },
                                lookupEventReference = commentActionsVm::lookupEvent,
                                lookupModel           = commentActionsVm::getEventModel,
                                fetchOgMetadata       = commentActionsVm::fetchOgMetadata,
                                hasCachedOgMetadata   = commentActionsVm::hasCachedOgMetadata,
                                profileFlow           = articleReaderVm::profileFlow,
                                statsFlow             = articleReaderVm::statsFlow,
                                zapDetailsForEvent    = articleReaderVm::zapDetailsForEvent,
                                repostPubkeysForEvent = articleReaderVm::repostPubkeysForEvent,
                                reactionsForEvent     = articleReaderVm::reactionsForEvent,
                                imageDimensionCache   = commentActionsVm.imageDimensionCache,
                                thumbnailCache        = commentActionsVm.videoThumbnailCache,
                                exoPlayer             = commentVideoScope.exoPlayer,
                                isMuted               = commentVideoScope.isMuted,
                                onToggleMute          = { commentVideoScope.toggleMute() },
                                isActiveVideo         = commentVideoScope.isActiveVideo(comment.id),
                                activeVideoUrl        = commentVideoScope.activeVideoUrl,
                                isFullscreen          = commentVideoScope.showFullscreenVideo,
                                onOpenFullscreen      = { commentVideoScope.openFullscreen(comment.id) },
                                onVideoModelsResolved = { models ->
                                    commentVideoScope.registerVideoModels(comment.id, models)
                                },
                                sensitiveMode         = sensitiveMode,
                                isSensitive           = comment.hasContentWarning,
                                contentWarningReason  = comment.contentWarningReason,
                                onLongPress           = { commentActionsRow = comment },
                                wotLookup             = { key -> wotLookups[key] },
                                feedWotDisplayMode    = feedWotDisplayMode,
                                onWotSubjectsVisible  = articleReaderVm::requestWotHydration,
                                pollActions           = pollActions,
                            )
                            }
                            FeedDivider()
                        }
                    }
                }
            }

            // ── Article-comment compose overlay (NIP-22 kind-1111) ──
            // Full-screen overlay above the reader; reader stays behind.
            commentTarget?.let { target ->
                ComposeScreen(
                    articleCommentTarget = target,
                    onDismiss            = { commentTarget = null },
                )
            }
            // Legacy kind-1 comment → normal kind-1 reply, opened above the reader.
            legacyReplyToEventId?.let { id ->
                ComposeScreen(
                    replyToEventId = id,
                    onDismiss      = { legacyReplyToEventId = null },
                )
            }
        }
    }

    if (commentVideoScope.showFullscreenVideo) {
        FullScreenVideoDialog(
            exoPlayer = commentVideoScope.exoPlayer,
            videoUrl  = commentVideoScope.activeVideoUrl,
            onDismiss = { commentVideoScope.dismissFullscreen() },
        )
    }

    PostActionsHost(
        row = commentActionsRow,
        profileFlow = articleReaderVm::profileFlow,
        canDelete = { activeRow -> commentActionsVm.isOwnPubkey(activeRow.pubkey) },
        onMuteUser = commentActionsVm::muteUser,
        onReport = { activeRow, type -> commentActionsVm.reportEvent(activeRow.id, activeRow.pubkey, type) },
        onDelete = { activeRow -> commentActionsVm.deleteEvent(activeRow.id, activeRow.pubkey, activeRow.relayUrl) },
        eventModelProvider = commentActionsVm::getEventModel,
        relayProvenance = commentActionsVm::relayProvenance,
        onDismiss = { commentActionsRow = null },
    )
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

/** Safe, renderable fallback for malformed input or an unexpected parser failure. */
private fun markdownParseFailureDocument(): MarkdownDocument = MarkdownDocument(
    blocks = listOf(
        MdBlock.Paragraph(
            listOf(MdInline.Text("This article could not be rendered safely.")),
        ),
    ),
    truncated = true,
)
