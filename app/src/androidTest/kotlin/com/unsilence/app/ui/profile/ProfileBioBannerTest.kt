package com.unsilence.app.ui.profile

import android.graphics.Bitmap
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.request.ImageRequest
import coil3.size.Scale
import java.util.concurrent.atomic.AtomicReference
import coil3.request.crossfade
import com.unsilence.app.ui.theme.AppTextStyles
import com.unsilence.app.ui.theme.UnsilenceTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production header components, local bitmaps and fake callbacks; no external requests. */
@RunWith(AndroidJUnit4::class)
class ProfileBioBannerTest {
    @get:Rule val compose = createComposeRule()
    private val bio = mutableStateOf("Short biography")
    private val hashtags = mutableListOf<String>()

    @Test fun longBioShowsEveryLineAtExistingFontSizeAndMovesFollowingContentDown() {
        bio.value = (1..8).joinToString("\n") { "Biography line $it" }
        renderBio()
        val layout = bioLayout()
        assertEquals(8, layout.lineCount)
        assertEquals(AppTextStyles.bodyLarge.fontSize, layout.layoutInput.style.fontSize)
        assertEquals(TextAlign.Center, layout.layoutInput.style.textAlign)
        assertEquals(bio.value.length, layout.getLineEnd(7, visibleEnd = true))
        assertFalse(layout.hasVisualOverflow)
        val text = compose.onNodeWithTag("bio").getUnclippedBoundsInRoot()
        val next = compose.onNodeWithText("Following / Followers / Relays").getUnclippedBoundsInRoot()
        assertEquals(8f, (next.top - text.bottom).value, 0.5f)
        capture("bio-eight-lines", "bio")
    }

    @Test fun shortBioRemainsOneLineAndGrowsWithoutShrinkingTypography() {
        renderBio()
        assertEquals(1, bioLayout().lineCount)
        val shortBounds = compose.onNodeWithTag("bio").getUnclippedBoundsInRoot()
        val shortHeight = shortBounds.bottom - shortBounds.top
        compose.runOnIdle { bio.value = (1..7).joinToString("\n") { "Biography line $it" } }
        val layout = bioLayout()
        assertEquals(7, layout.lineCount)
        assertEquals(AppTextStyles.bodyLarge.fontSize, layout.layoutInput.style.fontSize)
        val longBounds = compose.onNodeWithTag("bio").getUnclippedBoundsInRoot()
        assertTrue(longBounds.bottom - longBounds.top > shortHeight)
    }

    @Test fun largeFontWrapsFullBioAtNarrowWidth() {
        bio.value = "A profile biography that wraps naturally at a narrow width. ".repeat(8)
        renderBio(width = 280, fontScale = 1.5f)
        val layout = bioLayout()
        assertTrue(layout.lineCount > 3)
        assertFalse(layout.didOverflowHeight)
        assertEquals(bio.value.trimEnd().length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
        assertEquals(AppTextStyles.bodyLarge.fontSize, layout.layoutInput.style.fontSize)
    }

    @Test fun hashtagAfterThirdLineKeepsExistingNavigation() {
        bio.value = "First\nSecond\nThird\nFourth\n#nostr"
        renderBio()
        val layout = bioLayout()
        val index = layout.layoutInput.text.text.indexOf("#nostr") + 2
        val glyph = layout.getBoundingBox(index)
        compose.onNodeWithTag("bio").performTouchInput {
            // ProfileBio's horizontal inset belongs to the semantics node, not the text layout.
            click(Offset(12.dp.toPx() + glyph.center.x, glyph.center.y))
        }
        compose.runOnIdle { assertEquals(listOf("nostr"), hashtags) }
    }

    @Test fun longBioScrollsWithThePageToFollowingContent() {
        val content = (1..80).joinToString("\n") { "Biography line $it" }
        compose.setContent {
            UnsilenceTheme {
                LazyColumn(Modifier.width(300.dp).height(400.dp).background(MaterialTheme.colorScheme.background).testTag("page")) {
                    item {
                        ProfileHeaderStack {
                            ProfileBio(content, null, {}, {}, Modifier.testTag("bio"))
                            Text("Following / Followers / Relays")
                        }
                    }
                    item { Text("First post") }
                }
            }
        }
        val layout = bioLayout()
        assertEquals(80, layout.lineCount)
        assertEquals(content.length, layout.getLineEnd(79, visibleEnd = true))
        compose.onNodeWithTag("page").performScrollToIndex(1)
        compose.onNodeWithText("First post").assertIsDisplayed()
        compose.onNodeWithTag("page").performScrollToIndex(0)
        compose.onNodeWithTag("bio").assertIsDisplayed()
    }

    @Test fun tallBannerFillsBothEdgesAndKeepsVerticalCenter() {
        val decodeScale = AtomicReference<Scale>()
        val bitmap = Bitmap.createBitmap(100, 400, Bitmap.Config.ARGB_8888)
        for (y in 0 until 400) for (x in 0 until 100) {
            bitmap.setPixel(x, y, when {
                y < 160 -> android.graphics.Color.BLUE
                y >= 240 -> android.graphics.Color.YELLOW
                x < 50 -> android.graphics.Color.RED
                else -> android.graphics.Color.GREEN
            })
        }
        compose.setContent {
            UnsilenceTheme {
                val context = LocalContext.current
                val request = remember {
                    ImageRequest.Builder(context).data(bitmap).crossfade(false)
                        .listener(onStart = { decodeScale.set(it.scale) }).build()
                }
                ProfileBannerImage(request, Modifier.width(300.dp).height(200.dp).testTag("banner"))
            }
        }
        compose.waitUntil(5_000) {
            val pixels = compose.onNodeWithTag("banner").captureToImage().toPixelMap()
            pixels[pixels.width / 2 - 5, pixels.height / 2].red > 0.9f
        }
        val pixels = compose.onNodeWithTag("banner").captureToImage().toPixelMap()
        // Width scaling exposes only the center band. Fit leaves black sides; top/bottom
        // alignment reveals blue/yellow instead. No stretch or horizontal crop is needed.
        assertEquals("Width filling must preserve the previous decode scale", Scale.FIT, decodeScale.get())
        capture("banner-width-center", "banner")
        for (y in listOf(2, pixels.height / 2, pixels.height - 3)) {
            assertTrue("Left edge keeps the source's red half", pixels[2, y].red > 0.9f)
            assertTrue("Right edge keeps the source's green half", pixels[pixels.width - 3, y].green > 0.9f)
            assertEquals(0f, pixels[2, y].blue, 0.01f)
            assertEquals(0f, pixels[pixels.width - 3, y].red, 0.01f)
        }
    }

    private fun capture(name: String, tag: String) {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "$name.png")
        file.outputStream().use {
            compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun renderBio(width: Int = 360, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                UnsilenceTheme {
                    Column(Modifier.width(width.dp).background(MaterialTheme.colorScheme.background)) {
                        ProfileHeaderStack {
                            ProfileBio(bio.value, null, {}, { hashtags += it }, Modifier.testTag("bio"))
                            Text("Following / Followers / Relays")
                        }
                    }
                }
            }
        }
    }

    private fun bioLayout(): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("bio").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }
}
