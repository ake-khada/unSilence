package com.unsilence.app.data.relay

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import com.unsilence.app.data.DEFAULT_WOT_PROVIDER_PUBKEY
import com.unsilence.app.data.DEFAULT_WOT_RELAY
import com.unsilence.app.domain.model.GlobalFeedLens
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPreferencesStoreTest {

    @Test
    fun `owner reset decision ignores missing and matching owners`() {
        val owner = "a".repeat(64)

        assertFalse(shouldResetWotPrefs(null, owner))
        assertFalse(shouldResetWotPrefs(owner, owner))
        assertFalse(shouldResetWotPrefs(owner.uppercase(), owner))
        assertTrue(shouldResetWotPrefs(owner, "b".repeat(64)))
    }

    @Test
    fun `missing owner stamps current account without discarding its legacy provider`() = runTest {
        val dataStore = newDataStore()
        val store = RelayPreferencesStore(dataStore, backgroundScope)
        val owner = "a".repeat(64)
        val customProvider = "c".repeat(64)

        store.setWotProvider(customProvider, "wss://custom.example", WotProviderSource.CUSTOM)
        store.ensureWotPrefsOwner(owner)

        assertEquals(
            WotProviderPrefs(customProvider, "wss://custom.example", WotProviderSource.CUSTOM),
            store.wotProviderPrefsSuspending(),
        )
        assertEquals(
            owner,
            dataStore.data.first()[stringPreferencesKey("wot_prefs_owner")],
        )
    }

    @Test
    fun `account switch defaults first-seen owner and restores returning owner`() = runTest {
        val dataStore = newDataStore()
        val store = RelayPreferencesStore(dataStore, backgroundScope)
        val accountA = "a".repeat(64)
        val accountB = "b".repeat(64)
        val providerA = "c".repeat(64)
        val providerB = "d".repeat(64)

        store.ensureWotPrefsOwner(accountA)
        store.setWotProvider(providerA, "wss://a.example", WotProviderSource.CUSTOM)
        store.setLastWotFetch(123L, "a-targets")
        store.setGlobalFeedLens(GlobalFeedLens.RAW)
        store.setFeedWotDisplayMode(FeedWotDisplayMode.OFF)

        store.ensureWotPrefsOwner(accountB)

        assertEquals(
            WotProviderPrefs(
                DEFAULT_WOT_PROVIDER_PUBKEY,
                DEFAULT_WOT_RELAY,
                WotProviderSource.DEFAULT,
            ),
            store.wotProviderPrefsSuspending(),
        )
        assertEquals(0L, store.lastWotFetchAt())
        assertEquals("", store.lastWotTargetsHash())
        assertEquals(accountB, dataStore.data.first()[stringPreferencesKey("wot_prefs_owner")])
        assertEquals(GlobalFeedLens.RAW, store.globalFeedLensFlow().first())
        assertEquals(FeedWotDisplayMode.OFF, store.feedWotDisplayModeFlow().first())

        store.setWotProvider(providerB, "wss://b.example", WotProviderSource.OWN_10040)
        store.setLastWotFetch(456L, "b-targets")
        store.ensureWotPrefsOwner(accountB)
        assertEquals(
            WotProviderPrefs(providerB, "wss://b.example", WotProviderSource.OWN_10040),
            store.wotProviderPrefsSuspending(),
        )
        assertEquals(456L, store.lastWotFetchAt())

        store.ensureWotPrefsOwner(accountA)

        assertEquals(
            WotProviderPrefs(providerA, "wss://a.example", WotProviderSource.CUSTOM),
            store.wotProviderPrefsSuspending(),
        )
        assertEquals(0L, store.lastWotFetchAt())
        assertEquals("", store.lastWotTargetsHash())
        assertEquals(accountA, dataStore.data.first()[stringPreferencesKey("wot_prefs_owner")])
    }

    private fun kotlinx.coroutines.test.TestScope.newDataStore() =
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = {
                Files.createTempDirectory("relay-prefs-test")
                    .resolve("relay.preferences_pb")
                    .toFile()
            },
        )
}
