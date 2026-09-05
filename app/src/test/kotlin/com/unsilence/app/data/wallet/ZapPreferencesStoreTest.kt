package com.unsilence.app.data.wallet

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ZapPreferencesStoreTest {

    @Test
    fun `trusted zapper keys survive store recreation and union provider rotations`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = {
                Files.createTempDirectory("zap-prefs-test")
                    .resolve("zap.preferences_pb")
                    .toFile()
            },
        )
        val owner = "a".repeat(64)
        val keyA = "b".repeat(64)
        val keyB = "c".repeat(64)
        val firstStore = ZapPreferencesStore(dataStore, backgroundScope)

        assertEquals(
            setOf(keyA),
            firstStore.rememberTrustedZapperPubkeys(owner, setOf(keyA)),
        )

        val restartedStore = ZapPreferencesStore(dataStore, backgroundScope)
        assertEquals(setOf(keyA), restartedStore.trustedZapperPubkeys(owner))
        assertEquals(
            setOf(keyA, keyB),
            restartedStore.rememberTrustedZapperPubkeys(owner, setOf(keyB)),
        )
        assertEquals(setOf(keyA, keyB), restartedStore.trustedZapperPubkeys(owner))
    }
}
