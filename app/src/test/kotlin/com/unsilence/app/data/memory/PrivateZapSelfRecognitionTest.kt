package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.zap.ZapCryptoTestFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Sender-local recognition for a cryptographically-valid private zap request. */
class PrivateZapSelfRecognitionTest {
    private lateinit var store: MemoryEventStore
    private lateinit var crypto: ZapCryptoTestFixture

    @Before
    fun setUp() {
        crypto = ZapCryptoTestFixture()
        store = newStore()
        store.ownPubkey = crypto.sender.pubkey
        insertTarget(store)
    }

    @Test
    fun `register then verified anonymous receipt promotes to own and collapses`() = runTest {
        val prepared = privateReceipt(crypto.identity("66"))
        store.addOptimisticZapDetail(crypto.defaultTarget, crypto.sender.pubkey, 1_000, "gm")
        store.registerPendingPrivateZap(crypto.defaultTarget, prepared.requestSignerPubkey)

        store.insert(prepared.receipt)

        val rows = store.zapDetailsForEvent(crypto.defaultTarget)
        assertEquals(1, rows.size)
        assertEquals(crypto.sender.pubkey, rows.single().senderPubkey)
        assertTrue(rows.single().eventId != null)
        assertTrue(store.hasOwnZapReceipt(crypto.defaultTarget))
        assertEquals(1, store.zapStats(crypto.defaultTarget).count)
        assertEquals(1_000L, store.zapStats(crypto.defaultTarget).totalSats)
    }

    @Test
    fun `register after verified anonymous receipt reconciles it`() = runTest {
        val prepared = privateReceipt(crypto.identity("66"))
        store.addOptimisticZapDetail(crypto.defaultTarget, crypto.sender.pubkey, 1_000, "gm")
        store.insert(prepared.receipt)
        assertEquals(2, store.zapDetailsForEvent(crypto.defaultTarget).size)

        assertTrue(store.registerPendingPrivateZap(crypto.defaultTarget, prepared.requestSignerPubkey))

        val rows = store.zapDetailsForEvent(crypto.defaultTarget)
        assertEquals(1, rows.size)
        assertEquals(crypto.sender.pubkey, rows.single().senderPubkey)
        assertTrue(store.hasOwnZapReceipt(crypto.defaultTarget))
    }

    @Test
    fun `unrelated verified anonymous receipt remains intentionally anonymous`() = runTest {
        val ownPrepared = privateReceipt(crypto.identity("66"))
        val otherPrepared = privateReceipt(crypto.identity("77"))
        store.registerPendingPrivateZap(crypto.defaultTarget, ownPrepared.requestSignerPubkey)

        store.insert(otherPrepared.receipt)

        val row = store.zapDetailsForEvent(crypto.defaultTarget).single()
        assertEquals(ZapAttribution.IntentionallyAnonymous, row.attribution)
        assertFalse(store.hasOwnZapReceipt(crypto.defaultTarget))
    }

    @Test
    fun `verified public own zap still collapses optimistic row`() = runTest {
        store.addOptimisticZapDetail(crypto.defaultTarget, crypto.sender.pubkey, 1_000, "great post")
        val request = crypto.request(
            signer = crypto.sender,
            recipientPubkey = crypto.recipient.pubkey,
        )
        store.insert(
            crypto.receipt(
                request = request,
                recipientPubkey = crypto.recipient.pubkey,
            )
        )

        val rows = store.zapDetailsForEvent(crypto.defaultTarget)
        assertEquals(1, rows.size)
        assertEquals(crypto.sender.pubkey, rows.single().senderPubkey)
        assertTrue(store.hasOwnZapReceipt(crypto.defaultTarget))
    }

    @Test
    fun `pending anonymous signer mapping survives snapshot before receipt`() = runTest {
        val prepared = privateReceipt(crypto.identity("66"))
        store.addOptimisticZapDetail(crypto.defaultTarget, crypto.sender.pubkey, 1_000, "gm")
        store.registerPendingPrivateZap(crypto.defaultTarget, prepared.requestSignerPubkey)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { store.saveSnapshotBinary(it) }

        val restored = newStore()
        restored.ownPubkey = crypto.sender.pubkey
        DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use {
            restored.restoreSnapshotBinary(it)
        }
        restored.insert(prepared.receipt)

        val rows = restored.zapDetailsForEvent(crypto.defaultTarget)
        assertEquals(1, rows.size)
        assertEquals(crypto.sender.pubkey, rows.single().senderPubkey)
        assertTrue(rows.single().eventId != null)
    }

    private data class PreparedPrivateReceipt(
        val receipt: NostrEvent,
        val requestSignerPubkey: String,
    )

    private suspend fun privateReceipt(anon: ZapCryptoTestFixture.Identity): PreparedPrivateReceipt {
        val request = crypto.request(
            signer = anon,
            recipientPubkey = crypto.recipient.pubkey,
            anonCiphertext = "",
        )
        return PreparedPrivateReceipt(
            receipt = crypto.receipt(
                request = request,
                recipientPubkey = crypto.recipient.pubkey,
            ),
            requestSignerPubkey = request.pubkey,
        )
    }

    private fun insertTarget(targetStore: MemoryEventStore) {
        targetStore.insert(
            NostrEvent(
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
        )
    }

    private fun newStore() = MemoryEventStore(
        object : MuteKeyProvider {},
        com.unsilence.app.data.relay.stubTimelineServiceProvider(),
    )
}
