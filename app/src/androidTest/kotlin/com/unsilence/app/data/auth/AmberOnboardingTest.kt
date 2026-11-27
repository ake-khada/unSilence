package com.unsilence.app.data.auth

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.unsilence.app.ui.common.LocalShowSnackbar
import com.unsilence.app.ui.onboarding.OnboardingScreen
import com.unsilence.app.ui.theme.UnsilenceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class AmberOnboardingTest {
    @get:Rule val ui = createComposeRule()

    @Test fun missingAmberLeavesSignInAvailableAndShowsTheNotice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageManager.getLaunchIntentForPackage(AmberSigner.AMBER_PACKAGE) == null)
        assertFalse(AmberSigner.isInstalled(context))
        val keys = KeyManager(context)
        var notice: String? = null
        var completed = false
        ui.setContent {
            UnsilenceTheme {
                CompositionLocalProvider(LocalShowSnackbar provides { notice = it }) {
                    OnboardingScreen(keys) { completed = true }
                }
            }
        }
        val signIn = ui.onNodeWithText("Sign in with Amber")
        signIn.performClick()
        signIn.assertIsDisplayed()
        ui.runOnIdle {
            assertEquals("Amber app not installed", notice)
            assertFalse(completed)
        }
    }
}
