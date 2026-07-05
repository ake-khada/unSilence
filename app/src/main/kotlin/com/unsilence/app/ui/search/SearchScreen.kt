package com.unsilence.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.SearchOff
import com.unsilence.app.ui.common.EmptyState
import com.unsilence.app.ui.theme.AppType
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.common.ShimmerNoteCard
import com.unsilence.app.ui.common.ShimmerTrendingDiscovery
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.toEventModel
import com.unsilence.app.ui.common.LocalOpenEmojiSettings
import com.unsilence.app.ui.feed.ArticleReaderScreen
import com.unsilence.app.ui.feed.EmojiPickerSheet
import com.unsilence.app.ui.feed.NoteActionsViewModel
import com.unsilence.app.ui.feed.engagementId
import com.unsilence.app.ui.shared.EngagementSnapshot
import com.unsilence.app.ui.shared.EventActionCallbacks
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.shared.eventFeedItems
import com.unsilence.app.ui.theme.Black
import com.unsilence.app.ui.theme.BorderFaint
import com.unsilence.app.ui.theme.BorderSubtle
import com.unsilence.app.ui.theme.Brand
import com.unsilence.app.ui.theme.BrandSoft
import com.unsilence.app.ui.theme.Sizing
import com.unsilence.app.ui.theme.Spacing
import com.unsilence.app.ui.theme.Surface1
import com.unsilence.app.ui.theme.Text3
import com.unsilence.app.ui.theme.TextSecondary
import com.unsilence.app.ui.theme.Zap
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample

private val TAB_LABELS = listOf("All", "People", "Notes", "Tags")

