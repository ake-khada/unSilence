package com.unsilence.app.ui.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.data.relay.OgMetadata
import com.unsilence.app.ui.theme.UnsilenceTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real card with offline metadata and a recording UriHandler; no account or network. */
@RunWith(AndroidJUnit4::class)
class OgDestinationIdentityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun misleadingSiteNameFollowsActualDestinationHost() = checkLabel("Example News", "evil.example · Example News")
    @Test fun blankSiteNameDoesNotDuplicateHost() = checkLabel(" \t ", "evil.example")
    @Test fun absentSiteNameShowsHost() = checkLabel(null, "evil.example")
    @Test fun sameHostIgnoringCaseDoesNotDuplicateHost() = checkLabel(" EVIL.Example ", "evil.example")
    @Test fun unicodeDestinationUsesAsciiHost() = checkLabel("Apple", "xn--pple-43d.com · Apple", "https://аpple.com/path")
    @Test fun userinfoCannotReplaceDestinationHostOrPort() = checkLabel("Trusted", "evil.example:8443 · Trusted", "https://trusted.example@evil.example:8443/path?q=1")
    @Test fun explicitDefaultPortIsOmitted() = checkLabel("News", "evil.example · News", "https://evil.example:443/path")
    @Test fun bidiFormattingCannotReorderDestinationLabel() = checkLabel(
        "\u061c\u200e\u200f\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069Example News",
        "evil.example · Example News",
    )

    @Test fun numericHostPrecedesHebrewSiteNameVisually() = checkNumericHostDirection("חדשות")
    @Test fun numericHostPrecedesArabicSiteNameVisually() = checkNumericHostDirection("أخبار")

    private fun checkNumericHostDirection(siteName: String) {
        val host = "203.0.113.5"
        render("https://$host/path", siteName)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("$host · $siteName", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val hostRight = (host.indices).maxOf { layout.getBoundingBox(it).right }
        val siteStart = host.length + " · ".length
        val siteLeft = (siteStart until layout.layoutInput.text.length).minOf { layout.getBoundingBox(it).left }
        assertTrue("Numeric destination host must be visually left of the site name", hostRight <= siteLeft)
    }

    @Test fun minimalCardShowsAsciiHostAndPortWithoutChangingOriginalUrl() {
        val url = "https://аpple.com:8443/path?q=1#section"
        val opened = render(url, null, minimal = true)
        compose.onNodeWithText("xn--pple-43d.com:8443", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(url, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("preview").performClick()
        compose.runOnIdle { assertEquals(listOf(url), opened) }
    }

    @Test fun longSiteNameCannotDisplaceHostAndRemainsOneLine() {
        val expected = "evil.example · " + "Example News ".repeat(100)
        render("https://evil.example/path", "Example News ".repeat(100))
        val node = compose.onNodeWithText(expected.trimEnd(), useUnmergedTree = true)
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(1, layout.lineCount)
        assertTrue(layout.isLineEllipsized(0))
        assertTrue("Destination host clipped before site name", layout.getLineEnd(0, visibleEnd = true) >= "evil.example".length)
    }

    private fun checkLabel(siteName: String?, expected: String, url: String = "https://evil.example/path?q=1#section") {
        val opened = render(url, siteName)
        compose.onNodeWithText(expected, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Fixture title", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Fixture description", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("preview").performClick()
        compose.runOnIdle { assertEquals(listOf(url), opened) }
    }

    private fun render(url: String, siteName: String?, minimal: Boolean = false): MutableList<String> {
        val opened = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened += uri }
            }) {
                UnsilenceTheme {
                    Box(Modifier.width(320.dp)) {
                        if (minimal) MinimalLinkCard(
                            url, onClick = { opened += url }, loadFavicon = false,
                            modifier = Modifier.testTag("preview"),
                        ) else OgPreviewCard(url, fetchOgMetadata = { requested ->
                            assertEquals(url, requested)
                            OgMetadata("Fixture title", "Fixture description", null, siteName, "https://metadata.example/ignored")
                        }, hasCachedOgMetadata = { true }, modifier = Modifier.testTag("preview"))
                    }
                }
            }
        }
        compose.waitForIdle()
        return opened
    }
}
