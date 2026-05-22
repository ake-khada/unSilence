package com.unsilence.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsPrefs: DataStore<Preferences> by preferencesDataStore(name = "settings_prefs")

private val KEY_PINNED_EMOJI = stringSetPreferencesKey("pinned_emoji_shortcodes")

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore get() = context.settingsPrefs
    private val editMutex = Mutex()

    private val _pinnedEmojiShortcodes = MutableStateFlow<Set<String>>(emptySet())
    val pinnedEmojiShortcodes: StateFlow<Set<String>> = _pinnedEmojiShortcodes.asStateFlow()

    suspend fun initialize() {
        val prefs = dataStore.data.first()
        _pinnedEmojiShortcodes.value = prefs[KEY_PINNED_EMOJI] ?: emptySet()
    }

    suspend fun setPinnedEmojiShortcodes(shortcodes: Set<String>) {
        _pinnedEmojiShortcodes.value = shortcodes
        editMutex.withLock {
            dataStore.edit { it[KEY_PINNED_EMOJI] = shortcodes }
        }
    }
}