@Composable
fun SearchScreen(
    onNoteClick: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onAuthorClick: (pubkey: String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {},
    onQuote: (String) -> Unit = {},
    initialQuery: String? = null,
    onInitialQueryConsumed: () -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(
        key = "search-${LocalAppSessionKey.current}",
    ),
    actionsViewModel: NoteActionsViewModel = hiltViewModel(
        key = "note-actions-${LocalAppSessionKey.current}",
    ),
) {
    val state           by viewModel.uiState.collectAsStateWithLifecycle()
    val sensitiveMode   by viewModel.sensitiveContentMode.collectAsStateWithLifecycle()
    val trendingHashtags by viewModel.trendingHashtags.collectAsStateWithLifecycle()
    val trendingUsers   by viewModel.trendingUsers.collectAsStateWithLifecycle()
    val reactedIds      by actionsViewModel.reactedEventIds.collectAsStateWithLifecycle()
    val repostedIds     by actionsViewModel.repostedEventIds.collectAsStateWithLifecycle()
    val zappedIds       by actionsViewModel.zappedEventIds.collectAsStateWithLifecycle()
    val zapLoadingIds   by actionsViewModel.zapLoading.collectAsStateWithLifecycle()
    val optimisticSats  by actionsViewModel.optimisticZapSats.collectAsStateWithLifecycle()
    val zapFlash        by actionsViewModel.zapFlashState.collectAsStateWithLifecycle()
    val isNwcConfigured = actionsViewModel.isNwcConfigured

    val showSnackbar = LocalShowSnackbar.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var articleRow by remember { mutableStateOf<FeedRow?>(null) }
    val noteListState = rememberLazyListState()
    val cardWidthPx = LocalWindowInfo.current.containerSize.width

    // ── Emoji reaction picker state ─────────────────────────────────────────
    var emojiReactTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showFullEmojiPicker by remember { mutableStateOf(false) }
    val openEmojiSettings = LocalOpenEmojiSettings.current
    val pinnedShortcodes by actionsViewModel.pinnedEmojiShortcodes.collectAsStateWithLifecycle()
    val pinnedEmojis = remember(pinnedShortcodes) { actionsViewModel.getPinnedEmojis() }

    // ── Action failure snackbar ──────────────────────────────────────────────
    LaunchedEffect(Unit) {
        actionsViewModel.actionError.collect { showSnackbar(it) }
    }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Tapping a result navigates away from search — drop IME focus first so the
    // keyboard doesn't linger over the destination (profile / note / article).
    // clearFocus() (not just hide()) ensures the text field releases focus so the
    // IME stays down instead of popping back.
    val onAuthorClickDismiss: (String) -> Unit = { pubkey ->
        keyboardController?.hide()
        focusManager.clearFocus()
        onAuthorClick(pubkey)
    }
    val onNoteClickDismiss: (String) -> Unit = { id ->
        keyboardController?.hide()
        focusManager.clearFocus()
        onNoteClick(id)
    }
    var pendingSearch by remember { mutableStateOf(false) }

    @OptIn(FlowPreview::class)
    LaunchedEffect(state.noteResults, state.peopleResults, selectedTab, cardWidthPx) {
        if (state.noteResults.isEmpty()) return@LaunchedEffect
        fun warmVisibleRange(first: Int, last: Int) {
            val noteOffset = if (selectedTab == 0) state.peopleResults.size.coerceAtMost(3) else 0
            val dataFirst = (first - noteOffset).coerceAtLeast(0)
            val dataLast = (last - noteOffset).coerceAtMost(state.noteResults.lastIndex)
            if (dataFirst <= dataLast) {
                actionsViewModel.warmCardWindow(
                    rows = state.noteResults,
                    first = dataFirst,
                    last = dataLast,
                    cardWidthPx = cardWidthPx,
                    hydrateEngagement = true,
                )
            }
        }

        if (cardWidthPx > 0) {
            val info = noteListState.layoutInfo
            val noteOffset = if (selectedTab == 0) state.peopleResults.size.coerceAtMost(3) else 0
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: noteOffset
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: (noteOffset + 8)
            warmVisibleRange(first, last)
        }

        snapshotFlow {
            val info = noteListState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            first to last
        }.sample(100).collect { (first, last) ->
            warmVisibleRange(first, last)
        }
    }

    DisposableEffect(Unit) { onDispose { viewModel.onScreenLeft() } }
    LaunchedEffect(Unit) {
        viewModel.refreshTrendingIfStale()
        // Only auto-focus (open keyboard) if there's no pre-filled query
        if (initialQuery == null) focusRequester.requestFocus()
    }
    LaunchedEffect(state.loading) { if (state.loading) pendingSearch = false }
    // Consume initial query from hashtag tap navigation
    LaunchedEffect(initialQuery) {
        if (initialQuery != null) {
            keyboardController?.hide()
            viewModel.search(initialQuery)
            pendingSearch = initialQuery.length >= 3
            if (initialQuery.startsWith("#")) selectedTab = 3 // Tags tab
            onInitialQueryConsumed()
        }
    }

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
                .background(Surface1, RoundedCornerShape(Spacing.medium))
                .border(1.dp, BorderSubtle, RoundedCornerShape(Spacing.medium))
                .padding(horizontal = Spacing.medium, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector        = Icons.Filled.Search,
                contentDescription = null,
                tint               = Text3,
                modifier           = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(Spacing.small))
            BasicTextField(
                value         = state.query,
                onValueChange = {
                    viewModel.search(it)
                    pendingSearch = it.length >= 3
                },
                textStyle     = TextStyle(color = Color.White, fontSize = AppType.bodySmall),
                cursorBrush   = SolidColor(Brand),
                singleLine    = true,
                modifier      = Modifier.weight(1f).focusRequester(focusRequester),
                decorationBox = { inner ->
                    // Box centers the placeholder and the text/cursor on the same
                    // vertical axis \u2014 without it they stack at the top, so the
                    // cursor sits higher than the placeholder text.
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (state.query.isEmpty()) {
                            Text(
                                text     = "Search voices, notes, npubs\u2026",
                                color    = Text3,
                                fontSize = AppType.bodySmall,
                            )
                        }
                        inner()
                    }
                },
            )
            if (state.query.isNotEmpty()) {
                Spacer(Modifier.width(Spacing.small))
                Icon(
                    imageVector        = Icons.Filled.Close,
                    contentDescription = "Clear search",
                    tint               = Text3,
                    modifier           = Modifier
                        .size(14.dp)
                        .clickable {
                            viewModel.search("")
                            pendingSearch = false
                            focusRequester.requestFocus()
                        },
                )
            }
        }

        // Typing / loading feedback
        if (pendingSearch || state.loading) {
            LinearProgressIndicator(
                modifier   = Modifier.fillMaxWidth().height(2.dp),
                color      = Brand,
                trackColor = Color.Transparent,
            )
        } else {
            Spacer(Modifier.height(2.dp))
        }

        // ── Underlined tab row (only visible with active search) ─────────────
        if (state.hasSearched) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium)
                    .drawBehind {
                        drawLine(
                            color       = BorderFaint,
                            start       = Offset(0f, size.height),
                            end         = Offset(size.width, size.height),
                            strokeWidth = 1f,
                        )
                    },
                horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                TAB_LABELS.forEachIndexed { index, label ->
                    val isActive = selectedTab == index
                    Box(
                        modifier = Modifier
                            .clickable { selectedTab = index }
                            .drawBehind {
                                if (isActive) {
                                    drawLine(
                                        color       = Brand,
                                        start       = Offset(0f, size.height),
                                        end         = Offset(size.width, size.height),
                                        strokeWidth = 2.dp.toPx(),
                                    )
                                }
                            }
                            .padding(vertical = Spacing.small),
                    ) {
                        Text(
                            text       = label,
                            color      = if (isActive) Color.White else Text3,
                            fontSize   = AppType.footnote,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // ── Results ───────────────────────────────────────────────────────────
        Box(modifier = Modifier
            .fillMaxSize()
            .nestedScroll(remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        keyboardController?.hide()
                        return Offset.Zero
                    }
                }
            })
        ) {
            when {
                !state.hasSearched -> {
                    if (trendingHashtags.isEmpty() && trendingUsers.isEmpty()) {
                        ShimmerTrendingDiscovery()
                    } else {
                        TrendingDiscovery(
                            hashtags = trendingHashtags,
                            users = trendingUsers,
                            onHashtagClick = { tag ->
                                viewModel.search("#$tag")
                                pendingSearch = true
                                selectedTab = 3
                            },
                            onUserClick = onAuthorClickDismiss,
                        )
                    }
                }

                state.loading && state.noteResults.isEmpty() && state.peopleResults.isEmpty() -> {
                    if (selectedTab == 1) {
                        // People tab loading
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                color       = Brand,
                                modifier    = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(Spacing.small))
                            Text("Searching relays\u2026", color = TextSecondary, fontSize = AppType.body)
                        }
                    } else {
                        // Notes/Tags/All tab loading
                        LazyColumn(state = noteListState, modifier = Modifier.fillMaxSize()) {
                            items(3) { ShimmerNoteCard(showMedia = it == 0) }
                        }
                    }
                }

                selectedTab == 0 -> {
                    // All tab — mixed results
                    val hasAny = state.peopleResults.isNotEmpty() || state.noteResults.isNotEmpty()
                    if (!hasAny) {
                        EmptyState(
                            icon    = Icons.Outlined.SearchOff,
                            message = "No results for \u201c${state.query}\u201d",
                        )
                    } else {
                        val engagement = rememberEngagement(
                            reactedIds, repostedIds, zappedIds, isNwcConfigured,
                            zapLoadingIds, optimisticSats, zapFlash,
                        )
                        val callbacks = rememberCallbacks(
                            onNoteClickDismiss, onComment, onAuthorClickDismiss, onHashtagClick,
                            onQuote, actionsViewModel,
                            { keyboardController?.hide(); focusManager.clearFocus(); articleRow = it },
                            { id, pk -> emojiReactTarget = id to pk; showFullEmojiPicker = true },
                            pinnedEmojis,
                            viewModel,
                        )
                        LazyColumn(state = noteListState, modifier = Modifier.fillMaxSize()) {
                            if (state.peopleResults.isNotEmpty()) {
                                items(state.peopleResults.take(3), key = { it.pubkey }) { user ->
                                    ProfileCard(user = user, onClick = { onAuthorClickDismiss(user.pubkey) })
                                    HorizontalDivider(color = BorderFaint, thickness = 0.5.dp)
                                }
                            }
                            eventFeedItems(
                                events = state.noteResults,
                                engagement = engagement,
                                callbacks = callbacks,
                                videoScope = null,
                                role = CardRole.Search,
                                thumbnailCache = actionsViewModel.videoThumbnailCache,
                                imageDimensionCache = actionsViewModel.imageDimensionCache,
                                eventModelProvider = actionsViewModel::getEventModel,
                                sensitiveMode = sensitiveMode,
                            )
                        }
                    }
                }

                selectedTab == 1 -> {
                    // People tab
                    if (state.peopleResults.isEmpty()) {
                        EmptyState(
                            icon    = Icons.Outlined.SearchOff,
                            message = "No results for \u201c${state.query}\u201d",
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.peopleResults, key = { it.pubkey }) { user ->
                                ProfileCard(user = user, onClick = { onAuthorClickDismiss(user.pubkey) })
                                HorizontalDivider(color = BorderFaint, thickness = 0.5.dp)
                            }
                        }
                    }
                }

                else -> {
                    // Notes (2) / Tags (3)
                    if (state.noteResults.isEmpty()) {
                        EmptyState(
                            icon    = Icons.Outlined.SearchOff,
                            message = "No results for \u201c${state.query}\u201d",
                        )
                    } else {
                        val engagement = rememberEngagement(
                            reactedIds, repostedIds, zappedIds, isNwcConfigured,
                            zapLoadingIds, optimisticSats, zapFlash,
                        )
                        val callbacks = rememberCallbacks(
                            onNoteClickDismiss, onComment, onAuthorClickDismiss, onHashtagClick,
                            onQuote, actionsViewModel,
                            { keyboardController?.hide(); focusManager.clearFocus(); articleRow = it },
                            { id, pk -> emojiReactTarget = id to pk; showFullEmojiPicker = true },
                            pinnedEmojis,
                            viewModel,
                        )
                        LazyColumn(state = noteListState, modifier = Modifier.fillMaxSize()) {
                            eventFeedItems(
                                events = state.noteResults,
                                engagement = engagement,
                                callbacks = callbacks,
                                videoScope = null,
                                role = CardRole.Search,
                                thumbnailCache = actionsViewModel.videoThumbnailCache,
                                imageDimensionCache = actionsViewModel.imageDimensionCache,
                                eventModelProvider = actionsViewModel::getEventModel,
                                sensitiveMode = sensitiveMode,
                            )
                        }
                    }
                }
            }
        }
    }

    articleRow?.let { row ->
        // Effective engagement target (kind-6/16 reposts → original event).
        val model = remember(row.id, row.content, row.tags) { row.toEventModel() }
        ArticleReaderScreen(
            row             = row,
            onDismiss       = { articleRow = null },
            onNoteClick     = onNoteClickDismiss,
            onReact         = { actionsViewModel.react(model.engagementId, model.pubkey) },
            onReactLongPress = {
                emojiReactTarget = model.engagementId to model.pubkey
                showFullEmojiPicker = true
            },
            pinnedEmojis    = pinnedEmojis,
            onReactWithEmoji = { emoji ->
                actionsViewModel.react(model.engagementId, model.pubkey, ":${emoji.shortcode}:", emoji.url)
            },
            onRepost        = { actionsViewModel.repost(model.engagementId, model.pubkey, row.relayUrl) },
            onQuote         = onQuote,
            onZap           = { req -> actionsViewModel.zap(model.engagementId, model.pubkey, row.relayUrl, req) },
            onSaveNwcUri    = { uri -> actionsViewModel.saveNwcUri(uri) },
            hasReacted      = row.engagementId in reactedIds,
            hasReposted     = row.engagementId in repostedIds,
            hasZapped       = row.engagementId in zappedIds,
            isNwcConfigured = isNwcConfigured,
            isZapLoading    = model.engagementId in zapLoadingIds,
            extraZapSats    = optimisticSats[model.engagementId] ?: 0L,
            zapFlash        = zapFlash,
            onAuthorClick   = onAuthorClickDismiss,
            onHashtagClick  = onHashtagClick,
            lookupProfile   = actionsViewModel::lookupProfile,
            profileFlow     = viewModel::profileFlow,
            statsFlow       = viewModel::statsFlow,
            zapDetailsForEvent    = viewModel::zapDetailsForEvent,
            repostPubkeysForEvent = viewModel::repostPubkeysForEvent,
            reactionsForEvent     = viewModel::reactionsForEvent,
        )
    }

    // ── Full emoji picker sheet ─────────────────────────────────────────────
    if (showFullEmojiPicker && emojiReactTarget != null) {
        val (eventId, pubkey) = emojiReactTarget!!
        EmojiPickerSheet(
            emojis = actionsViewModel.getSubscribedEmojis(),
            pinnedShortcodes = pinnedShortcodes,
            onSelect = { emoji ->
                actionsViewModel.react(eventId, pubkey, ":${emoji.shortcode}:", emoji.url)
                showFullEmojiPicker = false
                emojiReactTarget = null
            },
            onTogglePin = { actionsViewModel.togglePinnedEmoji(it) },
            onOpenSettings = openEmojiSettings,
            onDismiss = {
                showFullEmojiPicker = false
                emojiReactTarget = null
            },
            categories = actionsViewModel.getSubscribedEmojisBySet(),
        )
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
                    model              = rememberAvatarImageRequest(user.picture, Sizing.avatar + 8.dp),
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
                    fontSize   = AppType.body,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
            }
            Text(
                text     = remember(user.pubkey) { shortNpub(user.pubkey) },
                color    = TextSecondary,
                fontSize = AppType.footnote,
            )
            if (!user.about.isNullOrBlank()) {
                Text(
                    text     = user.about,
                    color    = TextSecondary,
                    fontSize = AppType.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

// ── Helper composables ───────────────────────────────────────────────────────

@Composable
private fun rememberEngagement(
    reactedIds: Set<String>,
    repostedIds: Set<String>,
    zappedIds: Set<String>,
    isNwcConfigured: Boolean,
    zapLoadingIds: Set<String>,
    optimisticSats: Map<String, Long>,
    zapFlash: NoteActionsViewModel.ZapFlashState?,
): EngagementSnapshot = remember(reactedIds, repostedIds, zappedIds, isNwcConfigured, zapLoadingIds, optimisticSats, zapFlash) {
    EngagementSnapshot(
        reactedIds = reactedIds, repostedIds = repostedIds, zappedIds = zappedIds,
        isNwcConfigured = isNwcConfigured, zapLoadingIds = zapLoadingIds,
        optimisticZapSats = optimisticSats, zapFlash = zapFlash,
    )
}

@Composable
private fun rememberCallbacks(
    onNoteClick: (String) -> Unit,
    onComment: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    onHashtagClick: (String) -> Unit,
    onQuote: (String) -> Unit,
    actionsViewModel: NoteActionsViewModel,
    onArticleClick: (FeedRow) -> Unit,
    onReactLongPress: (String, String) -> Unit,
    pinnedEmojis: List<com.unsilence.app.data.memory.CustomEmoji>,
    viewModel: SearchViewModel,
): EventActionCallbacks = remember(onNoteClick, onComment, onAuthorClick, onHashtagClick, onQuote, pinnedEmojis, viewModel) {
    EventActionCallbacks(
        onNoteClick = onNoteClick,
        onComment = onComment,
        onAuthorClick = onAuthorClick,
        onHashtagClick = onHashtagClick,
        onQuote = onQuote,
        onArticleClick = onArticleClick,
        react = { id, pk, emoji, url -> actionsViewModel.react(id, pk, emoji, url) },
        onReactLongPress = { id, pk -> onReactLongPress(id, pk) },
        pinnedEmojis = { pinnedEmojis },
        repost = { id, pk, relay -> actionsViewModel.repost(id, pk, relay) },
        zap = { id, pk, relay, req -> actionsViewModel.zap(id, pk, relay, req) },
        saveNwcUri = { actionsViewModel.saveNwcUri(it) },
        lookupProfile = actionsViewModel::lookupProfile,
        lookupEvent = { id, hints -> actionsViewModel.lookupEvent(id, hints) },
        lookupEventWithAuthor = { id, hints, authorPk -> actionsViewModel.lookupEvent(id, hints, authorPk) },
        fetchOgMetadata = actionsViewModel::fetchOgMetadata,
        hasCachedOgMetadata = actionsViewModel::hasCachedOgMetadata,
        profileFlow = viewModel::profileFlow,
        statsFlow = viewModel::statsFlow,
        zapDetailsForEvent = viewModel::zapDetailsForEvent,
        repostPubkeysForEvent = viewModel::repostPubkeysForEvent,
        reactionsForEvent = viewModel::reactionsForEvent,
    )
}

// ── Trending discovery (empty state) ─────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrendingDiscovery(
    hashtags: List<Pair<String, Int>>,
    users: List<UserEntity>,
    onHashtagClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Spacing.xl),
    ) {
        if (hashtags.isNotEmpty()) {
            item {
                // Section header: TRENDING TONIGHT
                Row(
                    modifier = Modifier.padding(
                        start = Spacing.medium, end = Spacing.medium,
                        top = Spacing.medium, bottom = Spacing.small,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ElectricBolt,
                        contentDescription = null,
                        tint = Zap,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text           = "TRENDING TONIGHT",
                        color          = Text3,
                        fontSize       = AppType.caption,
                        fontWeight     = FontWeight.Medium,
                        fontFamily     = FontFamily.Monospace,
                        letterSpacing  = 1.5.sp,
                    )
                }
            }
            item {
                FlowRow(
                    modifier = Modifier.padding(horizontal = Spacing.medium),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.micro),
                    verticalArrangement = Arrangement.spacedBy(Spacing.micro),
                ) {
                    hashtags.forEachIndexed { index, (tag, count) ->
                        HashtagPill(
                            tag = tag,
                            count = count,
                            filled = index == 0,
                            onClick = { onHashtagClick(tag) },
                        )
                    }
                }
            }
        }

        if (users.isNotEmpty()) {
            item {
                // Section header: VOICES TO FOLLOW
                Row(
                    modifier = Modifier.padding(
                        start = Spacing.medium, end = Spacing.medium,
                        top = Spacing.large, bottom = Spacing.small,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = null,
                        tint = Zap,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text           = "VOICES TO FOLLOW",
                        color          = Text3,
                        fontSize       = AppType.caption,
                        fontWeight     = FontWeight.Medium,
                        fontFamily     = FontFamily.Monospace,
                        letterSpacing  = 1.5.sp,
                    )
                }
            }
            items(users.size) { index ->
                val user = users[index]
                TrendingUserRow(user = user, onClick = { onUserClick(user.pubkey) })
                if (index < users.lastIndex) {
                    HorizontalDivider(
                        color     = BorderFaint,
                        thickness = 0.5.dp,
                        modifier  = Modifier.padding(horizontal = Spacing.medium),
                    )
                }
            }
        }
    }
}

