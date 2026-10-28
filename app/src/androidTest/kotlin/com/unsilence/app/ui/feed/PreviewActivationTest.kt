package com.unsilence.app.ui.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.ui.theme.UnsilenceTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Actual cards, offline metadata/images and a recording URI handler. */
@RunWith(AndroidJUnit4::class)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class PreviewActivationTest {
    @get:Rule val compose = createComposeRule()
    private val metadataRequests = AtomicInteger()
    private val imageRequests = AtomicInteger()
    private val opened = mutableListOf<String>()
    private lateinit var previousImages: ImageLoader
    private lateinit var offlineImages: ImageLoader
    private val rejected = listOf(
        "https://0x7f.1/path", "https://127.1", "https://0177.0.0.1", "https://2130706433",
        "https:///empty", "https://", "https:opaque", "https://user@/path",
        "https://example.com:", "https://example.com:99999", "https://example.com:-1",
        "https://[invalid]/", "https://[::1", "https://example.com/has space",
        "https://example.com/\npath", "https://example.com/\u0000path",
        "nostrsigner:fixture", "//example.com", "/relative",
    )

    @Before fun interceptAllImagesWithoutNetwork() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        previousImages = SingletonImageLoader.get(context)
        offlineImages = ImageLoader.Builder(context).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                    imageRequests.incrementAndGet()
                    return ErrorResult(null, chain.request, IOException("Offline fixture"))
                }
            })
        }.build()
        SingletonImageLoader.setUnsafe(offlineImages)
    }

    @After fun restoreImages() {
        SingletonImageLoader.setUnsafe(previousImages)
        offlineImages.shutdown()
    }

    @Test fun rejectedOgCardsDoNotComposeFetchOrActivate() = checkRejected(minimal = false)
    @Test fun rejectedMinimalCardsDoNotComposeFetchFaviconsOrActivate() = checkRejected(minimal = true)

    private fun checkRejected(minimal: Boolean) {
        val selected = render(rejected.first(), minimal)
        for (url in rejected) {
            compose.runOnIdle { selected.value = url }
            compose.waitForIdle()
            compose.onNodeWithTag("preview").assertDoesNotExist()
            compose.onAllNodes(hasClickAction()).assertCountEquals(0)
            compose.runOnIdle {
                assertEquals("metadata for $url", 0, metadataRequests.get())
                assertEquals("images/favicons for $url", 0, imageRequests.get())
                assertEquals(emptyList<String>(), opened)
            }
        }
    }

    @Test fun changingAcceptedUrlToRejectedRemovesCardAndClickAction() {
        val url = "https://example.com/path?q=1#part"
        val selected = render(url, minimal = false)
        compose.onNodeWithTag("preview").performClick()
        compose.runOnIdle {
            assertEquals(listOf(url), opened)
            assertEquals(1, metadataRequests.get())
            selected.value = rejected.first()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("preview").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.runOnIdle { assertEquals(1, metadataRequests.get()) }
    }

    private fun render(initialUrl: String, minimal: Boolean) = mutableStateOf(initialUrl).also { selected ->
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened += uri }
            }) {
                UnsilenceTheme {
                    Box(Modifier.width(320.dp)) {
                        val url = selected.value
                        if (minimal) MinimalLinkCard(url, onClick = { opened += url }, modifier = Modifier.testTag("preview"))
                        else OgPreviewCard(url, fetchOgMetadata = {
                            metadataRequests.incrementAndGet()
                            OgMetadata("Fixture title", null, null, "Fixture", url)
                        }, hasCachedOgMetadata = { true }, modifier = Modifier.testTag("preview"))
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
