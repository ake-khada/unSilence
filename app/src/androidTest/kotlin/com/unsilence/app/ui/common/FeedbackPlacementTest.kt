package com.unsilence.app.ui.common

import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.filters.SdkSuppress
import com.unsilence.app.MainActivity
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

@SdkSuppress(minSdkVersion = 29)
class FeedbackPlacementTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun noticeSettlesAtLowerCenterAboveNavigation() = checkPlacement(withKeyboard = false)

    @Test fun noticeSettlesAboveTheOpenKeyboardAndKeepsEditorFocus() = checkPlacement(withKeyboard = true)

    private fun checkPlacement(withKeyboard: Boolean) {
        val activity = ui.activity
        val decor = activity.window.decorView
        lateinit var notify: (String) -> Unit
        ui.runOnUiThread {
            activity.setContent {
                UnsilenceTheme {
                    AppFeedback("", emptyFlow()) {
                        notify = LocalShowSnackbar.current
                        var value by remember { mutableStateOf("") }
                        OutlinedTextField(value, { value = it })
                    }
                }
            }
        }
        ui.waitForIdle()
        if (withKeyboard) {
            ui.onNode(hasSetTextAction()).performClick()
            ui.runOnIdle { WindowInsetsControllerCompat(activity.window, decor).show(WindowInsetsCompat.Type.ime()) }
        }
        ui.waitUntil(5_000) { ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == withKeyboard }
        ui.runOnIdle { notify("Draft saved") }
        ui.waitUntil(3_000) { noticeWindow()?.height?.let { it > 0 } == true }
        ui.waitForIdle()

        val window = ui.runOnIdle { requireNotNull(noticeWindow()).boundsOnScreen() }
        val screen = ui.runOnIdle { decor.boundsOnScreen() }
        val obstacles = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
        val inset = requireNotNull(ViewCompat.getRootWindowInsets(decor)).getInsets(obstacles).bottom
        val density = decor.resources.displayMetrics.density
        val gap = ((if (withKeyboard) 12 else 64) * density).roundToInt()
        assertEquals("horizontal center", screen.exactCenterX(), window.exactCenterX(), 2f)
        assertEquals("clearance above navigation or IME", gap.toDouble(), (screen.bottom - inset - window.bottom).toDouble(), 2.0)
        if (withKeyboard) ui.onNode(hasSetTextAction()).assertIsFocused()
    }

    private fun noticeWindow() = WindowInspector.getGlobalWindowViews().firstOrNull { view ->
        val flags = (view.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0
        flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0 &&
            flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0
    }

    private fun View.boundsOnScreen(): Rect {
        val position = IntArray(2)
        getLocationOnScreen(position)
        return Rect(position[0], position[1], position[0] + width, position[1] + height)
    }
}
