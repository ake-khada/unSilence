package com.unsilence.app.ui.compose

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.unsilence.app.MainActivity
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.theme.UnsilenceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class DraftSaveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun unsuccessfulSaveFromCloseSheetLeavesTheComposerEditable() {
        assertNull("Requires the account-free emulator", compose.activity.keyManager.getPublicKeyHex())
        val notices = mutableListOf<String>()
        var dismissals = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                UnsilenceTheme {
                    CompositionLocalProvider(LocalShowSnackbar provides { notices.add(it) }) {
                        ComposeScreen(onDismiss = { dismissals++ })
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("Keep this draft")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Save draft").performClick()
        compose.onNodeWithText("Keep this draft").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("Still editable")
        compose.runOnIdle {
            assertEquals(0, dismissals)
            assertEquals(listOf("Couldn’t save draft. Try again."), notices)
        }
    }
}
