package com.unsilence.app.data.memory

import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.UnsilenceApp
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.TimelineEventLoader
import com.unsilence.app.data.relay.TimelineEventResolution
import com.unsilence.app.data.relay.TimelineService
import com.unsilence.app.ui.theme.UnsilenceTheme
import javax.inject.Provider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Offline Android store/flow rendering: no account, relay, signer or published event. */
@RunWith(AndroidJUnit4::class)
class ReactionDeletionAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun evictedDirectReactionsUpdateRenderedCountAndActorState() = verify("e")
    @Test fun evictedCoordinateReactionsUpdateRenderedCountAndActorState() = verify("a")

    private fun verify(tag: String) {
        val timeline = TimelineService(ApplicationProvider.getApplicationContext<UnsilenceApp>().subscription,
            object : TimelineEventLoader {
                override suspend fun getEvents(ids: List<String>): TimelineEventResolution = error("Offline fixture")
            })
        val store = MemoryEventStore(object : MuteKeyProvider {}, Provider { timeline })
        val target = event("target").copy(kind = if (tag == "e") 1 else 30023,
            tags = if (tag == "e") emptyList() else listOf(listOf("d", "fixture")))
        val key = if (tag == "e") target.id else "30023:${target.pubkey}:fixture"
        val first = event("first").copy(kind = 7, pubkey = "reactor", content = "+", tags = listOf(listOf(tag, key)))
        val second = first.copy(id = "second")
        store.insertBatch(listOf(target, first, second))
        store.insertBatch((0 until 1_000).map {
            first.copy(id = "pressure-$it", pubkey = "other", tags = listOf(listOf("e", "unrelated")))
        })
        assertTrue(store.snapshotEvictionMetrics().admissionReplaced >= 2)
        assertNull(store.getNostrEvent(first.id))
        assertNull(store.getNostrEvent(second.id))
        assertEquals(1, store.reactionCount(target.id))
        val stats = store.statsFlow(target.id)
        val actors = store.reactedEventIdsFlow(first.pubkey)
        compose.setContent {
            UnsilenceTheme {
                val counts by stats.collectAsState(store.currentStatsSnapshot(target.id))
                val reacted by actors.collectAsState(emptySet())
                Text("Reactions ${counts.reactionCount}; reacted ${target.id in reacted}")
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Reactions 1; reacted true").assertIsDisplayed()
        fun delete(reaction: NostrEvent) {
            store.insert(event("delete-${reaction.id}").copy(kind = 5, pubkey = reaction.pubkey,
                tags = listOf(listOf("e", reaction.id))))
        }
        delete(first)
        compose.waitForIdle()
        compose.onNodeWithText("Reactions 1; reacted true").assertIsDisplayed()
        assertFalse(store.insert(first))
        delete(second)
        compose.waitForIdle()
        compose.onNodeWithText("Reactions 0; reacted false").assertIsDisplayed()
        assertTrue(store.reactionEventIdsForTarget(first.pubkey, key).isEmpty())
        assertFalse(store.insert(second))
    }

    private fun event(id: String) = NostrEvent(id, "author", 1, "fixture", 1L,
        emptyList(), "", "wss://relay.example", null, null, false, null, 1L, mutableSetOf("wss://relay.example"))
}
