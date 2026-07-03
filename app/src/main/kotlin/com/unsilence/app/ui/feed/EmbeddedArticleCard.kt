package com.unsilence.app.ui.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.memory.toEventModel
import com.unsilence.app.data.model.Segment
import com.unsilence.app.ui.common.LocalAppSessionKey
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.shared.EventEngagementSnapshot

/**
 * Renders a quoted/embedded long-form article (`naddr` or an `nevent` that
 * resolves to kind-30023) as the CANONICAL article card — the exact
 * `EventCard(CardRole.Article)` → `ArticleLayout` used in the feed, action bar
 * included. Self-contained: it resolves the article by coordinate and sources
 * its providers/actions from its own VMs, so the action bar targets the QUOTED
 * article's own engagement id (never the parent note/comment's).
 *
 * [onNoteClick]/[onAuthorClick] come from the host (in the article reader these
 * are the close-then-navigate wrappers, so taps don't open behind the reader).
 * Beyond [nestDepth] 0 it falls back to the compact [AddressChip] to avoid
 * rendering article cards inside article cards.
 */
@Composable
internal fun EmbeddedArticleCard(
    coord: String,
    author: String,
    dTag: String,
    hints: List<String>,
    onNoteClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    nestDepth: Int = 0,
    sensitiveMode: com.unsilence.app.data.memory.SensitiveContentMode =
        com.unsilence.app.data.memory.SensitiveContentMode.SHOW,
    modifier: Modifier = Modifier,
) {
    if (nestDepth >= 1) {
        AddressChip(
            segment     = Segment.QuoteAddress(30023, author, dTag, hints),
            onNoteClick = onNoteClick,
            modifier    = modifier,
        )
        return
    }

    val sessionKey = LocalAppSessionKey.current
    val actionsVm: NoteActionsViewModel = hiltViewModel(key = "note-actions-$sessionKey")
    val providerVm: ArticleReaderViewModel = hiltViewModel(key = "article-reader-$sessionKey")
    val row by providerVm.articleRowFlow(coord).collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(coord) { providerVm.ensureArticle(coord, author, dTag, hints) }

    val r = row
    if (r == null) {
        // Loading / unavailable → the compact stub (resolves the author at least).
        AddressChip(
            segment       = Segment.QuoteAddress(30023, author, dTag, hints),
            onNoteClick   = onNoteClick,
            lookupProfile = actionsVm::lookupProfile,
            modifier      = modifier,
        )
        return
    }

    val model = remember(r.id) { actionsVm.getEventModel(r.id) ?: r.toEventModel() }
    EventCard(
        model                 = model,
        row                   = r,
        role                  = CardRole.Article,
        engagement            = EventEngagementSnapshot(isNwcConfigured = actionsVm.isNwcConfigured),
        onNoteClick           = onNoteClick,
        onComment             = { onNoteClick(model.navigateId) },
        onAuthorClick         = onAuthorClick,
        onQuote               = {},
        onArticleClick        = { onNoteClick(it.id) },
        onReact               = { actionsVm.react(model.engagementId, model.pubkey) },
        onReactLongPress      = {},
        onReactWithEmoji      = { e -> actionsVm.react(model.engagementId, model.pubkey, ":${e.shortcode}:", e.url) },
        onRepost              = { actionsVm.repost(model.engagementId, model.pubkey, r.relayUrl) },
        onZap                 = { req -> actionsVm.zap(model.engagementId, model.pubkey, r.relayUrl, req) },
        onSaveNwcUri          = { uri -> actionsVm.saveNwcUri(uri) },
        lookupProfile         = actionsVm::lookupProfile,
        lookupEvent           = { id, h -> actionsVm.lookupEvent(id, h) },
        lookupEventWithAuthor = { id, h, pk -> actionsVm.lookupEvent(id, h, pk) },
        lookupModel           = actionsVm::getEventModel,
        fetchOgMetadata       = actionsVm::fetchOgMetadata,
        hasCachedOgMetadata   = actionsVm::hasCachedOgMetadata,
        profileFlow           = providerVm::profileFlow,
        statsFlow             = providerVm::statsFlow,
        zapDetailsForEvent    = providerVm::zapDetailsForEvent,
        repostPubkeysForEvent = providerVm::repostPubkeysForEvent,
        reactionsForEvent     = providerVm::reactionsForEvent,
        imageDimensionCache   = actionsVm.imageDimensionCache,
        thumbnailCache        = actionsVm.videoThumbnailCache,
        sensitiveMode         = sensitiveMode,
        isSensitive           = r.hasContentWarning,
        contentWarningReason  = r.contentWarningReason,
        modifier              = modifier,
    )
}
