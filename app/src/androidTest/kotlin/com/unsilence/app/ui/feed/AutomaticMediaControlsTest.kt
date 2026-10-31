package com.unsilence.app.ui.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.unsilence.app.data.media.MediaLoadPermissions
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.model.VideoRenderModel
import com.unsilence.app.data.model.markdown.MarkdownDocument
import com.unsilence.app.data.model.markdown.MdBlock
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.domain.model.AutomaticMediaMode
import com.unsilence.app.ui.markdown.MarkdownContent
import com.unsilence.app.ui.settings.MediaDataSettingsContent
import com.unsilence.app.domain.model.AutomaticMediaPreferences
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import com.unsilence.app.ui.shared.LocalMediaLoadPermissions
import com.unsilence.app.ui.theme.UnsilenceTheme
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual rendering/request boundaries with an offline Coil interceptor and no account. */
@RunWith(AndroidJUnit4::class)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class AutomaticMediaControlsTest {
    @get:Rule val compose = createComposeRule()
    private val images = AtomicInteger()
    private val metadata = AtomicInteger()
    private lateinit var previous: ImageLoader
    private lateinit var offline: ImageLoader
    private val permissions = mutableStateOf(MediaLoadPermissions(false, false))
    private val url = mutableStateOf("https://example.com/image.png")

    @Before fun intercept() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        previous = SingletonImageLoader.get(context)
        offline = ImageLoader.Builder(context).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                    images.incrementAndGet()
                    return ErrorResult(null, chain.request, IOException("Offline fixture"))
                }
            })
        }.build()
        SingletonImageLoader.setUnsafe(offline)
    }
    @After fun restore() { SingletonImageLoader.setUnsafe(previous); offline.shutdown() }

    @Test fun blockedContentImageLoadsOnlyAfterTap() {
        render { EventMediaGrid(listOf(Segment.Image(url.value, 1f))) }
        compose.onNodeWithText("Load image").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, images.get()) }
        compose.onNodeWithText("Load image").performClick()
        compose.waitUntil { images.get() == 1 }
        compose.onNodeWithText("Load image").assertDoesNotExist()
    }

    @Test fun imageAlreadyShownSurvivesDowngradeWhileNewContentIsBlocked() {
        permissions.value = MediaLoadPermissions()
        render { EventMediaGrid(listOf(Segment.Image(url.value, 1f))) }
        compose.waitUntil { images.get() == 1 }
        compose.runOnIdle { permissions.value = MediaLoadPermissions(false, false) }
        compose.onNodeWithText("Load image").assertDoesNotExist()
        compose.runOnIdle { url.value = "https://example.com/new.png" }
        compose.onNodeWithText("Load image").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, images.get()) }
    }

    @Test fun articleImagesUseTheSameTapToLoadPolicy() {
        render { MarkdownContent(MarkdownDocument(listOf(MdBlock.Image(url.value, "Fixture"))), onHashtagClick = {}) }
        compose.onNodeWithText("Load image").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, images.get()) }
        compose.onNodeWithText("Load image").performClick()
        compose.waitUntil { images.get() == 1 }
    }

    @Test fun postersDoNotRequestUntilAllowedAndSurviveDowngrade() {
        render {
            VideoThumbnailImage(VideoRenderModel("https://example.com/video.mp4", 1f, url.value, 100, 100),
                thumbnailCache = null, modifier = Modifier.testTag("poster"))
        }
        compose.onNodeWithTag("poster").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, images.get()); permissions.value = MediaLoadPermissions() }
        compose.waitUntil { images.get() == 1 }
        compose.onNodeWithTag("poster").assertExists()
        compose.runOnIdle { permissions.value = MediaLoadPermissions(false, false) }
        compose.onNodeWithTag("poster").assertExists()
        compose.runOnIdle { url.value = "https://example.com/other-poster.png" }
        // The same video retains its admission, including its poster replacement.
        compose.waitUntil { images.get() == 2 }
    }

    @Test fun previewTapLoadsMetadataAndThumbnailIndependentlyOfImageSetting() {
        val opened = mutableListOf<String>()
        render {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened += uri }
            }) {
                OgPreviewCard("https://example.com/article", fetchOgMetadata = {
                    metadata.incrementAndGet()
                    OgMetadata("Fixture title", null, "https://example.com/preview.png", "Example", it)
                }, hasCachedOgMetadata = { true }, modifier = Modifier.testTag("preview"))
            }
        }
        compose.onNodeWithText("Load link preview").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, metadata.get()); assertEquals(0, images.get()); assertTrue(opened.isEmpty()) }
        compose.onNodeWithText("Load link preview").performClick()
        compose.waitUntil { metadata.get() == 1 && images.get() == 1 }
        compose.onNodeWithText("Fixture title").assertIsDisplayed()
        compose.onNodeWithTag("preview").performClick()
        compose.runOnIdle { assertEquals(listOf("https://example.com/article"), opened) }
    }

    @Test fun blockedMinimalCardHasNoFaviconButKeepsOriginalNavigation() {
        var opened = false
        render { MinimalLinkCard("https://example.com/page", onClick = { opened = true }, modifier = Modifier.testTag("minimal")) }
        compose.onNodeWithTag("minimal").performClick()
        compose.runOnIdle { assertTrue(opened); assertEquals(0, images.get()) }
    }

    @Test fun previewAlreadyShownSurvivesDowngradeAndNewUrlNeedsConsent() {
        permissions.value = MediaLoadPermissions()
        render {
            OgPreviewCard(url.value, fetchOgMetadata = {
                metadata.incrementAndGet(); OgMetadata("Existing preview", null, null, "Example", it)
            }, hasCachedOgMetadata = { true })
        }
        compose.onNodeWithText("Existing preview").assertIsDisplayed()
        compose.runOnIdle { permissions.value = MediaLoadPermissions(false, false) }
        compose.onNodeWithText("Existing preview").assertIsDisplayed()
        compose.runOnIdle { url.value = "https://example.com/new" }
        compose.onNodeWithText("Load link preview").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, metadata.get()) }
    }

    @Test fun mediaPageHasThreeIndependentSegmentedChoices() {
        val current = mutableStateOf(AutomaticMediaPreferences())
        render {
            MediaDataSettingsContent(current.value,
                onVideoMode = { current.value = current.value.copy(video = it) },
                onImageMode = { current.value = current.value.copy(images = it) },
                onPreviewMode = { current.value = current.value.copy(previews = it) })
        }
        compose.onNodeWithText("Media & data").assertIsDisplayed()
        compose.onAllNodesWithText("Always").assertCountEquals(3)
        compose.onAllNodesWithText("Never")[0].performClick()
        compose.runOnIdle { assertEquals(AutomaticMediaPreferences(video = AutomaticMediaMode.NEVER), current.value) }
        compose.onAllNodesWithText("Unmetered only")[1].performClick()
        compose.onAllNodesWithText("Never")[2].performClick()
        compose.runOnIdle {
            assertEquals(AutomaticMediaPreferences(AutomaticMediaMode.NEVER, AutomaticMediaMode.UNMETERED_ONLY,
                AutomaticMediaMode.NEVER), current.value)
        }
    }

    @Test fun mediaOptionsRemainReadableAtLargerFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
                UnsilenceTheme {
                    Box(Modifier.width(360.dp)) {
                        MediaDataSettingsContent(AutomaticMediaPreferences(), {}, {}, {})
                    }
                }
            }
        }
        compose.onAllNodesWithText("Unmetered only").assertCountEquals(3)
        repeat(3) { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onAllNodesWithText("Unmetered only")[index]
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.size)
            assertFalse(layouts.single().hasVisualOverflow)
            assertTrue(layouts.single().lineCount <= 2)
        }
    }

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalMediaLoadPermissions provides permissions.value) {
                UnsilenceTheme { Box(Modifier.width(320.dp)) { content() } }
            }
        }
        compose.waitForIdle()
    }
}
