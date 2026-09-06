package com.unsilence.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.compose.ui.input.pointer.pointerInput
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
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.ui.common.IdentIcon
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.common.ShimmerNoteCard
import com.unsilence.app.ui.common.ShimmerTrendingDiscovery
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.toEventModel
import com.unsilence.app.ui.common.LocalOpenEmojiSettings
import com.unsilence.app.ui.feed.ArticleReaderScreen
import com.unsilence.app.ui.feed.EventCardActions
import com.unsilence.app.ui.feed.EmojiPickerSheet
import com.unsilence.app.ui.feed.NoteActionsViewModel
import com.unsilence.app.ui.feed.engagementId
import com.unsilence.app.ui.feed.eventCardHost
import com.unsilence.app.ui.shared.EngagementSnapshot
import com.unsilence.app.ui.shared.FeedDivider
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.shared.PostActionsHost
import com.unsilence.app.ui.shared.WotImpersonationBadge
import com.unsilence.app.ui.shared.WotSearchSignal
import com.unsilence.app.ui.shared.eventFeedItems
import com.unsilence.app.ui.shared.pollActionCallbacks
import com.unsilence.app.data.relay.ImpersonationRisk
import com.unsilence.app.ui.navigation.DeepLinkTarget
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
import kotlin.math.abs

