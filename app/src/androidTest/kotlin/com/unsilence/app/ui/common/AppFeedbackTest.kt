package com.unsilence.app.ui.common

import android.os.Build
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.window.Dialog
import com.unsilence.app.MainActivity
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppFeedbackTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun unavailableSignerUsesTheSameVisibleFeedbackAsSignedInActions() {
        check(Build.HARDWARE in listOf("ranchu", "goldfish") && ui.activity.packageName.endsWith(".dev"))
        check(ui.activity.keyManager.getPublicKeyHex() == null)
        ui.onNodeWithText("Sign in with Amber").performClick()
        ui.onNodeWithText("Amber app not installed").assertIsDisplayed()
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun noticeAppearsAboveAnOpenDialogAndLeavesItsEditorFocused() {
        val activity = ui.activity
        lateinit var notify: (String) -> Unit
        ui.runOnUiThread {
            activity.setContent {
                UnsilenceTheme {
                    AppFeedback("", emptyFlow()) {
                        notify = LocalShowSnackbar.current
                        Dialog(onDismissRequest = {}) {
                            Column {
                                var text by remember { mutableStateOf("") }
                                OutlinedTextField(text, { text = it })
                                Button(onClick = { notify("Test message") }) { Text("Show notice") }
                            }
                        }
                    }
                }
            }
        }
        val field = ui.onNode(hasSetTextAction())
        field.performClick()
        field.performTextReplacement("Still editing")
        ui.runOnIdle { notify("Test message") }
        ui.onNodeWithText("Test message").assertIsDisplayed()
        field.assertIsFocused()
        ui.runOnIdle {
            val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            val windows = WindowInspector.getGlobalWindowViews()
            assertTrue(windows.any { ((it.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0) and flags == flags })
            assertTrue(windows.any { it.hasWindowFocus() && it !== activity.window.decorView })
        }
        field.performTextReplacement("Typing continues")
        ui.onNodeWithText("Typing continues").assertIsDisplayed()
    }
}
