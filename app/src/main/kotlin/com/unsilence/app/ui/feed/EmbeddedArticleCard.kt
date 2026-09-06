package com.unsilence.app.ui.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.memory.toEventModel
import com.unsilence.app.data.model.Segment
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.shared.EventEngagementSnapshot

/**
 * Renders a quoted/embedded long-form article (`naddr` or an `nevent` that
 * resolves to kind-30023) as the CANONICAL article card — the exact
 * `EventCard(CardRole.Article)` → `ArticleLayout` used in the feed. It resolves
 * the article by coordinate through the screen's shared [EventCardHost].
 *
 * Note and author navigation come from the host (in the article reader these
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
    segment: Segment.QuoteAddress,
    host: EventCardHost,
    nestDepth: Int,
    modifier: Modifier,
) {
    val coord = remember(segment.author, segment.dTag) {
        "30023:${segment.author}:${segment.dTag}"
    }
    val rowFlow = remember(coord, host.services.articleRowFlow) {
        host.services.articleRowFlow(coord)
    }
    val row by rowFlow.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(coord) {
        host.services.ensureArticle(
            coord,
            segment.author,
            segment.dTag,
            segment.hints,
        )
    }

    val r = row
    if (r == null) {
        // Loading / unavailable → the compact stub (resolves the author at least).
        AddressChip(
            segment = segment,
            host = host,
            modifier = modifier,
        )
        return
    }

    val model = remember(r.id) { host.services.lookupModel(r.id) ?: r.toEventModel() }
    val isTopLevel = nestDepth == 0

    // A top-level article may resolve after ensureArticle's coordinate effect
    // returns. Hydrate from the resolved row so cached and fetched articles both
    // load complete engagement. Embedded hosts deliberately skip this fetch.
    if (isTopLevel) {
        LaunchedEffect(r.id) { host.services.hydrateEngagement(listOf(r)) }
    }

    val articleHost = remember(host, isTopLevel) {
        host.copy(
            actions = host.actions.copy(
                onComment = { _, articleModel -> host.actions.onNoteClick(articleModel.navigateId) },
                onArticleClick = { articleRow -> host.actions.onNoteClick(articleRow.id) },
            ),
            surface = if (isTopLevel) {
                host.surface
            } else {
                host.surface.copy(
                    statsFlow = null,
                    zapDetailsForEvent = null,
                    repostPubkeysForEvent = null,
                    reactionsForEvent = null,
                )
            },
        )
    }

    EventCard(
        model = model,
        row = r,
        role = if (isTopLevel) CardRole.Article else CardRole.EmbeddedArticle,
        engagement = EventEngagementSnapshot(
            isNwcConfigured = host.services.isNwcConfigured(),
        ),
        host = articleHost,
        modifier = modifier,
    )
}
