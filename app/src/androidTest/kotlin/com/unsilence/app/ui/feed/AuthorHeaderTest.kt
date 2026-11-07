package com.unsilence.app.ui.feed

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.network.Nip05VerificationController
import com.unsilence.app.data.network.Nip05VerificationStatus
import com.unsilence.app.ui.common.LocalMinuteClock
import com.unsilence.app.ui.common.LocalNip05VerificationController
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real headers, synthetic identities and an offline verifier; no account or image fetch. */
@RunWith(AndroidJUnit4::class)
class AuthorHeaderTest {
    @get:Rule val compose = createComposeRule()
    private val author = "a".repeat(64)
    private val reposter = "b".repeat(64)
    private val now = 1_700_000_000L
    private val opened = mutableListOf<String>()
    private val requests = mutableListOf<Pair<String, String>>()

    @Test fun standardHeaderRetainsAuthorAndNoteActions() {
        render()
        compose.onNodeWithText("Original author").performClick()
        compose.onNodeWithText("1m").performClick()
        compose.runOnIdle { assertEquals(listOf(author, "note"), opened) }
    }

    @Test fun missingProfileRetainsFallbackIdentityWithoutVerificationWork() {
        render(name = null, address = null)
        compose.onNodeWithText("aaaaaa…aaaa").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(author), opened)
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun unknownAddressMakesNoVerifiedClaim() = checkAddress(Nip05VerificationStatus.UNKNOWN)
    @Test fun failedAddressMakesNoVerifiedClaim() = checkAddress(Nip05VerificationStatus.UNVERIFIED)
    @Test fun verifiedAddressKeepsBadgeAndFullAccessibleAddress() = checkAddress(Nip05VerificationStatus.VERIFIED)

    @Test fun longIdentityAtNormalWidth() = checkLayout(360, 1f, false)
    @Test fun longIdentityAtNarrowWidth() = checkLayout(280, 1f, false)
    @Test fun longIdentityWithLargeFont() = checkLayout(360, 1.5f, false)
    @Test fun longIdentityAtNarrowWidthWithLargeFont() = checkLayout(280, 1.5f, false)
    @Test fun resolvedRepostAtNormalWidth() = checkLayout(360, 1f, true)
    @Test fun resolvedRepostAtNarrowWidthWithLargeFont() = checkLayout(280, 1.5f, true)

    @Test fun repostWithoutAddressKeepsOriginalAvatarBesideOriginalIdentity() {
        render(width = 280, scale = 1.5f, address = null, repost = true)
        val avatar = compose.onNodeWithContentDescription("Open profile for Original author").fetchSemanticsNode().boundsInRoot
        val name = compose.onNodeWithText("Original author", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val repost = compose.onNodeWithContentDescription("Reposted by Reposter").fetchSemanticsNode().boundsInRoot
        assertTrue("Avatar belongs beside original author", avatar.center.y >= repost.bottom)
        assertTrue("Original identity follows avatar", avatar.right < name.left)
        assertEquals(name.bottom, avatar.bottom, 1f)
        capture("repost-missing-address-large-font")
    }

    @Test fun compactRepostHeaderKeepsNearbyAuthorsSeparatelyTappable() {
        render(repost = true)
        val attribution = compose.onNodeWithContentDescription("Reposted by Reposter").getUnclippedBoundsInRoot()
        val original = compose.onNodeWithText("Original author").getUnclippedBoundsInRoot()
        val gap = original.top - attribution.bottom
        assertTrue("Attribution must stay close without overlapping the author", gap > 0.dp && gap <= 2.dp)
        compose.onNodeWithContentDescription("Reposted by Reposter").performTouchInput { click(center) }
        compose.onNodeWithText("Original author").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(listOf(reposter, author), opened) }
    }

