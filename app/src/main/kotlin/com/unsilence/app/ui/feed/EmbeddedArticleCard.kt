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
 * `EventCard(CardRole.Article)` → `ArticleLayout` used in the feed. It resolves
 * the article by coordinate and sources its providers/actions from its own VMs.
 *
 * [onNoteClick]/[onAuthorClick] come from the host (in the article reader these
 * are the close-then-navigate wrappers, so taps don't open behind the reader).
 * Every resolved depth uses the canonical author/hero/title/summary presentation.
 * Only a top-level host includes the article action bar; every embedded context
 * omits it because `ThreadParentCard` is contractually action-bar-free and its
 * fixed `nestDepth = 1` is a nesting bound rather than a quote level. Unresolved
 * articles at any depth fall back to [AddressChip]. Both resolved roles self-gate
 * through [EventCard] using the resolved article's own content warning.
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
            profileFlow   = providerVm::profileFlow,
            modifier      = modifier,
        )
        return
    }

    val model = remember(r.id) { actionsVm.getEventModel(r.id) ?: r.toEventModel() }
    val isTopLevel = nestDepth == 0

    // A top-level article may resolve after ensureArticle's coordinate effect
    // returns. Hydrate from the resolved row so cached and fetched articles both
    // load complete engagement. Embedded hosts deliberately skip this fetch.
    if (isTopLevel) {
        LaunchedEffect(r.id) { providerVm.hydrateEngagement(listOf(r)) }
    }

    val articleCard: @Composable (Modifier) -> Unit = { cardModifier ->
        EventCard(
            model                 = model,
            row                   = r,
            role                  = if (isTopLevel) CardRole.Article else CardRole.EmbeddedArticle,
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
            lookupEventReference  = actionsVm::lookupEvent,
            lookupModel           = actionsVm::getEventModel,
            fetchOgMetadata       = actionsVm::fetchOgMetadata,
            hasCachedOgMetadata   = actionsVm::hasCachedOgMetadata,
            profileFlow           = providerVm::profileFlow,
            statsFlow             = if (isTopLevel) providerVm::statsFlow else null,
            zapDetailsForEvent    = if (isTopLevel) providerVm::zapDetailsForEvent else null,
            repostPubkeysForEvent = if (isTopLevel) providerVm::repostPubkeysForEvent else null,
            reactionsForEvent     = if (isTopLevel) providerVm::reactionsForEvent else null,
            imageDimensionCache   = actionsVm.imageDimensionCache,
            thumbnailCache        = actionsVm.videoThumbnailCache,
            sensitiveMode         = sensitiveMode,
            isSensitive           = r.hasContentWarning,
            contentWarningReason  = r.contentWarningReason,
            modifier              = cardModifier,
        )
    }

    articleCard(modifier)
}
