package com.unsilence.app.data.relay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.unsilence.app.domain.model.AutomaticMediaMode
import com.unsilence.app.domain.model.AutomaticMediaPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticMediaPreferenceTest {
    private class Store : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value).also { data.value = it }
    }

    @Test fun `wait for disk before using defaults and keep choices current without subscribers`() = runTest {
        val source = Store()
        val store = RelayPreferencesStore(source, backgroundScope)
        assertNull(store.automaticMediaPreferences.value)
        runCurrent()
        assertEquals(AutomaticMediaPreferences(), store.automaticMediaPreferences.value)
        store.setVideoAutoplayMode(AutomaticMediaMode.NEVER)
        store.setImageLoadingMode(AutomaticMediaMode.UNMETERED_ONLY)
        runCurrent()
        assertEquals(AutomaticMediaPreferences(AutomaticMediaMode.NEVER, AutomaticMediaMode.UNMETERED_ONLY), store.automaticMediaPreferences.value)
        assertEquals("NEVER", source.data.value[stringPreferencesKey("video_autoplay_mode")])
        assertEquals("UNMETERED_ONLY", source.data.value[stringPreferencesKey("image_loading_mode")])
        // Simulate recreation: device preferences survive without an account key.
        val reopened = RelayPreferencesStore(source, backgroundScope)
        runCurrent()
        assertEquals(store.automaticMediaPreferences.value, reopened.automaticMediaPreferences.value)
        store.setLinkPreviewMode(AutomaticMediaMode.NEVER)
        runCurrent()
        assertEquals(AutomaticMediaMode.NEVER, reopened.automaticMediaPreferences.value?.previews)
    }

    @Test fun `unknown stored values fall back independently without overwriting valid settings`() = runTest {
        val source = Store()
        source.data.value = preferencesOf(
            stringPreferencesKey("video_autoplay_mode") to "FUTURE",
            stringPreferencesKey("image_loading_mode") to "NEVER",
            stringPreferencesKey("link_preview_mode") to "UNKNOWN",
        )
        val store = RelayPreferencesStore(source, backgroundScope)
        runCurrent()
        assertEquals(AutomaticMediaPreferences(images = AutomaticMediaMode.NEVER), store.automaticMediaPreferences.value)
        store.setLinkPreviewMode(AutomaticMediaMode.UNMETERED_ONLY)
        runCurrent()
        assertEquals("FUTURE", source.data.value[stringPreferencesKey("video_autoplay_mode")])
        assertEquals(AutomaticMediaMode.NEVER, store.automaticMediaPreferences.value?.images)
    }
}
