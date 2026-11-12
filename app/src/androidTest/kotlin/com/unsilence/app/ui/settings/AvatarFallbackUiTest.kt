package com.unsilence.app.ui.settings

import android.content.Context
import android.graphics.drawable.Animatable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.unsilence.app.ui.common.LocalAvatarFallbackPreferences
import com.unsilence.app.ui.common.rememberAvatarImageRequest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.Extras
import coil3.ImageLoader
import coil3.asDrawable
import coil3.network.*
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.unsilence.app.data.blossom.*
import com.unsilence.app.data.media.DirectBufferAnimatedImageDecoderFactory
import com.unsilence.app.domain.model.AutomaticMediaPreferences
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
@OptIn(coil3.annotation.ExperimentalCoilApi::class)
class AvatarFallbackUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedServersDoNotImplyThatFallbackIsEnabled() {
        val selected = mutableStateOf(listOf("https://blossom.primal.net"))
        compose.setContent { UnsilenceTheme {
            AvatarFallbackServersSection(selected.value) { selected.value = it }
        } }
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText("Use avatar fallback servers: Off").assertIsDisplayed()
        compose.onNodeWithText("Enable in Content & Safety → Media & data.").assertIsDisplayed()
        compose.onNode(isToggleable()).performClick().assertIsOff()
        compose.onNodeWithText("Use avatar fallback servers: Off").assertIsDisplayed()
    }

    @Test fun mediaDataToggleStartsOffAndCanBeEnabled() {
        val enabled = mutableStateOf(false)
        compose.setContent { UnsilenceTheme {
            MediaDataSettingsContent(AutomaticMediaPreferences(), {}, {}, {}, enabled.value, { enabled.value = it })
        } }
        compose.onNodeWithText("Use avatar fallback servers").performScrollTo().assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(enabled.value) }
    }

    @Test fun chooserReflectsGlobalStateWithoutChangingServerSelections() {
        val enabled = mutableStateOf(false)
        val servers = mutableStateOf(listOf("https://blossom.primal.net"))
        compose.setContent { UnsilenceTheme {
            AvatarFallbackServersSection(servers.value, enabled = enabled.value) { servers.value = it }
        } }
        compose.onNodeWithText("Use avatar fallback servers: Off").assertIsDisplayed()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithText("Use avatar fallback servers: On").assertIsDisplayed()
        compose.onNodeWithText("Enable in Content & Safety → Media & data.").assertDoesNotExist()
        compose.onNode(isToggleable()).assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("Use avatar fallback servers: On").assertIsDisplayed()
        compose.runOnIdle { enabled.value = false }
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("Use avatar fallback servers: Off").assertIsDisplayed()
        compose.onNodeWithText("Enable in Content & Safety → Media & data.").assertIsDisplayed()
    }

    @Test fun serverChoiceIsIndependentAndRejectsUnsafeInput() {
        val servers = mutableStateOf(listOf("https://blossom.primal.net"))
        compose.setContent { UnsilenceTheme { AvatarFallbackServersSection(servers.value) { servers.value = it } } }
        compose.onNodeWithText("Avatar fallback servers").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertTrue(servers.value.isEmpty()) }
        compose.onNodeWithText("Add avatar fallback server").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("https://127.0.0.1")
        compose.onNode(hasText("Add") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("https://selected.example")
        compose.onNode(hasText("Add") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(listOf("https://selected.example"), servers.value) }
    }

    @Test fun duplicateAndBareServerInputsGiveFeedbackInsteadOfSilentlyClosing() {
        var writes = 0
        compose.setContent { UnsilenceTheme {
            AvatarFallbackServersSection(listOf("https://blossom.primal.net")) { writes++ }
        } }
        compose.onNodeWithText("Add avatar fallback server").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("https://blossom.primal.net/")
        compose.onNodeWithText("Already selected").assertIsDisplayed()
        compose.onNode(hasText("Add") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("primal")
        compose.onNodeWithText("Enter a full server address, such as blossom.primal.net.").assertIsDisplayed()
        compose.onNode(hasText("Add") and hasClickAction()).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun fallbackServerNamesRemainVisibleOnBlack() {
        compose.setContent { UnsilenceTheme {
            // Match MediaUploadSettingsScreen: black container, without a Material Surface.
            Box(Modifier.background(Color.Black)) {
                AvatarFallbackServersSection(listOf("https://blossom.primal.net"), listOf("https://upload.example")) {}
            }
        } }
        for (label in listOf("Primal", "upload.example")) {
            val pixels = compose.onNodeWithText(label).assertIsDisplayed().captureToImage().toPixelMap()
            val hasLightText = (0 until pixels.height).any { y ->
                (0 until pixels.width).any { x ->
                    val pixel = pixels[x, y]
                    pixel.red > .8f && pixel.green > .8f && pixel.blue > .8f
                }
            }
            assertTrue("$label must render readable text, not black-on-black semantics only", hasLightText)
        }
    }

    @Test fun configuredUploadServerIsAnUncheckedIndependentFallbackChoice() {
        val selected = mutableStateOf(listOf("https://blossom.primal.net"))
        val uploadServers = listOf("https://upload.example")
        compose.setContent { UnsilenceTheme {
            AvatarFallbackServersSection(selected.value, uploadServers) { selected.value = it }
        } }
        compose.onNodeWithText("upload.example").assertIsDisplayed()
        compose.onAllNodes(isToggleable())[1].assertIsOff().performClick().assertIsOn()
        compose.runOnIdle {
            assertEquals(listOf("https://blossom.primal.net", "https://upload.example"), selected.value)
            assertEquals(listOf("https://upload.example"), uploadServers)
        }
    }

    @Test fun changingFallbackPreferenceRetriesTheSameFailedAvatar() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = mutableStateOf(AvatarFallbackPreferences())
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val errors = java.util.concurrent.atomic.AtomicInteger()
        val successes = java.util.concurrent.atomic.AtomicInteger()
        val bytes = android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", android.util.Base64.DEFAULT)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val offline = object : NetworkClient {
            override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T = block(NetworkResponse(404))
        }
        val loader = ImageLoader.Builder(context).diskCache(null).components {
            add(NetworkFetcher.Factory(networkClient = { AvatarNetworkClient(offline) { _, _, _ ->
                attempts.incrementAndGet()
                if (preferences.value.enabled) VerifiedAvatar(bytes, "image/gif") else null
            } }))
            add(DirectBufferAnimatedImageDecoderFactory())
        }.build()
        try {
            compose.setContent { UnsilenceTheme {
                CompositionLocalProvider(LocalAvatarFallbackPreferences provides preferences.value) {
                    AsyncImage(rememberAvatarImageRequest("https://retry.example/$hash.gif", 40.dp, "ab".repeat(32)),
                        contentDescription = "Avatar", imageLoader = loader, modifier = Modifier.size(40.dp),
                        onError = { errors.incrementAndGet() }, onSuccess = { successes.incrementAndGet() })
                }
            } }
            compose.waitUntil { errors.get() == 1 }
            compose.runOnIdle { preferences.value = AvatarFallbackPreferences(enabled = true) }
            compose.waitUntil { successes.get() == 1 }
            assertEquals(2, attempts.get())
        } finally { loader.shutdown() }
    }

    @Test fun recoveredAnimatedGifUsesExistingAndroidDecoder() = runBlocking {
        // Two public synthetic 1x1 frames; no account, remote fetch or navigation.
        val hex = "47494638396101000100800000ff00000000ff21ff0b4e45545343415045322e30030100000021f904000a0000002c000000000100010000020244010021f904000a0000002c00000000010001000002024c01003b"
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val url = "https://fixture.example/$hash.gif"
        val context = ApplicationProvider.getApplicationContext<Context>()
        val offline = object : NetworkClient {
            override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T = block(NetworkResponse(404))
        }
        val loader = ImageLoader.Builder(context).diskCache(null).components {
            add(NetworkFetcher.Factory(networkClient = { AvatarNetworkClient(offline) { _, _, blob ->
                assertTrue(AvatarTransport.matches(bytes, blob.hash))
                VerifiedAvatar(bytes, "image/gif")
            } }))
            add(DirectBufferAnimatedImageDecoderFactory())
        }.build()
        try {
            val request = ImageRequest.Builder(context).data(url).size(40, 40)
                .apply { extras[AvatarRequestKey] = AvatarRequest("ab".repeat(32), url) }.build()
            val result = loader.execute(request)
            assertTrue("Expected decoded GIF, got $result", result is SuccessResult)
            assertTrue((result as SuccessResult).image.asDrawable(context.resources) is Animatable)
        } finally { loader.shutdown() }
    }
}
