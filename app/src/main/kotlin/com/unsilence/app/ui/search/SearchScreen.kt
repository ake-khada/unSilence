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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.common.ShimmerNoteCard
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
    viewModel: SearchViewModel = hiltViewModel(),
    actionsViewModel: NoteActionsViewModel = hiltViewModel(),
) {
    val state           by viewModel.uiState.collectAsStateWithLifecycle()
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

    // ── Emoji reaction picker state ─────────────────────────────────────────
    var emojiReactTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showFullEmojiPicker by remember { mutableStateOf(false) }
    val openEmojiSettings = LocalOpenEmojiSettings.current
    val pinnedShortcodes by actionsViewModel.pinnedEmojiShortcodes.collectAsStateWithLifecycle()

    // ── Zap failure snackbar (lifted from per-card LaunchedEffect) ────────────
    LaunchedEffect(zapFlash) {
        val flash = zapFlash ?: return@LaunchedEffect
        if (!flash.success) showSnackbar("Zap failed: ${flash.message ?: "unknown error"}")
    }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var pendingSearch by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { viewModel.onScreenLeft() } }
    LaunchedEffect(Unit) {
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
                    if (state.query.isEmpty()) {
                        Text(
                            text     = "Search voices, notes, npubs\u2026",
                            color    = Text3,
                            fontSize = AppType.bodySmall,
                        )
                    }
                    inner()
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
                    TrendingDiscovery(
                        hashtags = trendingHashtags,
                        users = trendingUsers,
                        onHashtagClick = { tag ->
                            viewModel.search("#$tag")
                            pendingSearch = true
                            selectedTab = 3
                        },
                        onUserClick = onAuthorClick,
                    )
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
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                            onNoteClick, onComment, onAuthorClick, onHashtagClick,
                            onQuote, actionsViewModel, { articleRow = it },
                            { id, pk -> emojiReactTarget = id to pk; showFullEmojiPicker = true },
                        )
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            if (state.peopleResults.isNotEmpty()) {
                                items(state.peopleResults.take(3), key = { it.pubkey }) { user ->
                                    ProfileCard(user = user, onClick = { onAuthorClick(user.pubkey) })
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
                                ProfileCard(user = user, onClick = { onAuthorClick(user.pubkey) })
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
                            onNoteClick, onComment, onAuthorClick, onHashtagClick,
                            onQuote, actionsViewModel, { articleRow = it },
                            { id, pk -> emojiReactTarget = id to pk; showFullEmojiPicker = true },
                        )
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            eventFeedItems(
                                events = state.noteResults,
                                engagement = engagement,
                                callbacks = callbacks,
                                videoScope = null,
                                role = CardRole.Search,
                                thumbnailCache = actionsViewModel.videoThumbnailCache,
                                imageDimensionCache = actionsViewModel.imageDimensionCache,
                                eventModelProvider = actionsViewModel::getEventModel,
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
            onNoteClick     = onNoteClick,
            onReact         = { actionsViewModel.react(model.engagementId, model.pubkey) },
            onReactLongPress = {
                emojiReactTarget = model.engagementId to model.pubkey
                showFullEmojiPicker = true
            },
            pinnedEmojis    = actionsViewModel.getPinnedEmojis(),
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
            onAuthorClick   = onAuthorClick,
            lookupProfile   = actionsViewModel::lookupProfile,
            // SearchScreen has no per-event aggregation flows → reader falls back to
            // static row.* counts + empty drawer (profileFlow/statsFlow/... default null).
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
                text     = "${user.pubkey.take(6)}…${user.pubkey.takeLast(4)}",
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
): EventActionCallbacks = remember(onNoteClick, onComment, onAuthorClick, onHashtagClick, onQuote) {
    EventActionCallbacks(
        onNoteClick = onNoteClick,
        onComment = onComment,
        onAuthorClick = onAuthorClick,
        onHashtagClick = onHashtagClick,
        onQuote = onQuote,
        onArticleClick = onArticleClick,
        react = { id, pk, emoji, url -> actionsViewModel.react(id, pk, emoji, url) },
        onReactLongPress = { id, pk -> onReactLongPress(id, pk) },
        pinnedEmojis = actionsViewModel::getPinnedEmojis,
        repost = { id, pk, relay -> actionsViewModel.repost(id, pk, relay) },
        zap = { id, pk, relay, req -> actionsViewModel.zap(id, pk, relay, req) },
        saveNwcUri = { actionsViewModel.saveNwcUri(it) },
        lookupProfile = actionsViewModel::lookupProfile,
        lookupEvent = { id, hints -> actionsViewModel.lookupEvent(id, hints) },
        lookupEventWithAuthor = { id, hints, authorPk -> actionsViewModel.lookupEvent(id, hints, authorPk) },
        fetchOgMetadata = actionsViewModel::fetchOgMetadata,
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
                // Section header: ⚡ TRENDING TONIGHT
                Row(
                    modifier = Modifier.padding(
                        start = Spacing.medium, end = Spacing.medium,
                        top = Spacing.medium, bottom = Spacing.small,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.micro),
                ) {
                    Text(
                        text       = "\u26A1",
                        fontSize   = 10.sp,
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
                Text(
                    text           = "VOICES TO FOLLOW",
                    color          = Text3,
                    fontSize       = AppType.caption,
                    fontWeight     = FontWeight.Medium,
                    fontFamily     = FontFamily.Monospace,
                    letterSpacing  = 1.5.sp,
                    modifier       = Modifier.padding(
                        start = Spacing.medium, end = Spacing.medium,
                        top = Spacing.large, bottom = Spacing.small,
                    ),
                )
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
            val meta = buildString {
                append(user.pubkey.take(6))
                append("\u2026")
                append(user.pubkey.takeLast(4))
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
