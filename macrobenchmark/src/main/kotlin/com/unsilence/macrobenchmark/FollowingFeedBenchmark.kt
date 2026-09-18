package com.unsilence.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE = "com.unsilence.app"
private const val UI_TIMEOUT_MS = 15_000L
private const val FEED_SETTLE_MS = 5_000L
private const val BETWEEN_FLINGS_MS = 500L
private const val FINAL_REST_MS = 5_000L

/**
 * Repeatable diagnostic counterpart to the human F-01 workload.
 *
 * This benchmark is useful for regression traces and repeatability, but it does
 * not replace the final human gesture checkpoint in VALIDATION_PROTOCOL.md.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMacrobenchmarkApi::class)
class FollowingFeedBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun followingNotesTextTenFlings() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Ignore(),
        iterations = 7,
        startupMode = null,
        setupBlock = {
            device.wakeUp()
            pressHome()
            startActivityAndWait()
            prepareFollowingNotesText()
        },
    ) {
        val feed = device.wait(
            Until.findObject(By.scrollable(true)),
            UI_TIMEOUT_MS,
        )
        assertNotNull("Following feed did not expose a scrollable surface", feed)

        feed!!.setGestureMargin(device.displayWidth / 5)
        repeat(10) {
            assertTrue("Feed refused fling ${it + 1}", feed.fling(Direction.DOWN))
            Thread.sleep(BETWEEN_FLINGS_MS)
        }
        Thread.sleep(FINAL_REST_MS)
    }

    private fun MacrobenchmarkScope.prepareFollowingNotesText() {
        assertTrue(
            "Target package did not reach the foreground",
            device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), UI_TIMEOUT_MS),
        )
        assertTrue(
            "Benchmark requires the Following feed",
            device.wait(
                Until.hasObject(By.desc("Feed source: Following. Tap to change")),
                UI_TIMEOUT_MS,
            ),
        )

        val notes = device.wait(Until.findObject(By.text("Notes")), UI_TIMEOUT_MS)
        assertNotNull("Notes tab was not visible", notes)
        notes!!.click()

        if (!device.hasObject(By.desc("Text filter"))) {
            val filterLauncher = listOf(
                "Open feed filters",
                "Active feed filters",
                "Images filter",
                "Video filter",
                "Articles filter",
                "Text, Images filters",
                "Text, Video filters",
                "Text, Articles filters",
                "Images, Video filters",
                "Images, Articles filters",
                "Video, Articles filters",
            ).firstNotNullOfOrNull { description ->
                device.findObject(By.desc(description))
            }
            assertNotNull("Feed filter launcher was not visible", filterLauncher)
            filterLauncher!!.click()

            val allChip = device.wait(Until.findObject(By.desc("All")), UI_TIMEOUT_MS)
            val textChip = device.wait(Until.findObject(By.desc("Text")), UI_TIMEOUT_MS)
            val apply = device.wait(Until.findObject(By.text("Apply")), UI_TIMEOUT_MS)
            assertNotNull("All show-type chip was not visible", allChip)
            assertNotNull("Text show-type chip was not visible", textChip)
            assertNotNull("Filter Apply action was not visible", apply)

            allChip!!.click()
            textChip!!.click()
            apply!!.click()
            assertTrue(
                "Text-only filter did not become active",
                device.wait(Until.hasObject(By.desc("Text filter")), UI_TIMEOUT_MS),
            )
        }

        val scrollToTop = device.wait(
            Until.findObject(By.desc("Scroll feed to top")),
            UI_TIMEOUT_MS,
        )
        assertNotNull("Feed header was not visible", scrollToTop)
        scrollToTop!!.click()
        Thread.sleep(FEED_SETTLE_MS)

        assertTrue(
            "Following source changed during setup",
            device.hasObject(By.desc("Feed source: Following. Tap to change")),
        )
        assertTrue("Text-only filter changed during setup", device.hasObject(By.desc("Text filter")))
        assertTrue("Feed had no scrollable content", device.hasObject(By.scrollable(true)))
    }
}
