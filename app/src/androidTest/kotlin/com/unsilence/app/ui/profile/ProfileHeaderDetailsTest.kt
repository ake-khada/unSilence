package com.unsilence.app.ui.profile

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.unsilence.app.ui.theme.Spacing
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unsilence.app.ui.theme.UnsilenceTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real header components, fake action/clipboard only; never sign, pay or fetch. */
@RunWith(AndroidJUnit4::class)
class ProfileHeaderDetailsTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(ProfileFollowState(isFollowing = false))
    private val loading = mutableStateOf(false)
    private val menuOpen = mutableStateOf(false)
    private val subject = mutableStateOf("author")
    private val intents = mutableListOf<Boolean>()
    private val operations get() = intents.size
    private var copied: AnnotatedString? = null
    private val clipboard = object : ClipboardManager {
        override fun getText() = copied
        override fun setText(annotatedString: AnnotatedString) { copied = annotatedString }
    }

    @Test fun unresolvedOwnListDisablesMenuWithoutFollowClaim() {
        state.value = ProfileFollowState()
        render()
        openMenu()
        compose.onNodeWithText("Follow status loading").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Following").assertDoesNotExist()
        compose.runOnIdle { assertTrue(intents.isEmpty()) }
    }

    @Test fun followAndUnfollowMenuRetain48dpTouchBounds() {
        render()
        openMenu()
        compose.onNodeWithText("Follow").assertTouchHeightIsEqualTo(48.dp)
        compose.runOnIdle { state.value = ProfileFollowState(isFollowing = true) }
        compose.onNodeWithText("Unfollow").assertTouchHeightIsEqualTo(48.dp)
    }

    @Test fun unknownRelationshipShowsPlainFollowWithoutClaim() {
        render()
        openMenu()
        compose.onNodeWithText("Follow").assertIsDisplayed().performClick()
        compose.onNodeWithText("Follows you").assertDoesNotExist()
        compose.onNodeWithText("Mutual follow").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(true), intents); assertFalse(menuOpen.value) }
    }

    @Test fun incomingFollowSharesPositiveLabelAndFollowBackAction() {
        state.value = ProfileFollowState(isFollowing = false, followsYou = true)
        render()
        compose.onNodeWithText("Follows you").assertIsDisplayed()
        openMenu()
        compose.onNodeWithText("Follow back").performClick()
        compose.runOnIdle { assertEquals(listOf(true), intents) }
    }

    @Test fun mutualFollowRequiresConfirmationOutsideDismissedMenuAndCancelDoesNothing() {
        state.value = ProfileFollowState(isFollowing = true, followsYou = true)
        render()
        compose.onNodeWithText("Mutual follow").assertIsDisplayed()
        openMenu()
        compose.onNodeWithText("Unfollow").performClick()
        compose.onNodeWithText("Unfollow?").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, operations); assertFalse(menuOpen.value) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Unfollow?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, operations) }
        openMenu()
        compose.onNodeWithText("Unfollow").performClick()
        compose.onNodeWithText("Unfollow", substring = false).performClick()
        compose.runOnIdle { assertEquals(listOf(false), intents) }
    }

    @Test fun pendingFollowDisablesRepeatedOperation() {
        loading.value = true
        render()
        openMenu()
        compose.onNodeWithText("Updating…").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, operations) }
    }

    @Test fun ownProfileHasNoRelationshipMarkerOrFollowAction() {
        state.value = ProfileFollowState(isFollowing = true, followsYou = true)
        render(own = true)
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.onNodeWithText("Mutual follow").assertDoesNotExist()
        compose.onNodeWithContentDescription("Following").assertDoesNotExist()
    }

    @Test fun staleConfirmationDoesNotFollowAnotherProfile() {
        state.value = ProfileFollowState(isFollowing = true)
        render()
        openMenu()
        compose.onNodeWithText("Unfollow").performClick()
        compose.runOnIdle { subject.value = "another-author" }
        compose.onNodeWithText("Unfollow?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, operations) }
    }

    @Test fun changedFollowStateDismissesStaleUnfollowConfirmation() {
        state.value = ProfileFollowState(isFollowing = true)
        render()
        openMenu()
        compose.onNodeWithText("Unfollow").performClick()
        compose.runOnIdle { state.value = ProfileFollowState(isFollowing = false) }
        compose.onNodeWithText("Unfollow?").assertDoesNotExist()
        openMenu()
        compose.onNodeWithText("Follow").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, operations) }
    }

    @Test fun fullLightningValueCopiesWithoutAnyFollowOrPaymentAction() {
        val address = "person@wallet.example"
        render(address = address, own = true)
        compose.onNodeWithContentDescription("Copy Lightning address: $address").performClick()
        compose.runOnIdle { assertEquals(address, copied?.text); assertEquals(0, operations) }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test fun blankLightningAddressAddsNoRow() {
        render(address = "  ", own = true)
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun longLocalPartPreservesDomainAtNarrowLargeFont() {
        val address = "verylonglocalpart".repeat(12) + "@wallet.example"
        render(address = address, width = 280, scale = 1.5f, own = true)
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(address, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertEquals(1, layout.lineCount)
        assertTrue(layout.isLineEllipsized(0))
        // The suffix glyphs are inside the laid-out text, not an inaccessible clipped end.
        val domainStart = address.indexOf('@')
        for (i in domainStart until address.length) {
            val glyph = layout.getBoundingBox(i)
            assertTrue("domain glyph $i within width", glyph.left >= -1f && glyph.right <= layout.size.width + 1f)
        }
        compose.onNodeWithContentDescription("Copy Lightning address: $address").performClick()
        compose.runOnIdle { assertEquals(address, copied?.text) }
        capture("lightning-long-local-large-font")
    }

    @Test fun veryLongDomainRemainsBoundedAndCopiesCompletely() {
        val address = "person@" + "long-subdomain.".repeat(20) + "example"
        render(address = address, width = 280, scale = 1.5f, own = true)
        compose.onNodeWithContentDescription("Copy Lightning address: $address")
            .assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(address, copied?.text) }
    }

    @Test fun completeDetailsAtStandardWidth() {
        state.value = ProfileFollowState(isFollowing = true, followsYou = true)
        render(address = "person@wallet.example")
        compose.onNodeWithText("Mutual follow").assertIsDisplayed()
        compose.onNodeWithText("person@wallet.example", useUnmergedTree = true).assertIsDisplayed()
        capture("profile-details-normal")
    }

    @Test fun knownFollowMarkerIsNeutralNoninteractiveAndBesideCenteredName() {
        render()
        compose.onNodeWithContentDescription("Following").assertDoesNotExist()
        compose.runOnIdle { state.value = ProfileFollowState(isFollowing = true) }
        val name = compose.onNodeWithText("Example author").getUnclippedBoundsInRoot()
        val check = compose.onNodeWithContentDescription("Following")
        check.assertIsDisplayed().assertHasNoClickAction()
        val bounds = check.getUnclippedBoundsInRoot()
        assertEquals(5f, (bounds.left - name.right).value, 0.5f)
        assertEquals((name.top.value + name.bottom.value) / 2, (bounds.top.value + bounds.bottom.value) / 2, 0.5f)
        val header = compose.onNodeWithTag("details").getUnclippedBoundsInRoot()
        assertEquals((header.left.value + header.right.value) / 2, (name.left.value + bounds.right.value) / 2, 0.5f)
        compose.onNodeWithText("Follow").assertDoesNotExist()
        compose.onNodeWithText("Unfollow").assertDoesNotExist()
    }

    @Test fun socialContextJoinsKnownPartsAndWrapsCenteredAtLargeFont() {
        state.value = ProfileFollowState(isFollowing = true, followsYou = true)
        render(width = 280, scale = 1.5f, verified = 1400L)
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("1.4k verified in your grapevine | Mutual follow")
            .assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, layout.layoutInput.style.textAlign)
        assertFalse(layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) {
            assertFalse(layout.isLineEllipsized(line))
            assertTrue(layout.getLineRight(line) <= layout.size.width + 1f)
        }
        capture("social-context-large-font")
    }

    @Test fun grapevineWithoutRelationshipHasNoSeparator() {
        render(verified = 42L)
        compose.onNodeWithText("42 verified in your grapevine").assertIsDisplayed()
        compose.onAllNodes(hasText("|", substring = true)).assertCountEquals(0)
    }

    @Test fun absentSocialContextLeavesNoEmptyRow() {
        compose.setContent {
            UnsilenceTheme {
                ProfileHeaderStack {
                    Text("Stats")
                    ProfileSocialContext(null, null)
                    Text("Tabs")
                }
            }
        }
        val stats = compose.onNodeWithText("Stats").getUnclippedBoundsInRoot()
        val tabs = compose.onNodeWithText("Tabs").getUnclippedBoundsInRoot()
        assertEquals(8f, (tabs.top - stats.bottom).value, 0.5f)
    }

    @Test fun sharedStackHasEqualGapsAndNeighborActionsStayIndependent() {
        var npubCopies = 0
        var bioClicks = 0
        compose.setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                UnsilenceTheme {
                    ProfileHeaderStack(Modifier.width(360.dp).padding(20.dp)) {
                        ProfileDisplayName("Example author", isFollowing = false)
                        Text("npub fixture", Modifier.clickable { npubCopies++ }.testTag("npub"))
                        Text("name@example.com", Modifier.testTag("nip05"))
                        ProfileLightningAddress("person@wallet.example")
                        Text("Bio link", Modifier.clickable { bioClicks++ }.testTag("bio"))
                    }
                }
            }
        }
        val nip = compose.onNodeWithTag("nip05").getUnclippedBoundsInRoot()
        val light = compose.onNodeWithContentDescription("Copy Lightning address: person@wallet.example")
        val lightBounds = light.getUnclippedBoundsInRoot()
        val bio = compose.onNodeWithTag("bio").getUnclippedBoundsInRoot()
        assertEquals(8f, (lightBounds.top - nip.bottom).value, 0.5f)
        assertEquals(8f, (bio.top - lightBounds.bottom).value, 0.5f)
        light.assertTouchHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("npub").performTouchInput { click() }
        light.performTouchInput { click() }
        compose.onNodeWithTag("bio").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, npubCopies)
            assertEquals(1, bioClicks)
            assertEquals("person@wallet.example", copied?.text)
            assertTrue(intents.isEmpty())
        }
    }

    @Test fun missingLightningDoesNotLeaveAnEmptyGap() {
        compose.setContent {
            UnsilenceTheme {
                ProfileHeaderStack {
                    Text("Nostr address")
                    ProfileLightningAddress(null)
                    Text("Bio")
                }
            }
        }
        val first = compose.onNodeWithText("Nostr address").getUnclippedBoundsInRoot()
        val next = compose.onNodeWithText("Bio").getUnclippedBoundsInRoot()
        assertEquals(8f, (next.top - first.bottom).value, 0.5f)
    }

    @Test fun wideBannerFitsWholeImageCenteredInViewport() = checkBanner(400, 100)
    @Test fun tallBannerFitsWholeImageCenteredInViewport() = checkBanner(100, 400)

    private fun checkBanner(width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) for (x in 0 until width) {
            bitmap.setPixel(x, y, if (x < width / 2) android.graphics.Color.RED else android.graphics.Color.GREEN)
        }
        compose.setContent {
            UnsilenceTheme {
                val context = LocalContext.current
                val request = androidx.compose.runtime.remember(bitmap) {
                    ImageRequest.Builder(context).data(bitmap).crossfade(false).build()
                }
                ProfileBannerImage(request, Modifier.width(300.dp).height(200.dp).testTag("banner"))
            }
        }
        compose.waitUntil(5_000) {
            val pixels = compose.onNodeWithTag("banner").captureToImage().toPixelMap()
            pixels[pixels.width / 2 - 5, pixels.height / 2].red > 0.9f
        }
        val pixels = compose.onNodeWithTag("banner").captureToImage().toPixelMap()
        val scale = minOf(pixels.width.toFloat() / width, pixels.height.toFloat() / height)
        val left = (pixels.width - width * scale) / 2f
        val top = (pixels.height - height * scale) / 2f
        val right = pixels.width - left
        val bottom = pixels.height - top
        assertTrue(pixels[(left + 2).toInt(), (top + 2).toInt()].red > 0.9f)
        assertTrue(pixels[(right - 3).toInt(), (bottom - 3).toInt()].green > 0.9f)
        if (top > 2) {
            assertEquals(0f, pixels[pixels.width / 2, (top - 2).toInt()].red, 0.01f)
            assertEquals(0f, pixels[pixels.width / 2, (bottom + 1).toInt()].green, 0.01f)
        } else {
            assertEquals(0f, pixels[(left - 2).toInt(), pixels.height / 2].red, 0.01f)
            assertEquals(0f, pixels[(right + 1).toInt(), pixels.height / 2].green, 0.01f)
        }
    }

    private fun render(address: String? = null, own: Boolean = false, width: Int = 360, scale: Float = 1f, verified: Long? = null) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, scale),
                LocalClipboardManager provides clipboard,
            ) {
                UnsilenceTheme {
                    Column {
                        if (!own) Box {
                            Text("Profile actions", Modifier.clickable { menuOpen.value = true })
                            ProfileActionsMenu(subject.value, state.value, loading.value, menuOpen.value,
                                { menuOpen.value = false }, { intents += it }) {}
                        }
                        ProfileHeaderStack(Modifier.width(width.dp).background(MaterialTheme.colorScheme.background).padding(vertical = Spacing.large).testTag("details")) {
                            ProfileDisplayName("Example author", !own && state.value.isFollowing == true)
                            ProfileLightningAddress(address)
                            ProfileSocialContext(verified, state.value.relationship.takeUnless { own })
                        }
                    }
                }
            }
        }
    }

    private fun openMenu() { compose.onNodeWithText("Profile actions").performClick() }

    private fun capture(name: String) {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { compose.onNodeWithTag("details").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
