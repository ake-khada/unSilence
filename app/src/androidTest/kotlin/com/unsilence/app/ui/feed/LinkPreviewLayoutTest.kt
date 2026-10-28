package com.unsilence.app.ui.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.unsilence.app.data.model.ContentParser
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Offline fixtures: the real renderer, without accounts, relay writes or payments. */
@RunWith(AndroidJUnit4::class)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class LinkPreviewLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val quoteLookups = AtomicInteger()
    private val previewFetches = AtomicInteger()
    private lateinit var previousImageLoader: ImageLoader
    private lateinit var fixtureImageLoader: ImageLoader

    @Before fun useOfflineImages() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        previousImageLoader = SingletonImageLoader.get(context)
        fixtureImageLoader = ImageLoader.Builder(context).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                    ErrorResult(null, chain.request, IOException("Offline image fixture"))
            })
        }.build()
        SingletonImageLoader.setUnsafe(fixtureImageLoader)
    }

    @After fun restoreImages() {
        SingletonImageLoader.setUnsafe(previousImageLoader)
        fixtureImageLoader.shutdown()
    }

    @Test fun standalonePreviewStaysBetweenParagraphs() {
        render("Introduction\n\nhttps://example.test/paintings\n\nSelection details")
        val before = compose.onNodeWithText("Introduction").getUnclippedBoundsInRoot()
        val preview = compose.onNodeWithText("Preview title", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val after = compose.onNodeWithText("Selection details").getUnclippedBoundsInRoot()
        assertTrue("Preview moved below the following paragraph", before.bottom <= preview.top && preview.bottom < after.top)
        assertTrue("Blank separator lines survived before the preview", preview.top - before.bottom <= 20.dp)
    }

    @Test fun inlineSentenceKeepsItsClickableUrl() {
        val sentence = "Read https://example.test/paintings for details."
        render(sentence)
        // Raw links use the accepted compact label, while retaining the full destination.
        val node = compose.onNodeWithText("Read example.test/paintings for details.")
        node.assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val text = layouts.single().layoutInput.text
        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals("https://example.test/paintings", (link.item as LinkAnnotation.Url).url)
        assertEquals("example.test/paintings", text.subSequence(link.start, link.end).text)
        compose.onNodeWithText("Preview title", useUnmergedTree = true).assertExists()
    }

    @Test fun largeFontStillKeepsPreviewInSourceOrder() {
        render("Introduction\n\nhttps://example.test/paintings\n\nDetails", fontScale = 2f)
        val before = compose.onNodeWithText("Introduction").getUnclippedBoundsInRoot()
        val preview = compose.onNodeWithText("Preview title", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val after = compose.onNodeWithText("Details").getUnclippedBoundsInRoot()
        assertTrue(before.bottom <= preview.top && preview.bottom < after.top)
    }

    @Test fun splitEmbeddedTextSharesOneSixLineBudgetAndHidesQuoteTail() {
        val before = "Before one\nBefore two\nBefore three"
        val after = "After one\nAfter two\nAfter three\nAfter four"
        render("$before\n\nhttps://example.test/paintings\n\n$after", role = CardRole.Embedded, withQuote = true)
        val layouts = mutableListOf<TextLayoutResult>()
        for (text in listOf(before, after)) {
            compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        }
        assertEquals(6, layouts.sumOf { it.lineCount })
        assertTrue(layouts.last().hasVisualOverflow)
        compose.runOnIdle { assertEquals(0, quoteLookups.get()) }
    }

    @Test fun longFollowingTextDoesNotHydrateHiddenQuoteUntilExpanded() {
        render("Intro\n\nhttps://example.test/paintings\n\n" + "Following line\n".repeat(80), withQuote = true)
        compose.onNodeWithText("Preview title", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Show more").assertExists()
        compose.runOnIdle { assertEquals(0, quoteLookups.get()) }
        compose.onNodeWithText("Show more").performScrollTo().performClick()
        compose.waitUntil { quoteLookups.get() > 0 }
        compose.runOnIdle { assertEquals("Expanding repeated the cached preview request", 1, previewFetches.get()) }
    }

    @Test fun longPrecedingTextDoesNotFetchHiddenPreviewUntilExpanded() {
        render("Preceding line\n".repeat(80) + "\nhttps://example.test/paintings\n\nAfter")
        compose.onNodeWithText("Show more").assertExists()
        compose.runOnIdle { assertEquals(0, previewFetches.get()) }
        compose.onNodeWithText("Show more").performScrollTo().performClick()
        compose.waitUntil { previewFetches.get() == 1 }
        compose.onNodeWithText("After").assertExists()
    }

    @Test fun delayedAndMissingMetadataKeepFallbackPositionAndGutters() {
        val result = CompletableDeferred<OgMetadata?>()
        render("Intro\n\nhttps://example.test/paintings\n\nAfter", metadata = { result.await() })
        fun assertFallback() {
            val before = compose.onNodeWithText("Intro").getUnclippedBoundsInRoot()
            val card = compose.onNodeWithText("example.test").getUnclippedBoundsInRoot()
            val after = compose.onNodeWithText("After").getUnclippedBoundsInRoot()
            assertEquals(before.left, card.left)
            assertEquals(before.right, card.right)
            assertTrue(before.bottom <= card.top && card.bottom <= after.top)
        }
        assertFallback()
        result.complete(null)
        compose.waitForIdle()
        assertFallback()
        compose.runOnIdle { assertEquals(1, previewFetches.get()) }
    }

    @Test fun bareUrlStillRendersOnePreview() {
        render("https://example.test/paintings")
        compose.onNodeWithText("Preview title", useUnmergedTree = true).assertExists()
        compose.runOnIdle { assertEquals(1, previewFetches.get()) }
    }

    private fun render(
        content: String,
        role: CardRole = CardRole.Feed,
        fontScale: Float = 1f,
        withQuote: Boolean = false,
        metadata: suspend (String) -> OgMetadata? = { url -> OgMetadata("Preview title", null, null, "Preview site", url) },
    ) {
        val parsed = ContentParser.parse(
            id = "a".repeat(64), pubkey = "b".repeat(64), kind = 1,
            content = content, tagsJson = "[]", createdAt = 1L, relayUrl = "",
            replyToId = null, rootId = null, hasContentWarning = false,
            contentWarningReason = null,
        )
        val model = if (withQuote) parsed.copy(
            segments = parsed.segments + Segment.QuoteEvent("c".repeat(64), emptyList()),
        ) else parsed
        val host = previewEventCardHost(EventCardServices(
            react = { _, _, _, _ -> }, repost = { _, _, _ -> }, zap = { _, _, _, _ -> },
            saveNwcUri = {}, isNwcConfigured = { false }, lookupProfile = { _, _ -> null },
            lookupEvent = { quoteLookups.incrementAndGet(); null }, lookupModel = { null },
            observeReference = { quoteLookups.incrementAndGet(); kotlinx.coroutines.flow.flowOf(ReferenceState(loading = false)) },
            fetchOgMetadata = { url -> previewFetches.incrementAndGet(); metadata(url) },
            hasCachedOgMetadata = { true },
            articleRowFlow = { flowOf(null) }, ensureArticle = { _, _, _, _ -> },
            hydrateEngagement = {}, engagementRetryRevision = MutableStateFlow(0L),
            imageDimensionCache = null, thumbnailCache = null,
        ))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                UnsilenceTheme {
                    Box(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        ContentFlow(model, role, host, model.id)
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
