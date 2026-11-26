package com.unsilence.app.ui.common

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.window.Dialog
import androidx.test.platform.app.InstrumentationRegistry
import com.unsilence.app.MainActivity
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppFeedbackAccessibilityTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun messageHasAnAndroidLiveRegionWhileTheUnderlyingDialogKeepsFocus() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val info = automation.serviceInfo
        val originalFlags = info.flags
        info.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        val message = "Visible to a screen reader"
        val dialogLabel = "Underlying editor"
        val activity = ui.activity
        lateinit var showNotice: (String) -> Unit

        try {
            ui.runOnUiThread {
                activity.setContent {
                    UnsilenceTheme {
                        AppFeedback("", emptyFlow()) {
                            showNotice = LocalShowSnackbar.current
                            Dialog(onDismissRequest = {}) { Text(dialogLabel) }
                        }
                    }
                }
            }
            ui.runOnIdle { showNotice(message) }
            ui.waitUntil(5_000) {
                automation.windows.any { containsText(it.root, message, requireLiveRegion = true) }
            }
            assertTrue(automation.windows.any { it.isFocused && containsText(it.root, dialogLabel) })
        } finally {
            info.flags = originalFlags
            automation.serviceInfo = info
        }
    }

    private fun containsText(
        node: AccessibilityNodeInfo?,
        message: String,
        requireLiveRegion: Boolean = false,
    ): Boolean {
        if (node == null) return false
        val needsLiveParent = requireLiveRegion && node.liveRegion == View.ACCESSIBILITY_LIVE_REGION_NONE
        if (!needsLiveParent && (node.text?.toString() == message || node.contentDescription?.toString() == message)) {
            return true
        }
        return (0 until node.childCount).any { containsText(node.getChild(it), message, needsLiveParent) }
    }
}
