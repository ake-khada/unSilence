package com.unsilence.app.ui.search

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.ui.theme.UnsilenceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Offline state/semantics fixtures, not a substitute for owner phone validation. */
@RunWith(AndroidJUnit4::class)
class SearchDiscoveryTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(SearchDiscoveryState())
    private var retryCalls = 0
    private var clickedPerson: String? = null
    private var clickedTag: String? = null
    private val person = UserEntity(pubkey = "a".repeat(64), name = "Alice")

    private fun render(onRetry: () -> Unit = { retryCalls++ }) {
        // The pre-existing loading shimmer is infinite. Freeze its clock so an
        // idle wait cannot mistake the fixture's animation for unfinished work.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UnsilenceTheme {
                SearchDiscoveryContent(
                    state = state.value,
                    staticBottomPadding = 0.dp,
                    onRetry = onRetry,
                    onHashtagClick = { clickedTag = it },
                    onUserClick = { clickedPerson = it },
                )
            }
        }
        compose.mainClock.advanceTimeBy(64)
    }

    private fun setState(value: SearchDiscoveryState) {
        compose.runOnUiThread { state.value = value }
        compose.mainClock.advanceTimeBy(64)
    }

    @Test fun loadingFailureRetryAndContentAreReachable() {
        state.value = SearchDiscoveryState(
            refreshing = true,
            peopleStatus = DiscoveryStatus.Loading,
            hashtagStatus = DiscoveryStatus.Loading,
        )
        render {
            retryCalls++
            state.value = SearchDiscoveryState(users = listOf(person), peopleStatus = DiscoveryStatus.Ready)
        }
        compose.onNodeWithContentDescription("Loading suggestions").assertIsDisplayed()
        setState(SearchDiscoveryState(
            peopleStatus = DiscoveryStatus.Unavailable,
            hashtagStatus = DiscoveryStatus.Unavailable,
        ))
        compose.onNodeWithContentDescription("Loading suggestions").assertDoesNotExist()
        compose.onNodeWithText("Suggestions unavailable").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("Alice").assertIsDisplayed()
        compose.onNodeWithText("VOICES TO FOLLOW").assertIsDisplayed()
        compose.onNodeWithText("Suggestions unavailable").assertDoesNotExist()
        assertEquals(1, retryCalls)
    }

    @Test fun peopleWithoutTopicsRemainVisibleAndClickable() {
        state.value = SearchDiscoveryState(
            users = listOf(person),
            peopleStatus = DiscoveryStatus.Ready,
            hashtagStatus = DiscoveryStatus.Unavailable,
            peopleSource = DiscoverySource.Provider,
        )
        render()
        compose.onNodeWithText("VOICES TO FOLLOW").assertIsDisplayed()
        compose.onNodeWithText("Alice").performClick()
        compose.onNodeWithText("Topics unavailable").assertIsDisplayed()
        assertEquals(person.pubkey, clickedPerson)
    }

    @Test fun topicsWithoutPeopleRemainVisibleAndClickable() {
        state.value = SearchDiscoveryState(
            hashtags = listOf("nostr" to 2),
            hashtagStatus = DiscoveryStatus.Ready,
            peopleStatus = DiscoveryStatus.Unavailable,
            hashtagSource = DiscoverySource.Provider,
        )
        render()
        compose.onNodeWithText("TRENDING TOPICS").assertIsDisplayed()
        compose.onNodeWithText("#nostr").performClick()
        compose.onNodeWithText("People unavailable").assertIsDisplayed()
        assertEquals("nostr", clickedTag)
    }

    @Test fun localFallbackKeepsApprovedHeadingsAndUnknownCountsAreNotZero() {
        state.value = SearchDiscoveryState(
            hashtags = listOf("local" to 1), users = listOf(person),
            hashtagStatus = DiscoveryStatus.Ready, peopleStatus = DiscoveryStatus.Ready,
        )
        render()
        compose.onNodeWithText("RECENT TOPICS").assertIsDisplayed()
        compose.onNodeWithText("VOICES TO FOLLOW").assertIsDisplayed()
        compose.onNodeWithText("TRENDING TOPICS").assertDoesNotExist()
        compose.onNodeWithText("0 followers", substring = true).assertDoesNotExist()
    }

    @Test fun emptyStateHasRetryAndRetainedContentSurvivesRefresh() {
        render()
        compose.onNodeWithText("No suggestions available yet").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retryCalls)
        setState(SearchDiscoveryState(
            users = listOf(person), peopleStatus = DiscoveryStatus.Ready, refreshing = true,
        ))
        compose.onNodeWithText("Alice").assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading suggestions").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertIsNotEnabled()
    }
}