@Composable
private fun HashtagPill(tag: String, count: Int, filled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .then(
                if (filled) {
                    Modifier.background(BrandSoft, RoundedCornerShape(50))
                } else {
                    Modifier.border(1.dp, BorderSubtle, RoundedCornerShape(50))
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text       = "#$tag",
            color      = if (filled) Brand else Color.White,
            fontSize   = AppType.caption,
            fontWeight = if (filled) FontWeight.Medium else FontWeight.Normal,
        )
        if (count > 1) {
            Text(
                text       = formatCount(count.toLong()),
                color      = Text3,
                fontSize   = AppType.caption,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun TrendingUserRow(user: UserEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(Sizing.avatar)
                .clip(CircleShape),
        ) {
            IdentIcon(pubkey = user.pubkey, modifier = Modifier.fillMaxSize())
            if (!user.picture.isNullOrBlank()) {
                AsyncImage(
                    model              = rememberAvatarImageRequest(user.picture, Sizing.avatar),
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
                    fontSize   = AppType.body,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
            }
            // npub + follower count in mono
            val npub = remember(user.pubkey) { shortNpub(user.pubkey) }
            val meta = buildString {
                append(npub)
                if (user.followerCount != null && user.followerCount > 0) {
                    append(" \u00B7 ")
                    append(formatCount(user.followerCount))
                    append(" followers")
                }
            }
            Text(
                text       = meta,
                color      = Text3,
                fontSize   = AppType.caption,
                fontFamily = FontFamily.Monospace,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "${count / 1_000_000}.${(count % 1_000_000) / 100_000}M"
    count >= 1_000     -> "${count / 1_000}.${(count % 1_000) / 100}k"
    else               -> count.toString()
}

private fun shortNpub(pubkeyHex: String): String {
    val npub = runCatching { pubkeyHex.hexToByteArray().toNpub() }.getOrNull()
    val value = npub ?: pubkeyHex
    return if (value.length > 18) "${value.take(10)}\u2026${value.takeLast(6)}" else value
}
