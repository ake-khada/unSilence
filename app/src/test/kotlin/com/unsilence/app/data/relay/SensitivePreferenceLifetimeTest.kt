package com.unsilence.app.data.relay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.unsilence.app.data.memory.SensitiveContentMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SensitivePreferenceLifetimeTest {
    private class PreferencesStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            return transform(data.value).also { data.value = it }
        }
    }

    @Test fun `preference fails closed before load then keeps current without screen subscribers`() = runTest {
        val source = PreferencesStore()
        val store = RelayPreferencesStore(source, backgroundScope)
        assertEquals(SensitiveContentMode.HIDE, store.sensitiveContentMode.value)
        runCurrent()
        assertEquals(SensitiveContentMode.BLUR, store.sensitiveContentMode.value)

        store.setSensitiveContentMode(SensitiveContentMode.SHOW)
        runCurrent()
        assertEquals(SensitiveContentMode.SHOW, store.sensitiveContentMode.value)
        // No UI collection is running when the preference changes.
        store.setSensitiveContentMode(SensitiveContentMode.HIDE)
        runCurrent()
        assertEquals(SensitiveContentMode.HIDE, store.sensitiveContentMode.value)
    }

    @Test fun `screens share one live preference owner rather than stopped copies`() = runTest {
        val source = PreferencesStore()
        source.data.value = preferencesOf(stringPreferencesKey("sensitive_content_mode") to "SHOW")
        val store = RelayPreferencesStore(source, backgroundScope)
        runCurrent()
        val upstreamCount = source.data.subscriptionCount.value
        val jobs = List(10) { backgroundScope.launch { store.sensitiveContentMode.collect {} } }
        runCurrent()
        assertSame(store.sensitiveContentMode, store.sensitiveContentMode)
        assertEquals(upstreamCount, source.data.subscriptionCount.value)
        jobs.forEach { it.cancel() }
        runCurrent()
        store.setSensitiveContentMode(SensitiveContentMode.HIDE)
        runCurrent()
        assertEquals(SensitiveContentMode.HIDE, store.sensitiveContentMode.value)
        assertEquals(upstreamCount, source.data.subscriptionCount.value)
    }
}
