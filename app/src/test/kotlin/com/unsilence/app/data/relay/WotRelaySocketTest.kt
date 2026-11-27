package com.unsilence.app.data.relay

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.unsilence.app.data.WOT_REGISTRY_LOOKUP_RELAYS
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.zap.ZapCryptoTestFixture
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WotRelaySocketTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun movedSignerIsFoundBeforeSubjectsAreSentAndEmptyServiceCannotWithdrawScores() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val data = PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("relay.preferences_pb") }
        try {
            EngagementCoverageSocketTest.Fixture(maxSubjects = 1, fixtureRelays = WOT_REGISTRY_LOOKUP_RELAYS.toSet(),
                preferences = Lazy { RelayPreferencesStore(data, scope) }).use { f ->
                val keys = ZapCryptoTestFixture()
                val signer = keys.sender.pubkey
                val subject = keys.recipient.pubkey
                val readOnly = "wss://inbox.test"
                val wrong = "wss://other.test"
                val moved = "wss://future.test"
                val indexer = "wss://nos.lol"
                val hint = "wss://previous.test"
                val tags = listOf(listOf("d", subject), listOf("rank", "0"))
                val score = keys.signedEvent(keys.sender, 30382, tags, "")
                f.remote[wrong] = listOf(keys.signedEvent(keys.attacker, 30382, tags, ""))
                f.remote[indexer] = listOf(keys.signedEvent(keys.sender, 10002, listOf(
                    listOf("r", readOnly, "read"), listOf("r", wrong), listOf("r", moved, "write")
                ), ""))
                f.remote[moved] = listOf(score)
                f.store.ownPubkey = subject
                f.store.setActiveWotProvider(signer, hint)
                suspend fun fetch(target: String = subject) = f.pool.fetchWotAssertions(signer, hint, listOf(target))

                assertTrue(fetch())
                assertEquals(moved, f.store.effectiveWotProvider().relayHint)
                assertEquals(hint, f.store.activeWotProvider().relayHint)
                assertEquals(0, (f.store.wotFor(subject) as WotLookup.Scored).assertion.rank)
                assertFalse(f.requests.any { it.first == readOnly })
                assertTrue(f.requests.any { (url, req) -> url == hint && "#d" !in req[2].jsonObject })
                assertEquals(setOf(moved), f.requests.filter { "#d" in it.second[2].jsonObject }.map { it.first }.toSet())

                assertTrue(f.pool.fetchWotAssertions(signer, hint, listOf(subject, keys.attacker.pubkey)))
                assertEquals(moved, f.store.wotQueryRelay)
                assertEquals(WotLookup.Absent, f.store.wotFor(keys.attacker.pubkey))

                f.remote[moved] = emptyList()
                assertFalse(fetch())
                assertTrue(f.store.wotFor(subject) is WotLookup.Scored)
            }
        } finally {
            scope.cancel()
        }
    }
}
