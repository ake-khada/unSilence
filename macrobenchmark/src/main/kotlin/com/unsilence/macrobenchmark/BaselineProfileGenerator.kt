package com.unsilence.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiDevice
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run against the non-minified baselineProfile variant, then rebuild release. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Before fun requireProfilingRuntime() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(
            "ART JIT profiling is disabled. Generate on stock Android " +
                "API 33+ or an emulator; do not weaken the phone's security settings.",
            device.executeShellCommand("getprop dalvik.vm.usejit").trim() != "false",
        )
    }

    @Test fun coldStartAndFeedScroll() = rule.collect(
        packageName = "com.unsilence.app",
        includeInStartupProfile = false,
    ) {
        pressHome()
        startActivityAndWait()
        assertTrue(
            "Unlock the phone and leave a populated feed selected before generation",
            device.wait(Until.hasObject(By.descStartsWith("Feed source:")), 20_000),
        )
        val feed = device.wait(Until.findObject(By.scrollable(true)), 20_000)
        assertNotNull("No feed content; do not generate an onboarding-only profile", feed)
        feed!!.setGestureMargin(device.displayWidth / 5)
        repeat(4) {
            feed.fling(Direction.DOWN)
            device.waitForIdle()
        }
        repeat(4) {
            feed.fling(Direction.UP)
            device.waitForIdle()
        }
    }

    @Test fun coldStart() = rule.collect(
        packageName = "com.unsilence.app",
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        assertTrue("Feed shell did not appear", device.wait(
            Until.hasObject(By.descStartsWith("Feed source:")), 20_000,
        ))
    }
}
