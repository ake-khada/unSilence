package com.unsilence.app.ui.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.model.ContentParser
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.domain.model.ShowType
import com.unsilence.app.domain.model.quotedMediaPolicy
import com.unsilence.app.ui.shared.CardRole
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Real quote resolution/rendering with no account or external image/preview requests. */
@RunWith(AndroidJUnit4::class)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class QuotedMediaFilterTest {
    @get:Rule val compose = createComposeRule()
    private val requests = CopyOnWriteArrayList<String>()
    private val opened = mutableListOf<String>()
    private val showTypes = mutableStateOf(setOf(ShowType.TEXT))
    private val reference = MutableStateFlow(ReferenceState<EventEntity>())
    private lateinit var previous: ImageLoader
    private lateinit var offline: ImageLoader
    private val quoteId = "c".repeat(64)
    private val image = "https://example.com/quote.png"
    private val video = "https://example.com/quote.mp4"

    @Before fun intercept() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        previous = SingletonImageLoader.get(context)
        offline = ImageLoader.Builder(context).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                    requests += chain.request.data.toString()
                    return ErrorResult(null, chain.request, IOException("Offline fixture"))
                }
            })
        }.build()
        SingletonImageLoader.setUnsafe(offline)
    }
    @After fun restore() { SingletonImageLoader.setUnsafe(previous); offline.shutdown() }

    @Test fun imageOnlyQuoteHasOneNeutralCaptionThatOpensTheQuote() {
        render("$image\nhttps://example.com/second.png")
        compose.onNodeWithText("Image").assertIsDisplayed().performClick()
        assertNoMediaOrFilterLabels()
        compose.runOnIdle { assertEquals(listOf(quoteId), opened); assertTrue(requests.isEmpty()) }
    }

    @Test fun videoOnlyQuoteHasNeutralCaptionWithoutPosterOrPlayback() {
        render(video, tags = """[["imeta","url $video","image https://example.com/poster.png"]]""")
        compose.onNodeWithText("Video").assertIsDisplayed().performClick()
        assertNoMediaOrFilterLabels()
        compose.runOnIdle { assertEquals(listOf(quoteId), opened); assertTrue(requests.isEmpty()) }
    }

    @Test fun youtubeOnlyQuoteHasNeutralVideoCaptionWithoutExternalAction() {
        render("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        compose.onNodeWithText("Video").assertIsDisplayed().performClick()
        assertNoMediaOrFilterLabels()
        compose.runOnIdle { assertEquals(listOf(quoteId), opened); assertTrue(requests.isEmpty()) }
    }

    @Test fun truncatedQuoteKeepsItsExistingNoticeInsteadOfAMediaCaption() {
        render(image, truncated = true)
        compose.onNodeWithText("Long post — truncated").assertExists()
        assertNoNeutralCaption()
        assertNoMediaOrFilterLabels()
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
    }

    @Test fun mixedMediaOnlyQuoteHasOneCaptionWhenEverythingIsExcluded() {
        render("$image\n$video")
        compose.onNodeWithText("Video").assertIsDisplayed().performClick()
        compose.onNodeWithText("Image").assertDoesNotExist()
        assertNoMediaOrFilterLabels()
        compose.runOnIdle { assertEquals(listOf(quoteId), opened); assertTrue(requests.isEmpty()) }
    }

    @Test fun wordsAfterMediaAlsoSuppressTheNeutralCaption() {
        render("$image\nQuoted words")
        compose.onNodeWithText("Quoted words", substring = true).assertIsDisplayed().performClick()
        assertNoNeutralCaption()
        assertNoMediaOrFilterLabels()
        compose.runOnIdle { assertEquals(listOf(quoteId), opened); assertTrue(requests.isEmpty()) }
    }

    @Test fun textKeepsQuoteWordsWithoutMediaOrFilterLabelsAndOpensQuotedNote() {
        render("Quoted words\n$image")
        assertNoMediaOrFilterLabels()
        compose.onNodeWithText("Quoted words", substring = true).assertIsDisplayed().performClick()
        assertNoNeutralCaption()
        compose.onNodeWithText("Quoted words", substring = true).assertExists()
        compose.onNodeWithText("Parent words", substring = true).assertExists()
        compose.runOnIdle { assertEquals(listOf(quoteId), opened); assertTrue(requests.isEmpty()) }
        assertNoMediaOrFilterLabels()
    }

    @Test fun textVideoHasNoPosterOrPlayerAndOpensQuote() {
        render("Quoted words\n$video", tags = """[["imeta","url $video","image https://example.com/poster.png"]]""")
        assertNoMediaOrFilterLabels()
        compose.onNodeWithText("Quoted words", substring = true).assertIsDisplayed().performClick()
        assertNoNeutralCaption()
        compose.onNodeWithContentDescription("Play").assertDoesNotExist()
        compose.onNodeWithContentDescription("Unmute").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(quoteId), opened); assertTrue(requests.isEmpty()) }
    }

    @Test fun textYouTubeHasNoThumbnailOrExternalAction() {
        render("Quoted words\nhttps://www.youtube.com/watch?v=dQw4w9WgXcQ")
        compose.onNodeWithContentDescription("Open YouTube video").assertDoesNotExist()
        assertNoMediaOrFilterLabels()
        compose.onNodeWithText("Quoted words", substring = true).assertIsDisplayed().performClick()
        assertNoNeutralCaption()
        compose.runOnIdle { assertEquals(listOf(quoteId), opened); assertTrue(requests.isEmpty()) }
    }

    @Test fun textAndImagesAllowsImageButNotVideoPoster() {
        showTypes.value = setOf(ShowType.TEXT, ShowType.IMAGES)
        render("$image\n$video", tags = """[["imeta","url $video","image https://example.com/poster.png"]]""")
        assertNoNeutralCaption()
        compose.onNodeWithText("Video hidden by filter").assertDoesNotExist()
        compose.onNodeWithText("Image hidden by filter").assertDoesNotExist()
        compose.waitUntil { requests.contains(image) }
        compose.runOnIdle { assertEquals(listOf(image), requests.toList()) }
    }

    @Test fun changingAllToTextRemovesMediaAndAllRestoresIt() {
        showTypes.value = setOf(ShowType.ALL)
        render(image)
        compose.waitUntil { requests.contains(image) }
        compose.onNodeWithContentDescription("Image failed to load").assertExists()
        compose.runOnIdle { showTypes.value = setOf(ShowType.TEXT); requests.clear() }
        compose.onNodeWithText("Image").assertExists()
        assertNoMediaOrFilterLabels()
        compose.runOnIdle { assertTrue(requests.isEmpty()); showTypes.value = setOf(ShowType.ALL) }
        assertNoNeutralCaption()
        compose.onNodeWithText("Image hidden by filter").assertDoesNotExist()
        compose.waitUntil { requests.contains(image) }
    }

    @Test fun lateReferenceUsesCurrentFilter() {
        render("Quoted words\n$image", late = true)
        compose.runOnIdle { reference.value = ReferenceState(event("Quoted words\n$image"), false) }
        compose.onNodeWithText("Quoted words", substring = true).assertExists()
        assertNoMediaOrFilterLabels()
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
    }

    @Test fun nestedQuotesInheritTheFilterAndNavigateToTheirOwnNote() {
        render("Nested words\n$image", nested = true)
        assertNoMediaOrFilterLabels()
        compose.onNodeWithText("Nested words", substring = true).performClick()
        compose.onNodeWithText("Middle words", substring = true).assertExists()
        compose.onNodeWithText("Nested words", substring = true).assertExists()
        compose.runOnIdle { assertEquals(listOf(quoteId), opened); assertTrue(requests.isEmpty()) }
    }

    @Test fun topLevelMediaAndQuotedPreviewRemainUnchanged() {
        render("https://example.com/guide", topLevelImage = true)
        compose.onNodeWithText("Preview title").assertExists()
        compose.waitUntil { requests.contains(image) }
        compose.onNodeWithText("Image hidden by filter").assertDoesNotExist()
    }

    private fun assertNoNeutralCaption() {
        compose.onNodeWithText("Image").assertDoesNotExist()
        compose.onNodeWithText("Video").assertDoesNotExist()
    }

    private fun assertNoMediaOrFilterLabels() {
        compose.onNodeWithText("Image hidden by filter").assertDoesNotExist()
        compose.onNodeWithText("Video hidden by filter").assertDoesNotExist()
        compose.onNodeWithContentDescription("Image failed to load").assertDoesNotExist()
        compose.onNodeWithContentDescription("Play").assertDoesNotExist()
        compose.onNodeWithContentDescription("Unmute").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open YouTube video").assertDoesNotExist()
    }

    private fun event(content: String, tags: String = "[]") = EventEntity(
        quoteId, "d".repeat(64), 1, content, 1L, tags,
    )

    private fun render(content: String, tags: String = "[]", late: Boolean = false, topLevelImage: Boolean = false, nested: Boolean = false, truncated: Boolean = false) {
        if (!late) reference.value = ReferenceState(event(content, tags), false)
        val parent = ContentParser.parse(
            id = "a".repeat(64), pubkey = "b".repeat(64), kind = 1,
            content = "Parent words" + if (topLevelImage) "\n$image" else "", tagsJson = "[]", createdAt = 1L,
            relayUrl = "", replyToId = null, rootId = null, hasContentWarning = false, contentWarningReason = null,
        ).let { it.copy(segments = it.segments + Segment.QuoteEvent(quoteId, emptyList())) }
        val middle = ContentParser.parse(
            id = "e".repeat(64), pubkey = "f".repeat(64), kind = 1, content = "Middle words",
            tagsJson = "[]", createdAt = 1L, relayUrl = "", replyToId = null, rootId = null,
            hasContentWarning = false, contentWarningReason = null,
        ).let { it.copy(segments = it.segments + Segment.QuoteEvent(quoteId, emptyList())) }
        val displayed = if (nested) parent.copy(segments = parent.segments.dropLast(1) +
            Segment.QuoteEvent(middle.id, emptyList())) else parent
        val base = previewEventCardHost(EventCardServices(
            react = { _, _, _, _ -> }, repost = { _, _, _ -> }, zap = { _, _, _, _ -> },
            saveNwcUri = {}, isNwcConfigured = { false }, lookupProfile = { _, _ -> null },
            lookupEvent = { reference.value.event }, lookupModel = {
                when {
                    it == middle.id -> middle
                    it == quoteId && truncated -> ContentParser.parse(
                        id = quoteId, pubkey = "d".repeat(64), kind = 1, content = content,
                        tagsJson = tags, createdAt = 1L, relayUrl = "", replyToId = null,
                        rootId = null, hasContentWarning = false, contentWarningReason = null,
                    ).copy(truncated = true)
                    else -> null
                }
            }, observeReference = {
                if (it.eventId == middle.id) flowOf(ReferenceState(
                    EventEntity(middle.id, middle.pubkey, 1, "Middle words", 1L, "[]"), false,
                )) else reference
            },
            fetchOgMetadata = { OgMetadata("Preview title", null, null, "Example", it) }, hasCachedOgMetadata = { true },
            articleRowFlow = { flowOf(null) }, ensureArticle = { _, _, _, _ -> }, hydrateEngagement = {},
            engagementRetryRevision = MutableStateFlow(0L), imageDimensionCache = null, thumbnailCache = null,
        ))
        compose.setContent {
            val host = base.copy(actions = base.actions.copy(onNoteClick = { opened += it }),
                surface = base.surface.copy(quotedMediaPolicy = FeedFilter(showTypes.value).quotedMediaPolicy()))
            UnsilenceTheme {
                Box(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                    ContentFlow(displayed, CardRole.Feed, host, parent.id)
                }
            }
        }
        compose.waitForIdle()
    }
}