    @Test fun compactRepostTargetsStaySeparateAtLargeFont() {
        render(width = 280, scale = 1.5f, repost = true)
        val reposterNode = compose.onNodeWithContentDescription("Reposted by Reposter")
        val authorNode = compose.onNodeWithText("Original author")
        assertTrue(reposterNode.fetchSemanticsNode().boundsInRoot.bottom < authorNode.fetchSemanticsNode().boundsInRoot.top)
        reposterNode.performTouchInput { click(center) }
        authorNode.performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(listOf(reposter, author), opened) }
    }

    @Test fun badgeAreaOpensOriginalAuthorWithoutAddingAnotherAction() {
        render(repost = true)
        compose.onAllNodes(hasClickAction()).assertCountEquals(4)
        compose.onNodeWithContentDescription("Open profile for Original author")
            .performTouchInput { click(Offset(width - 1f, 4f)) }
        compose.runOnIdle { assertEquals(listOf(author), opened) }
        val attribution = compose.onNodeWithText("Reposter reposted", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val original = compose.onNodeWithText("Original author", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("Attribution starts at the original author's name", original.left, attribution.left, 1f)
    }

    @Test fun repostAttributionMatchesNostrAddressFontSize() {
        render(repost = true)
        val attribution = layout(compose.onNodeWithText("Reposter reposted", useUnmergedTree = true))
        val address = layout(compose.onNodeWithText("example.com", useUnmergedTree = true))
        assertEquals(address.layoutInput.style.fontSize, attribution.layoutInput.style.fontSize)
    }

    @Test fun referenceAttributionUsesTheSameCompactGap() {
        render(name = "Reposter", reference = true, address = null)
        val attribution = compose.onNodeWithText("Reposted", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val identity = compose.onNodeWithText("Reposter", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val gap = identity.top - attribution.bottom
        assertTrue("Reference attribution stays close without overlap", gap > 0.dp && gap <= 2.dp)
        capture("reference-compact")
    }

    @Test fun unresolvedReferenceKeepsReposterIdentity() {
        render(name = "Reposter", reference = true)
        val attribution = compose.onNodeWithText("Reposted", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val identity = compose.onNodeWithText("Reposter", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(identity.left, attribution.left, 1f)
        compose.onNodeWithText("Reposter").assertIsDisplayed().performClick()
        compose.onNodeWithText("Original author").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(author), opened) }
        capture("reference")
    }

    @Test fun selfRepostShowsOnlyRepostTimeNotOlderOriginal() {
        render(repost = true, selfRepost = true)
        compose.onNodeWithText("Reposted").assertIsDisplayed()
        compose.onNodeWithContentDescription("Reposted by Original author").assertHasClickAction()
        compose.onNodeWithText("2m", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.onNodeWithText("3d").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("note"), opened) }
        capture("self-repost")
    }

    @Test fun distinctRepostRetainsSingleRepostTimeAndBothAuthors() {
        render(repost = true)
        val attribution = compose.onNodeWithContentDescription("Reposted by Reposter")
        attribution.assertIsDisplayed().assertHasClickAction().performTouchInput { click(center) }
        compose.onNodeWithText("Original author").performClick()
        compose.onNodeWithContentDescription("Open profile for Original author").performClick()
        compose.onNodeWithText("2m", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("3d").assertDoesNotExist()
        val authorBounds = compose.onNodeWithText("Original author", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val attributionBounds = attribution.fetchSemanticsNode().boundsInRoot
        val repostTimeBounds = compose.onNodeWithText("2m", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(attributionBounds.bottom < authorBounds.top)
        assertTrue(authorBounds.right < repostTimeBounds.left)
        compose.onAllNodesWithText("-", useUnmergedTree = true).assertCountEquals(1)
        compose.runOnIdle { assertEquals(listOf(reposter, author, author), opened) }
    }

    @Test fun referenceRepostDoesNotFabricateAnOriginalTime() {
        render(repost = true, reference = true, selfRepost = true, name = "Wrapper author")
        compose.onNodeWithText("Reposted").assertIsDisplayed()
        compose.onNodeWithText("Wrapper author").assertIsDisplayed()
        compose.onNodeWithText("2m").assertIsDisplayed()
        compose.onNodeWithText("3d").assertDoesNotExist()
        compose.onNodeWithText("1m").assertDoesNotExist()
    }

    @Test fun hiddenTimestampsRemainHiddenOnReposts() {
        render(repost = true, showTimestamps = false)
        compose.onNodeWithText("Reposter reposted").assertIsDisplayed()
        compose.onNodeWithText("1m").assertDoesNotExist()
        compose.onNodeWithText("2m").assertDoesNotExist()
        compose.onNodeWithText("3d").assertDoesNotExist()
        compose.onNodeWithText("-", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun threadHeaderDoesNotRestoreSuppressedWrapperTime() {
        render(repost = true, showRepostTime = false)
        compose.onNodeWithText("Reposter reposted").assertIsDisplayed()
        compose.onNodeWithText("3d", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("2m").assertDoesNotExist()
    }

    @Test fun fiveActionSlotsAndChevronKeepTheirExistingBehavior() {
        val expanded = mutableStateOf(false)
        var comments = 0
        compose.setContent {
            UnsilenceTheme {
                Box(Modifier.width(360.dp)) {
                    EventActionBar(
                        noteId = "fixture", replyCount = 2, repostCount = 3, reactionCount = 4, zapTotalSats = 5,
                        hasReacted = false, hasReposted = false, hasZapped = false,
                        isNwcConfigured = false, isZapLoading = false, extraZapSats = 0, zapFlash = null,
                        drawerOpen = expanded.value, onChevronTap = { expanded.value = !expanded.value },
                        onNoteClick = {}, onComment = { comments++ }, onReact = {}, onReactLongPress = {},
                        onReactWithEmoji = {}, onRepost = {}, onQuote = {},
                        onZap = { error("No payment in this fixture") }, onSaveNwcUri = {},
                    )
                }
            }
        }
        listOf("Replies", "Reposts", "Reactions", "Zap", "Show engagement").forEach {
            compose.onNodeWithContentDescription(it).assertIsDisplayed().assertHasClickAction()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithContentDescription("Show engagement").performClick()
        compose.onNodeWithContentDescription("Hide engagement").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Show engagement").assertIsDisplayed()
        compose.onNodeWithContentDescription("Replies").performClick()
        compose.runOnIdle { assertEquals(1, comments) }
    }

    private fun checkAddress(status: Nip05VerificationStatus) {
        render(status = status)
        val prefix = if (status == Nip05VerificationStatus.VERIFIED) "Verified Nostr address: " else "Nostr address: "
        compose.onNodeWithContentDescription(prefix + "person@example.com", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(author to "person@example.com"), requests) }
    }

    private fun checkLayout(width: Int, scale: Float, repost: Boolean) {
        val name = "A deliberately long original author name"
        render(width, scale, name, "person@a-deliberately-long-address.example.com", repost = repost,
            status = Nip05VerificationStatus.VERIFIED)
        val nameNode = compose.onNodeWithText(name, useUnmergedTree = true)
        val nameLayout = layout(nameNode)
        assertEquals(1, nameLayout.lineCount)
        assertTrue(nameLayout.isLineEllipsized(0))
        val time = compose.onNodeWithText(if (repost) "2m" else "1m", useUnmergedTree = true)
        time.assertIsDisplayed()
        capture("${if (repost) "repost" else "standard"}-$width-$scale")
        val timeLayout = layout(time)
        // Text layout rounds to integer pixels; subpixel paragraph width alone is not clipping.
        assertTrue("Timestamp glyphs clipped", timeLayout.getLineRight(0) <= timeLayout.size.width + 1f)
        assertTrue("Timestamp characters omitted", timeLayout.getLineEnd(0, visibleEnd = true) == timeLayout.layoutInput.text.length)
        assertFalse(timeLayout.didOverflowHeight)
        compose.onNodeWithText("-", useUnmergedTree = true).assertIsDisplayed()
        val address = compose.onNodeWithContentDescription(
            "Verified Nostr address: person@a-deliberately-long-address.example.com", useUnmergedTree = true)
        address.assertIsDisplayed()
        val nameBounds = nameNode.fetchSemanticsNode().boundsInRoot
        val addressBounds = address.fetchSemanticsNode().boundsInRoot
        val timeBounds = time.fetchSemanticsNode().boundsInRoot
        assertTrue("Address must follow the author without overlap", nameBounds.right <= addressBounds.left)
        assertTrue("Address cannot displace timestamp", addressBounds.right <= timeBounds.left)
        assertTrue("Name/address remain on the same line", nameBounds.top < addressBounds.bottom && addressBounds.top < nameBounds.bottom)
        if (repost) {
            val attribution = compose.onNodeWithText("Reposter reposted", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(nameBounds.left, attribution.left, 1f)
        }
        capture("${if (repost) "repost" else "standard"}-$width-$scale")
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun capture(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "author-header")
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use {
            compose.onNodeWithTag("header").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun render(
        width: Int = 360,
        scale: Float = 1f,
        name: String? = "Original author",
        address: String? = "person@example.com",
        repost: Boolean = false,
        reference: Boolean = false,
        selfRepost: Boolean = false,
        showTimestamps: Boolean = true,
        showRepostTime: Boolean = true,
        status: Nip05VerificationStatus = Nip05VerificationStatus.UNKNOWN,
    ) {
        val verifier = object : Nip05VerificationController {
            override fun verificationFlow(pubkey: String, nip05: String) = flowOf(status)
            override fun requestIfEligible(pubkey: String, nip05: String) { requests += pubkey to nip05 }
            override fun markProfileOpened(pubkey: String) = Unit
        }
        val clock = mutableLongStateOf(now * 1000)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(density, scale),
                LocalMinuteClock provides clock,
                LocalNip05VerificationController provides verifier,
            ) {
                UnsilenceTheme {
                    Box(Modifier.width(width.dp).background(MaterialTheme.colorScheme.background).testTag("header")) {
                        AuthorHeader(
                            pubkey = author, picture = null, displayName = name, nip05 = address,
                            createdAt = (if (reference) now - 120 else if (repost) now - 3 * 86400 else now - 60).takeIf { showTimestamps }, onAuthorClick = { opened += it }, onNoteClick = { opened += "note" },
                            wotLookup = { WotLookup.Absent },
                            repostSourcePubkey = if (repost) (if (selfRepost) author else reposter) else null,
                            repostSourceProfile = if (repost) UserEntity(
                                if (selfRepost) author else reposter,
                                displayName = if (selfRepost) name else "Reposter",
                            ) else null,
                            repostSourceCreatedAt = if (repost && showTimestamps && showRepostTime) now - 120 else null,
                            referenceRepost = reference,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
