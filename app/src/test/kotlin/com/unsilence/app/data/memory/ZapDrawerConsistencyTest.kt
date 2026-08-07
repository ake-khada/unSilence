package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.zap.OwnZapServiceAuthority
import com.unsilence.app.data.zap.ZapCryptoTestFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Snapshot repair must rebuild drawer rows only from authenticated receipts. */
class ZapDrawerConsistencyTest {
    private lateinit var store: MemoryEventStore
    private lateinit var crypto: ZapCryptoTestFixture

    @Before
    fun setUp() {
        crypto = ZapCryptoTestFixture()
        store = MemoryEventStore(
            object : MuteKeyProvider {},
            com.unsilence.app.data.relay.stubTimelineServiceProvider(),
        )
        store.ownPubkey = crypto.recipient.pubkey
        store.updateOwnZapServiceAuthority(
            crypto.recipient.pubkey,
            OwnZapServiceAuthority.Trusted(crypto.zapper.pubkey),
        )
    }

    @Test
    fun `repair reconstructs a verified row from a restored receipt`() = runTest {
        store.insertFromSnapshot(targetEvent())
        store.insertFromSnapshot(crypto.receipt(request = crypto.request()))

        store.repairZapDetailsFromReceipts()

        val row = store.zapDetailsForEvent(crypto.defaultTarget).single()
        assertEquals(crypto.sender.pubkey, row.senderPubkey)
        assertEquals(1_000L, row.sats)
        assertEquals(1, store.zapStats(crypto.defaultTarget).count)
        assertEquals(1_000L, store.zapStats(crypto.defaultTarget).totalSats)
    }

    @Test
    fun `repair is idempotent and cannot duplicate a receipt`() = runTest {
        store.insertFromSnapshot(targetEvent())
        store.insertFromSnapshot(crypto.receipt(request = crypto.request()))

        store.repairZapDetailsFromReceipts()
        store.repairZapDetailsFromReceipts()

        assertEquals(1, store.zapDetailsForEvent(crypto.defaultTarget).count { it.eventId != null })
        assertEquals(1, store.zapStats(crypto.defaultTarget).count)
        assertEquals(1_000L, store.zapStats(crypto.defaultTarget).totalSats)
    }

    private fun targetEvent() = NostrEvent(
        id = crypto.defaultTarget,
        pubkey = crypto.recipient.pubkey,
        kind = 1,
        content = "target",
        createdAt = 1_674_999_000L,
        tags = emptyList(),
        sig = "",
        relayUrl = "wss://test.invalid",
        replyToId = null,
        rootId = null,
        hasContentWarning = false,
        contentWarningReason = null,
        firstSeenAt = 1_674_999_000_000L,
        relaysSeen = mutableSetOf("wss://test.invalid"),
    )
}
