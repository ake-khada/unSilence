package com.unsilence.app.ui.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.data.model.ContentParser
import com.unsilence.app.data.model.Segment
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Device-rendered tests; no account, relay writes, or production-app data needed. */
@RunWith(AndroidJUnit4::class)
class FeedGeometryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longTextHasFinalFirstFrameHeightAndDoesNotHydrateHiddenQuote() = longCard(1f, false)
    @Test fun largeFontBlockquoteHasFinalFirstFrameHeight() = longCard(2f, true)

    private fun longCard(fontScale: Float, blockquote: Boolean) {
        val lookups = AtomicInteger()
        val heights = mutableListOf<Int>()
        val text = "A harmless long note for geometry validation.\n".repeat(80)
        val base = ContentParser.parse(
            id = "a".repeat(64), pubkey = "b".repeat(64), kind = 1,
            content = text, tagsJson = "[]", createdAt = 1L, relayUrl = "",
            replyToId = null, rootId = null, hasContentWarning = false,
            contentWarningReason = null,
        )
        val first = if (blockquote) Segment.BlockQuote(listOf(Segment.Text(text))) else Segment.Text(text)
        val model = base.copy(segments = listOf(first, Segment.QuoteEvent("c".repeat(64), emptyList())))
        val host = previewEventCardHost(EventCardServices(
            react = { _, _, _, _ -> }, repost = { _, _, _ -> }, zap = { _, _, _, _ -> },
            saveNwcUri = {}, isNwcConfigured = { false }, lookupProfile = { _, _ -> null },
            lookupEvent = { lookups.incrementAndGet(); null }, lookupModel = { null },
            fetchOgMetadata = { null }, hasCachedOgMetadata = { false },
            articleRowFlow = { flowOf(null) }, ensureArticle = { _, _, _, _ -> },
            hydrateEngagement = {}, engagementRetryRevision = MutableStateFlow(0L),
            imageDimensionCache = null, thumbnailCache = null,
        ))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                UnsilenceTheme {
                    Box(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        ContentFlow(model, CardRole.Feed, host, model.id,
                            modifier = Modifier.onSizeChanged { heights += it.height })
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Show more").assertExists()
        compose.runOnIdle {
            assertTrue("No measured card", heights.isNotEmpty())
            assertEquals("Card changed height after its first layout", 1, heights.distinct().size)
            assertEquals("Collapsed tail hydrated before reveal", 0, lookups.get())
        }
        compose.onNodeWithText("Show more").performScrollTo().performClick()
        compose.waitUntil { lookups.get() > 0 }
        compose.onNodeWithText("Show less").assertExists()
    }
}
