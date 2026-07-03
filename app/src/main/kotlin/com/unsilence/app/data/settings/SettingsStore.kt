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

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore get() = context.settingsPrefs
    private val editMutex = Mutex()

    private val _activeOwner = MutableStateFlow<String?>(null)
    private val _pinnedEmojiShortcodes = MutableStateFlow<Set<String>>(emptySet())
    val pinnedEmojiShortcodes: StateFlow<Set<String>> = _pinnedEmojiShortcodes.asStateFlow()

    fun selectOwner(pubkeyHex: String) {
        _activeOwner.value = pubkeyHex.lowercase()
    }

    fun clearActiveOwner() {
        _activeOwner.value = null
        _pinnedEmojiShortcodes.value = emptySet()
    }

    suspend fun initialize() {
        val prefs = dataStore.data.first()
        val owner = _activeOwner.value ?: return
        _pinnedEmojiShortcodes.value = prefs[pinnedEmojiKey(owner)] ?: emptySet()
    }

    suspend fun setPinnedEmojiShortcodes(shortcodes: Set<String>) {
        val owner = _activeOwner.value ?: return
        _pinnedEmojiShortcodes.value = shortcodes
        editMutex.withLock {
            dataStore.edit { it[pinnedEmojiKey(owner)] = shortcodes }
        }
    }

    private fun pinnedEmojiKey(owner: String) =
        stringSetPreferencesKey("${owner}_pinned_emoji_shortcodes")
}
