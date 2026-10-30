package com.unsilence.app.ui.feed

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.data.memory.NotificationRow
import com.unsilence.app.data.memory.ZapAttribution
import com.unsilence.app.data.memory.ZapDetail
import com.unsilence.app.ui.shared.NotificationActorSheet
import com.unsilence.app.ui.shared.NotificationEventRow
import com.unsilence.app.ui.theme.UnsilenceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Shared production surfaces with local rows only: no account, lookup, or navigation. */
@RunWith(AndroidJUnit4::class)
class ZapAttributionPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun noteDrawerSeparatesUnverifiedFromAnonymous() = checkDrawer("note")
    @Test fun articleDrawerSeparatesUnverifiedFromAnonymous() = checkDrawer("article")

    private fun checkDrawer(target: String) {
        compose.setContent {
            UnsilenceTheme {
                EngagementDrawer(
                    eventId = target,
                    statsFlow = null,
                    zapDetailsForEvent = {
                        assertEquals(target, it)
                        listOf(ZapDetail(ZapAttribution.Unverified, 1_000), ZapDetail.anonymous(2_000))
                    },
                    repostPubkeysForEvent = null,
                    reactionsForEvent = null,
                    profileFlow = null,
                    lookupProfile = null,
                    onProfileTap = { error("Unattributed zaps must not open a profile") },
                )
            }
        }
        compose.onNodeWithText("Unverified").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithContentDescription("Unverified zaps").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithContentDescription("Anonymous zaps").assertIsDisplayed().assertHasNoClickAction()
    }

    @Test fun notificationSummaryDisclosesUnverifiedReceipts() {
        compose.setContent {
            UnsilenceTheme {
                NotificationEventRow(row(), onNoteClick = {}, onProfileClick = { error("No identified actor") })
            }
        }
        compose.onNodeWithText("1 unverified").assertIsDisplayed()
    }

    @Test fun actorSheetKeepsAnonymousAndUnverifiedSeparateWithoutProfileActions() {
        compose.setContent {
            UnsilenceTheme {
                NotificationActorSheet(row(), onProfileClick = { error("No identified actor") }, onDismiss = {})
            }
        }
        compose.onNodeWithText("1 anonymous").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("1 unverified").assertIsDisplayed().assertHasNoClickAction()
    }

    private fun row() = NotificationRow.Grouped(
        notifType = "zap", targetNoteId = null, actors = emptyList(), people = 2,
        sumSats = 3_000, dominantReaction = null, anonymousCount = 1, anonymousSats = 2_000,
        anonymousMostRecentAt = 100, mostRecentAt = 100,
        unverifiedCount = 1, unverifiedSats = 1_000, unverifiedMostRecentAt = 100,
    )
}
