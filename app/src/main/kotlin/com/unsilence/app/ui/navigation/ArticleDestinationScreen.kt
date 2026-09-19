package com.unsilence.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.ui.common.LocalOpenEmojiSettings
import com.unsilence.app.ui.common.LoadingScreen
import com.unsilence.app.ui.feed.ArticleReaderScreen
import com.unsilence.app.ui.feed.ArticleReaderViewModel
import com.unsilence.app.ui.feed.NoteActionsViewModel
import com.unsilence.app.ui.feed.EmojiPickerSheet
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.shared.ResumedEffect

/** One entry for all article sources; caller VMs/collectors are never kept alive by the reader. */
@Composable
internal fun ArticleDestinationScreen(
    entryId: String,
    destination: AppDestination.Article,
    onDismiss: () -> Unit,
    onNoteClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    onHashtagClick: (String) -> Unit,
    onCompose: (AppDestination.Compose) -> Unit,
    actions: NoteActionsViewModel,
    viewModel: ArticleReaderViewModel = hiltViewModel(),
) {
    val showSnackbar = LocalShowSnackbar.current
    ResumedEffect(actions) { actions.actionError.collect { showSnackbar(it) } }
    val flow = remember(viewModel, destination.eventId) { viewModel.articleFlow(destination.eventId) }
    val initial = remember(viewModel, destination.eventId) { viewModel.cachedArticle(destination.eventId) }
    val article by flow.collectAsStateWithLifecycle(initialValue = initial)
    var lookupFinished by remember(viewModel, destination.eventId) {
        mutableStateOf(viewModel.snapshotReady && initial != null)
    }
    ResumedEffect(viewModel, destination) {
        viewModel.restoreArticle(destination.eventId, destination.relayHints)
        lookupFinished = true
    }
    val content = article
    if (content == null || !lookupFinished) {
        if (lookupFinished) MissingDestination("Article is not available yet.", onDismiss)
        else LoadingScreen()
        return
    }
    val (row, model) = content
    val reacted by actions.reactedEventIds.collectAsStateWithLifecycle()
    val reposted by actions.repostedEventIds.collectAsStateWithLifecycle()
    val zapped by actions.zappedEventIds.collectAsStateWithLifecycle()
    val loading by actions.zapLoading.collectAsStateWithLifecycle()
    val optimisticSats by actions.optimisticZapSats.collectAsStateWithLifecycle()
    val flash by actions.zapFlashState.collectAsStateWithLifecycle()
    val pinned by actions.pinnedEmojis.collectAsStateWithLifecycle()
    val pinnedShortcodes by actions.pinnedEmojiShortcodes.collectAsStateWithLifecycle()
    var showEmojiPicker by remember { mutableStateOf(false) }
    val openEmojiSettings = LocalOpenEmojiSettings.current

    ArticleReaderScreen(
        entryId = entryId,
        row = row,
        model = model,
        focusedCommentId = destination.focusedCommentId,
        onDismiss = onDismiss,
        onComment = { onCompose(AppDestination.Compose(articleComment = it)) },
        onReply = { onCompose(AppDestination.Compose(replyToEventId = it)) },
        onNoteClick = onNoteClick,
        onAuthorClick = onAuthorClick,
        onHashtagClick = onHashtagClick,
        onQuote = { onCompose(AppDestination.Compose(quoteEventId = it)) },
        onReact = { actions.react(model.engagementId, model.pubkey) },
        onReactLongPress = { showEmojiPicker = true },
        pinnedEmojis = pinned,
        onReactWithEmoji = { actions.react(model.engagementId, model.pubkey, ":${it.shortcode}:", it.url) },
        onRepost = { actions.repost(model.engagementId, model.pubkey, row.relayUrl) },
        onZap = { actions.zap(model.engagementId, model.pubkey, row.relayUrl, it) },
        onSaveNwcUri = actions::saveNwcUri,
        hasReacted = model.engagementId in reacted,
        hasReposted = model.engagementId in reposted,
        hasZapped = model.engagementId in zapped,
        isNwcConfigured = actions.isNwcConfigured,
        isZapLoading = model.engagementId in loading,
        extraZapSats = optimisticSats[model.engagementId] ?: 0L,
        zapFlash = flash,
        lookupProfile = actions::lookupProfile,
        profileFlow = viewModel::profileFlow,
        statsFlow = viewModel::statsFlow,
        zapDetailsForEvent = viewModel::zapDetailsForEvent,
        repostPubkeysForEvent = viewModel::repostPubkeysForEvent,
        reactionsForEvent = viewModel::reactionsForEvent,
        articleReaderVm = viewModel,
        commentActionsVm = actions,
    )
    if (showEmojiPicker) {
        EmojiPickerSheet(
            emojis = actions.getSubscribedEmojis(),
            pinnedShortcodes = pinnedShortcodes,
            onSelect = {
                actions.react(model.engagementId, model.pubkey, ":${it.shortcode}:", it.url)
                showEmojiPicker = false
            },
            onTogglePin = actions::togglePinnedEmoji,
            onOpenSettings = { showEmojiPicker = false; openEmojiSettings() },
            onDismiss = { showEmojiPicker = false },
            categories = actions.getSubscribedEmojisBySet(),
        )
    }
}
