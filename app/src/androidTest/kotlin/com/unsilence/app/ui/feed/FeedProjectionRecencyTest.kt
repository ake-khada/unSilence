package com.unsilence.app.ui.feed

import android.os.Build
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.unsilence.app.MainActivity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.toNostrEvent
import com.unsilence.app.ui.shared.DisplayWindowReporter
import com.unsilence.app.ui.thread.ThreadViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FeedProjectionRecencyTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun projectingAnUnseenPagePreservesRecencyUntilRowsBecomeVisible() {
        val activity = ui.activity
        check(Build.HARDWARE in listOf("ranchu", "goldfish") && activity.packageName.endsWith(".dev"))
        assertNull(activity.keyManager.getPublicKeyHex())
        val store = ui.runOnIdle {
            val thread = ViewModelProvider(activity)[ThreadViewModel::class.java]
            ThreadViewModel::class.java.getDeclaredField("memoryEventStore").apply { isAccessible = true }
                .get(thread) as MemoryEventStore
        }
        val previousOwner = store.ownPubkey
        store.ownPubkey = "2".repeat(64)
        try {
            val feed = ui.runOnIdle { ViewModelProvider(activity)[FeedViewModel::class.java].apply {
                setFeedType(FeedType.Following)
            } }
            val now = System.currentTimeMillis() / 1000
            val notes = (1..24).map { i ->
                EventDto(id = "%064x".format(i), pubkey = "3".repeat(64), createdAt = now - i,
                    kind = 1, tags = emptyList(), content = "Projected row $i", sig = "0".repeat(128))
                    .toNostrEvent("")
            }
            store.insertBatch(notes)
            @Suppress("UNCHECKED_CAST")
            val touched = MemoryEventStore::class.java.getDeclaredField("lastTouchedAt").apply { isAccessible = true }
                .get(store) as MutableMap<String, Long>
            notes.forEach { touched[it.id] = 1L }
            ui.runOnIdle {
                FeedViewModel::class.java.getDeclaredMethod("handleHistory", List::class.java)
                    .apply { isAccessible = true }.invoke(feed, notes)
            }
            ui.waitUntil(10_000) { feed.presentation.value.rows.size == notes.size }
            val rows = feed.presentation.value.rows
            assertTrue("Projection renewed unseen rows", rows.all { touched[it.id] == 1L })
            DisplayWindowReporter(store).use { reporter ->
                reporter.setActive(true)
                val visible = setOf(rows[4].id, rows[5].id)
                reporter.prepareWarmWindow(rows, 4, 5, visible, 360)
                assertEquals(visible, rows.filter { touched[it.id] != 1L }.map { it.id }.toSet())
                assertTrue(rows[6].id in store.displayPriorities())
                assertFalse(rows.last().id in store.displayPriorities())
            }
        } finally {
            ui.activityRule.scenario.close()
            store.ownPubkey = previousOwner
        }
    }
}
