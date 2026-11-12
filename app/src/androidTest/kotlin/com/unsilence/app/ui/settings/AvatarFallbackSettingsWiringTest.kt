package com.unsilence.app.ui.settings

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.MainActivity
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Account-free full settings screen: real Hilt ViewModel and device-local preferences. */
@RunWith(AndroidJUnit4::class)
class AvatarFallbackSettingsWiringTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun screenUsesPersistedGlobalStateAndKeepsItSeparateFromServerChoice() {
        assertNull("Requires an account-free emulator", compose.activity.keyManager.getPublicKeyHex())
        val prefs = compose.activity.relayPreferencesStore
        val previous = runBlocking { prefs.avatarFallbackPreferences.first() }
        lateinit var viewModel: MediaUploadSettingsViewModel
        try {
            runBlocking {
                prefs.setAvatarFallbackServers(listOf("https://blossom.primal.net"))
                prefs.setAvatarFallbackEnabled(true)
                prefs.avatarFallbackPreferences.first { it.enabled }
            }
            compose.runOnUiThread {
                viewModel = ViewModelProvider(compose.activity)[MediaUploadSettingsViewModel::class.java]
                compose.activity.setContent {
                    UnsilenceTheme { MediaUploadSettingsScreen(onDismiss = {}, viewModel = viewModel) }
                }
            }
            compose.onNodeWithText("Use avatar fallback servers: On").performScrollTo().assertIsDisplayed()
            compose.runOnUiThread { viewModel.setAvatarServers(emptyList()) }
            runBlocking { prefs.avatarFallbackPreferences.first { it.servers.isEmpty() } }
            compose.onNodeWithText("Use avatar fallback servers: On").assertIsDisplayed()
            runBlocking {
                prefs.setAvatarFallbackEnabled(false)
                prefs.avatarFallbackPreferences.first { !it.enabled }
            }
            compose.onNodeWithText("Use avatar fallback servers: Off").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Enable in Content & Safety → Media & data.").performScrollTo().assertIsDisplayed()
            assertTrue(viewModel.avatarPreferences.value.servers.isEmpty())
        } finally {
            runBlocking {
                prefs.setAvatarFallbackEnabled(previous.enabled)
                prefs.setAvatarFallbackServers(previous.servers)
            }
        }
    }
}
