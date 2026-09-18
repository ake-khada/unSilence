package com.unsilence.macrobenchmark

import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit, emulator-only provisioning. Creates a disposable LOCAL test identity,
 * skips all follows and selects Global/Raw. Never imports keys or posts anything.
 * Not part of profile measurements; invoke this class separately with the opt-in.
 */
@RunWith(AndroidJUnit4::class)
class PrepareProfileEmulator {
    @Test fun prepareDisposableFeed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue("Requires explicit allowLocalTestIdentity=true",
            InstrumentationRegistry.getArguments().getString("allowLocalTestIdentity") == "true")
        assertTrue("Never run identity provisioning on a physical phone",
            Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("generic"))
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.context
        val launch = context.packageManager.getLaunchIntentForPackage("com.unsilence.app")
        assertNotNull("Install the baselineProfile APK first", launch)
        context.startActivity(launch!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        val create = device.wait(Until.findObject(By.text("Create new identity")), 20_000)
        assertNotNull("Expected a fresh disposable emulator, not an existing account", create)
        create!!.click()
        // A blank identity can finish graph onboarding automatically if the
        // suggestion service is unavailable; otherwise Skip selects no people.
        device.wait(Until.hasObject(By.text("Skip")), 45_000)
        device.findObject(By.text("Skip"))?.click()
        val source = device.wait(Until.findObject(By.descStartsWith("Feed source:")), 30_000)
        assertNotNull("Feed shell did not appear", source)
        source!!.click()
        val global = device.wait(Until.findObject(By.text("Global")), 10_000)
        assertNotNull("Global feed selection missing", global)
        global!!.click()
        device.wait(Until.hasObject(By.descStartsWith("Trusted lens")), 10_000)
        device.findObject(By.descStartsWith("Trusted lens"))?.click()
        assertTrue("Raw lens did not activate",
            device.wait(Until.hasObject(By.descStartsWith("Raw feed")), 10_000))
        assertTrue("No populated feed; cannot capture a useful profile",
            device.wait(Until.hasObject(By.scrollable(true)), 45_000))
        // Home lets the normal snapshot/save-on-stop path persist the test feed.
        device.pressHome()
    }
}
