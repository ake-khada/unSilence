package com.unsilence.app.ui.profile

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.ui.theme.UnsilenceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MutedUserRowTest {
    @get:Rule val compose = createComposeRule()
    private val pubkey = "7".repeat(64)

    @Test fun tappingNamedAndUnresolvedRowsOpensTheSelectedPubkey() {
        val opened = mutableListOf<String>()
        var removals = 0
        val profile = mutableStateOf<UserEntity?>(UserEntity(pubkey, displayName = "Muted fixture"))
        compose.setContent {
            UnsilenceTheme {
                MutedUserRow(pubkey, profile.value, onOpenProfile = opened::add, onRemove = { removals++ })
            }
        }
        compose.onNodeWithText("Muted fixture").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf(pubkey), opened)
            assertEquals(0, removals)
            profile.value = null
        }
        compose.onNodeWithText("77777777…").assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals(listOf(pubkey, pubkey), opened)
            assertEquals(0, removals)
        }
    }

    @Test fun unmuteIsAnIndependentTouchAndAccessibilityAction() {
        var opens = 0
        var removals = 0
        compose.setContent {
            UnsilenceTheme {
                MutedUserRow(pubkey, null, onOpenProfile = { opens++ }, onRemove = { removals++ })
            }
        }
        val unmute = compose.onNodeWithContentDescription("Unmute")
        unmute.assertIsDisplayed().assertHasClickAction().performTouchInput { click() }
        unmute.performClick()
        compose.runOnIdle {
            assertEquals(0, opens)
            assertEquals(2, removals)
        }
    }
}
