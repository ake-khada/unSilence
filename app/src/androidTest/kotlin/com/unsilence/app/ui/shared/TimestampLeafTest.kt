package com.unsilence.app.ui.shared

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.data.relay.FeedWotDisplayMode
import com.unsilence.app.ui.common.LocalMinuteClock
import com.unsilence.app.ui.theme.UnsilenceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimestampLeafTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tickUpdatesOnlyTheTimestampLeaf() {
        val created = 1_700_000_000L
        val clock = mutableLongStateOf(created * 1000L)
        var parentCompositions = 0
        compose.setContent {
            UnsilenceTheme {
                CompositionLocalProvider(LocalMinuteClock provides clock) {
                    SideEffect { parentCompositions++ }
                    WotFeedMetaTimestamp(null, FeedWotDisplayMode.NUMBERS, created)
                }
            }
        }
        compose.onNodeWithText("now").assertExists()
        val before = compose.runOnIdle { parentCompositions }
        compose.runOnIdle { clock.longValue += 60_000L }
        compose.onNodeWithText("1m").assertExists()
        compose.runOnIdle { assertEquals(before, parentCompositions) }
    }
}
