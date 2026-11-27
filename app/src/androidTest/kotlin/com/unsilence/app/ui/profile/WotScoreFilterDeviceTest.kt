package com.unsilence.app.ui.profile

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.MainActivity
import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.relay.WotScoreFilter
import com.unsilence.app.ui.navigation.NotificationFilterPill
import com.unsilence.app.ui.notifications.NotifFilter
import com.unsilence.app.ui.shared.WotHiddenReply
import com.unsilence.app.ui.shared.rememberWotReplyFilter
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class WotScoreFilterDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun settingThresholdRevealsOnlyTappedReplyAndUpdatesNotificationShield() {
        val settings = MutableStateFlow<WotScoreFilter?>(WotScoreFilter())
        val scores = mapOf("actor" to WotLookup.Absent)
        val row = FeedRow("one", "actor", 1, "", 1, "[]", "", null, null, false, null,
            0L, null, null, null, null, 0, 0, 0, 0)
        ui.activity.runOnUiThread {
            ui.activity.setContent {
                UnsilenceTheme {
                    val policy by settings.collectAsStateWithLifecycle()
                    val filter = rememberWotReplyFilter("thread", settings, scores)
                    Column {
                        MinimumScoreControl(requireNotNull(policy)) { value ->
                            settings.value = WotScoreFilter(value > 0, value.coerceAtLeast(1))
                        }
                        NotificationFilterPill(NotifFilter.Global, false, false, {},
                            minimumScore = policy?.takeIf { it.enabled }?.minimum)
                        for (reply in listOf(row, row.copy(id = "two"))) {
                            if (filter.hides(reply)) WotHiddenReply(reply, filter)
                            else Text("Reply ${reply.id}")
                        }
                    }
                }
            }
        }
        ui.onNodeWithText("Reply one").assertExists()
        val slider = ui.onNodeWithContentDescription("Minimum accepted score")
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
        for (score in listOf(1, 2, 50, 100, 25)) {
            slider.performSemanticsAction(SemanticsActions.SetProgress) { it(score.toFloat()) }
            slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "$score out of 100"))
        }
        ui.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Global, minimum WoT score 25")).assertExists()
        ui.onAllNodesWithText("Hidden by your WoT filter · Minimum score 25", substring = true).assertCountEquals(2)
        ui.onNodeWithText("Reply one").assertDoesNotExist()
        ui.onAllNodesWithText("Tap to reveal", substring = true)[0].performClick()
        ui.onNodeWithText("Reply one").assertExists()
        ui.onNodeWithText("Reply two").assertDoesNotExist()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
        ui.onNodeWithText("Reply two").assertExists()
        ui.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Global, minimum WoT score 25")).assertDoesNotExist()
    }
}
