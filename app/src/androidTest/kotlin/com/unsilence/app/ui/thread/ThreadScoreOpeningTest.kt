package com.unsilence.app.ui.thread

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.unsilence.app.MainActivity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.WotAssertionEntity
import com.unsilence.app.data.memory.WotScoreSnapshot
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.WotScoreFilter
import com.unsilence.app.data.relay.toNostrEvent
import com.unsilence.app.ui.common.ProvideMinuteClock
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ThreadScoreOpeningTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun knownScoresArriveWithTheFirstCardAndFirstReplyBatch() {
        val activity = ui.activity
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && activity.keyManager.getPublicKeyHex() == null)
        val vm = ui.runOnIdle { ViewModelProvider(activity)[ThreadViewModel::class.java] }
        val store = ThreadViewModel::class.java.getDeclaredField("memoryEventStore")
            .apply { isAccessible = true }.get(vm) as MemoryEventStore
        val prefs = activity.relayPreferencesStore
        val initial = runBlocking { prefs.wotScoreFilter.filterNotNull().first() }
        val previousOwner = store.ownPubkey
        val event = EventDto(id = "a".repeat(64), pubkey = "b".repeat(64), kind = 1,
            content = "Cached post at the threshold", createdAt = 100, tags = emptyList(), sig = "0".repeat(128)).toNostrEvent("")
        val owner = "c".repeat(64)
        val provider = store.activeWotProvider().providerPubkey
        fun reply(id: String, author: String) = event.copy(
            id = id.repeat(64), pubkey = author, content = "Reply $id", rootId = event.id, replyToId = event.id,
            tags = listOf(listOf("e", event.id, "", "root")),
        )
        val accepted = reply("1", event.pubkey)
        val below = reply("2", "d".repeat(64))
        val unknown = reply("3", "e".repeat(64))
        val entrance = ui.runOnIdle {
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
            }
        }
        try {
            store.ownPubkey = owner
            store.insertBatch(listOf(event, accepted, below, unknown))
            store.feedRowsByIds(setOf(event.id))
            store.restoreWotScores(WotScoreSnapshot(owner, provider, listOf(
                WotAssertionEntity(event.pubkey, provider, 50, confirmedAt = System.currentTimeMillis()),
                WotAssertionEntity(below.pubkey, provider, 49, confirmedAt = System.currentTimeMillis()),
            )))
            runBlocking {
                prefs.setMinimumWotScore(50)
                prefs.wotScoreFilter.first { it == WotScoreFilter(true, 50) }
            }
            ui.runOnUiThread {
                activity.setContent {
                    UnsilenceTheme {
                        CompositionLocalProvider(LocalLifecycleOwner provides entrance) {
                            ProvideMinuteClock {
                                ThreadScreen(onArticleClick = { _, _ -> }, eventId = event.id,
                                    entryId = "opening", onDismiss = {}, viewModel = vm)
                            }
                        }
                    }
                }
            }
            assertEquals(emptyMap<String, Any>(), vm.wotLookups.value)
            ui.onNodeWithText(event.content).assertIsDisplayed()
            ui.runOnIdle { entrance.lifecycle.currentState = Lifecycle.State.RESUMED }
            val firstBatch = runBlocking {
                withTimeout(10_000) { vm.uiState.first { it.replies.any { reply -> reply.row.id == accepted.id } } }
            }
            assertEquals(50, (firstBatch.authorScores[accepted.pubkey] as WotLookup.Scored).assertion.rank)
            assertEquals(49, (firstBatch.authorScores[below.pubkey] as WotLookup.Scored).assertion.rank)
            assertEquals(WotLookup.Pending, firstBatch.authorScores[unknown.pubkey])
            ui.onNodeWithText(accepted.content).assertIsDisplayed()
            ui.onNodeWithText(below.content).assertDoesNotExist()
            ui.onNodeWithText(unknown.content).assertDoesNotExist()
            ui.onNodeWithText("No current WoT score", substring = true).assertExists()

            store.restoreWotScores(WotScoreSnapshot(owner, provider, listOf(
                WotAssertionEntity(unknown.pubkey, provider, 75, confirmedAt = System.currentTimeMillis()),
            )))
            ui.waitUntil(10_000) {
                ui.onAllNodes(androidx.compose.ui.test.hasText(unknown.content)).fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithText(below.content).assertDoesNotExist()
        } finally {
            ui.activityRule.scenario.close()
            runBlocking {
                prefs.setMinimumWotScore(initial.minimum)
                if (!initial.enabled) prefs.setMinimumWotScore(0)
            }
            store.ownPubkey = previousOwner
        }
    }
}
