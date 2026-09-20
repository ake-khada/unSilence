package com.unsilence.app.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `notification education is initially visible and dismissal survives store recreation`() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.root.resolve("settings.preferences_pb")
        }
        val original = SettingsStore(data)
        assertFalse(original.notificationFilterHintDismissed.first())
        original.dismissNotificationFilterHint()
        val recreated = SettingsStore(data)
        assertTrue(recreated.notificationFilterHintDismissed.first())
        recreated.dismissNotificationFilterHint()
        assertTrue(recreated.notificationFilterHintDismissed.first())
    }

    @Test fun `dismissing education preserves account preferences and is not reset by account changes`() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.root.resolve("settings.preferences_pb")
        }
        val store = SettingsStore(data)
        store.selectOwner("a".repeat(64))
        store.setPinnedEmojiShortcodes(setOf("wave"))
        store.dismissNotificationFilterHint()
        store.clearActiveOwner()
        store.selectOwner("b".repeat(64))
        store.initialize()
        assertTrue(store.notificationFilterHintDismissed.first())
        assertEquals(emptySet<String>(), store.pinnedEmojiShortcodes.value)
        store.selectOwner("a".repeat(64))
        store.initialize()
        assertEquals(setOf("wave"), store.pinnedEmojiShortcodes.value)
    }
}