private val TAB_LABELS = listOf("All", "People", "Notes", "Tags")
private val WOT_SEARCH_SIGNAL_WIDTH = 48.dp
private const val SEARCH_TAB_SWIPE_THRESHOLD_PX = 120f

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
    val wotLookups      by viewModel.wotLookups.collectAsStateWithLifecycle()
    val feedWotDisplayMode by viewModel.feedWotDisplayMode.collectAsStateWithLifecycle()
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
    var actionsRow by remember { mutableStateOf<FeedRow?>(null) }
    val noteListState = rememberLazyListState()
    val cardWidthPx = LocalWindowInfo.current.containerSize.width

    // ── Emoji reaction picker state ─────────────────────────────────────────
    var emojiReactTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showFullEmojiPicker by remember { mutableStateOf(false) }
    val openEmojiSettings = LocalOpenEmojiSettings.current
    val pinnedShortcodes by actionsViewModel.pinnedEmojiShortcodes.collectAsStateWithLifecycle()
    val pinnedEmojis by actionsViewModel.pinnedEmojis.collectAsStateWithLifecycle()

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
    val onAuthorClickDismiss: (String) -> Unit = remember(
        keyboardController,
        focusManager,
        onAuthorClick,
    ) {
        { pubkey ->
            keyboardController?.hide()
            focusManager.clearFocus()
            onAuthorClick(pubkey)
        }
    }
    val onNoteClickDismiss: (String) -> Unit = remember(
        keyboardController,
        focusManager,
        onNoteClick,
    ) {
        { id ->
            keyboardController?.hide()
            focusManager.clearFocus()
            onNoteClick(id)
        }
    }
    val onArticleClickDismiss: (FeedRow) -> Unit = remember(keyboardController, focusManager) {
        { row ->
            keyboardController?.hide()
            focusManager.clearFocus()
            articleRow = row
        }
    }
    val onCardReactLongPress: (String, String) -> Unit = remember {
        { id, pubkey ->
            emojiReactTarget = id to pubkey
            showFullEmojiPicker = true
        }
    }
    val onCardLongPress: (FeedRow) -> Unit = remember {
        { row -> actionsRow = row }
    }
    val cardHost = remember(
        onNoteClickDismiss,
        onComment,
        onAuthorClickDismiss,
        onHashtagClick,
        onQuote,
        actionsViewModel,
        onArticleClickDismiss,
        onCardReactLongPress,
        pinnedEmojis,
        viewModel,
        wotLookups,
        feedWotDisplayMode,
        onCardLongPress,
        sensitiveMode,
    ) {
        actionsViewModel.eventCardHost(
            actions = EventCardActions(
                onNoteClick = onNoteClickDismiss,
                onComment = { _, model -> onComment(model.navigateId) },
                onAuthorClick = onAuthorClickDismiss,
                onHashtagClick = onHashtagClick,
                onQuote = onQuote,
                onArticleClick = onArticleClickDismiss,
                onReactLongPress = onCardReactLongPress,
                onLongPress = onCardLongPress,
            ),
            profileFlow = viewModel::profileFlow,
            statsFlow = viewModel::statsFlow,
            zapDetailsForEvent = viewModel::zapDetailsForEvent,
            repostPubkeysForEvent = viewModel::repostPubkeysForEvent,
            reactionsForEvent = viewModel::reactionsForEvent,
            pinnedEmojis = pinnedEmojis,
            videoScope = null,
            sensitiveMode = sensitiveMode,
            wotLookup = { pubkey -> wotLookups[pubkey] },
            feedWotDisplayMode = feedWotDisplayMode,
            onWotSubjectsVisible = viewModel::requestWotHydration,
            pollActions = actionsViewModel.pollActionCallbacks(),
        )
    }
    val onEntityClickDismiss: (DeepLinkTarget) -> Unit = { target ->
        keyboardController?.hide()
        focusManager.clearFocus()
        viewModel.openEntityTarget(target)
    }
    var pendingSearch by remember { mutableStateOf(false) }
    val activeEventResults = if (selectedTab == 3) state.tagResults else state.noteResults
    val peopleBelowEntity = remember(state.entityTarget, state.peopleResults) {
        peopleBelowEntityResult(state.entityTarget, state.peopleResults)
    }

    @OptIn(FlowPreview::class)
    LaunchedEffect(activeEventResults, peopleBelowEntity, state.entityTarget, selectedTab, cardWidthPx) {
        if (activeEventResults.isEmpty()) return@LaunchedEffect
        fun entityOffset(): Int = when {
            selectedTab == 0 && state.entityTarget != null -> 1
            selectedTab == 2 && state.entityTarget is DeepLinkTarget.Note -> 1
            selectedTab == 2 && state.entityTarget is DeepLinkTarget.Address -> 1
            else -> 0
        }
        fun warmVisibleRange(first: Int, last: Int) {
            val noteOffset = entityOffset() +
                if (selectedTab == 0) peopleBelowEntity.size.coerceAtMost(3) else 0
            val dataFirst = (first - noteOffset).coerceAtLeast(0)
            val dataLast = (last - noteOffset).coerceAtMost(activeEventResults.lastIndex)
            if (dataFirst <= dataLast) {
                actionsViewModel.warmCardWindow(
                    rows = activeEventResults,
                    first = dataFirst,
                    last = dataLast,
                    cardWidthPx = cardWidthPx,
                    hydrateEngagement = true,
                )
            }
        }

        if (cardWidthPx > 0) {
            val info = noteListState.layoutInfo
            val noteOffset = entityOffset() +
                if (selectedTab == 0) peopleBelowEntity.size.coerceAtMost(3) else 0
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
    LaunchedEffect(state.rejectedSecret) { if (state.rejectedSecret) pendingSearch = false }
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
                    .drawBehind {
                        drawLine(
                            color       = BorderFaint,
                            start       = Offset(0f, size.height),
                            end         = Offset(size.width, size.height),
                            strokeWidth = 1f,
                        )
                    },
            ) {
                TAB_LABELS.forEachIndexed { index, label ->
                    val isActive = selectedTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
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
                        contentAlignment = Alignment.Center,
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
            .pointerInput(state.hasSearched) {
                if (!state.hasSearched) return@pointerInput
                var horizontalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { horizontalDrag = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        horizontalDrag += dragAmount
                        if (abs(horizontalDrag) > SEARCH_TAB_SWIPE_THRESHOLD_PX / 2f) {
                            change.consume()
                        }
                    },
                    onDragEnd = {
                        when {
                            horizontalDrag <= -SEARCH_TAB_SWIPE_THRESHOLD_PX ->
                                selectedTab = (selectedTab + 1).coerceAtMost(TAB_LABELS.lastIndex)
                            horizontalDrag >= SEARCH_TAB_SWIPE_THRESHOLD_PX ->
                                selectedTab = (selectedTab - 1).coerceAtLeast(0)
                        }
                    },
                    onDragCancel = { horizontalDrag = 0f },
                )
            }
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

                state.rejectedSecret -> {
                    EmptyState(
                        icon = Icons.Outlined.SearchOff,
                        message = "Secret keys can't be searched",
                    )
                }

                state.loading && state.entityTarget == null && state.noteResults.isEmpty() &&
                    state.peopleResults.isEmpty() && state.tagResults.isEmpty() -> {
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
                    val hasAny = state.entityTarget != null || peopleBelowEntity.isNotEmpty() ||
                        state.noteResults.isNotEmpty()
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
                        LazyColumn(state = noteListState, modifier = Modifier.fillMaxSize()) {
                            state.entityTarget?.let { target ->
                                item(key = "entity:${entityResultKey(target)}") {
                                    SearchEntityResultRow(
                                        target = target,
                                        profile = state.entityProfile,
                                        wotLookup = (target as? DeepLinkTarget.Profile)?.let {
                                            wotLookups[it.pubkey]
                                        },
                                        onClick = { onEntityClickDismiss(target) },
                                    )
                                    FeedDivider()
                                }
                            }
                            if (peopleBelowEntity.isNotEmpty()) {
                                items(peopleBelowEntity.take(3), key = { it.pubkey }) { user ->
                                    ProfileCard(
                                        user = user,
                                        wotLookup = wotLookups[user.pubkey],
                                        impersonationRisk = state.impersonationRisks[user.pubkey],
                                        onClick = { onAuthorClickDismiss(user.pubkey) },
                                    )
                                    FeedDivider()
                                }
                            }
                            eventFeedItems(
                                events = state.noteResults,
                                engagement = engagement,
                                host = cardHost,
                                role = CardRole.Search,
                            )
                        }
                    }
                }

                selectedTab == 1 -> {
                    // People tab
                    val profileTarget = state.entityTarget as? DeepLinkTarget.Profile
                    if (peopleBelowEntity.isEmpty() && profileTarget == null) {
                        EmptyState(
                            icon    = Icons.Outlined.SearchOff,
                            message = "No results for \u201c${state.query}\u201d",
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            profileTarget?.let { target ->
                                item(key = "entity:${entityResultKey(target)}") {
                                    SearchEntityResultRow(
                                        target = target,
                                        profile = state.entityProfile,
                                        wotLookup = wotLookups[target.pubkey],
                                        onClick = { onEntityClickDismiss(target) },
                                    )
                                    FeedDivider()
                                }
                            }
                            items(peopleBelowEntity, key = { it.pubkey }) { user ->
                                ProfileCard(
                                    user = user,
                                    wotLookup = wotLookups[user.pubkey],
                                    impersonationRisk = state.impersonationRisks[user.pubkey],
                                    onClick = { onAuthorClickDismiss(user.pubkey) },
                                )
                                FeedDivider()
                            }
                        }
                    }
                }

                else -> {
                    // Notes (2) / Tags (3)
                    val results = if (selectedTab == 3) state.tagResults else state.noteResults
                    val noteTarget = state.entityTarget.takeIf {
                        selectedTab == 2 && (it is DeepLinkTarget.Note || it is DeepLinkTarget.Address)
                    }
                    if (results.isEmpty() && noteTarget == null) {
                        EmptyState(
                            icon    = Icons.Outlined.SearchOff,
                            message = "No results for \u201c${state.query}\u201d",
                        )
                    } else {
                        val engagement = rememberEngagement(
                            reactedIds, repostedIds, zappedIds, isNwcConfigured,
                            zapLoadingIds, optimisticSats, zapFlash,
                        )
                        LazyColumn(state = noteListState, modifier = Modifier.fillMaxSize()) {
                            noteTarget?.let { target ->
                                item(key = "entity:${entityResultKey(target)}") {
                                    SearchEntityResultRow(
                                        target = target,
                                        profile = null,
                                        wotLookup = null,
                                        onClick = { onEntityClickDismiss(target) },
                                    )
                                    FeedDivider()
                                }
                            }
                            eventFeedItems(
                                events = results,
                                engagement = engagement,
                                host = cardHost,
                                role = CardRole.Search,
                            )
                        }
                    }
                }
            }
        }
    }

    articleRow?.let { row ->
        // Effective engagement target (kind-6/16 reposts → original event).
        val model = remember(row.id) {
            actionsViewModel.getEventModel(row.id) ?: row.toEventModel()
        }
        ArticleReaderScreen(
            row             = row,
            model           = model,
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

    PostActionsHost(
        row = actionsRow,
        profileFlow = viewModel::profileFlow,
        canDelete = { row -> actionsViewModel.isOwnPubkey(row.pubkey) },
        onMuteUser = actionsViewModel::muteUser,
        onReport = { row, type -> actionsViewModel.reportEvent(row.id, row.pubkey, type) },
        onDelete = { row -> actionsViewModel.deleteEvent(row.id, row.pubkey, row.relayUrl) },
        eventModelProvider = actionsViewModel::getEventModel,
        relayProvenance = actionsViewModel::relayProvenance,
        onDismiss = { actionsRow = null },
    )

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

private fun entityResultKey(target: DeepLinkTarget): String = when (target) {
    is DeepLinkTarget.Profile -> "profile:${target.pubkey}"
    is DeepLinkTarget.Note -> "note:${target.eventId}"
    is DeepLinkTarget.Address -> "address:${target.coordinate}"
}

@Composable
private fun SearchEntityResultRow(
    target: DeepLinkTarget,
    profile: UserEntity?,
    wotLookup: WotLookup?,
    onClick: () -> Unit,
) {
    if (target is DeepLinkTarget.Profile) {
        ProfileCard(
            user = profile?.takeIf { it.pubkey.equals(target.pubkey, ignoreCase = true) }
                ?: UserEntity(pubkey = target.pubkey),
            wotLookup = wotLookup,
            impersonationRisk = null,
            onClick = onClick,
        )
        return
    }

    val identity = when (target) {
        is DeepLinkTarget.Note -> target.eventId
        is DeepLinkTarget.Address -> target.coordinate
        is DeepLinkTarget.Profile -> error("Handled above")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(Sizing.avatar + 8.dp)
                .clip(CircleShape)
                .background(BrandSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = Brand,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(Spacing.small))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Open note",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = AppType.body,
            )
            Text(
                text = identity,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = AppType.footnote,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Open note",
            tint = Text3,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ProfileCard(
    user: UserEntity,
    wotLookup: WotLookup?,
    impersonationRisk: ImpersonationRisk?,
    onClick: () -> Unit,
) {
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
            val title = displayName ?: remember(user.pubkey) { shortNpub(user.pubkey) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text       = title,
                    color      = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = AppType.body,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = if (impersonationRisk != null) {
                        Modifier.weight(1f, fill = false)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                )
                if (impersonationRisk != null) {
                    Spacer(Modifier.width(Spacing.small))
                    WotImpersonationBadge(
                        risk = impersonationRisk,
                        showLabel = false,
                    )
                }
            }
            if (displayName != null) {
                Text(
                    text     = remember(user.pubkey) { shortNpub(user.pubkey) },
                    color    = TextSecondary,
                    fontSize = AppType.footnote,
                )
            }
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

        if (wotLookup is WotLookup.Scored || wotLookup == WotLookup.Absent) {
            Spacer(Modifier.width(Spacing.small))
            Box(
                modifier = Modifier.width(WOT_SEARCH_SIGNAL_WIDTH),
                contentAlignment = Alignment.CenterEnd,
            ) {
                WotSearchSignal(lookup = wotLookup)
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
