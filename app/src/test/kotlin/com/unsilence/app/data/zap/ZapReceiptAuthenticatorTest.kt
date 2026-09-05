package com.unsilence.app.data.zap

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.NotificationRow
import com.unsilence.app.data.memory.VerifiedPrivateZap
import com.unsilence.app.data.memory.ZapAttribution
import com.unsilence.app.data.relay.EventDto
import com.unsilence.app.data.relay.NostrJson
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class ZapReceiptAuthenticatorTest {
    private val crypto = ZapCryptoTestFixture()
    private val senderSigner = crypto.sender
    private val zapperSigner = crypto.zapper
    private val attackerSigner = crypto.attacker
    private val sender = senderSigner.pubkey
    private val recipient = crypto.recipient.pubkey
    private val zapper = zapperSigner.pubkey
    private val target = crypto.defaultTarget

    @Test
    fun `bolt11 amount accepts valid checksum and rejects a tampered invoice`() {
        assertEquals(1_000_000L, decodeBolt11AmountMsats(ZapCryptoTestFixture.NIP57_1K_SAT_INVOICE))
        assertNull(decodeBolt11AmountMsats(tamper(ZapCryptoTestFixture.NIP57_1K_SAT_INVOICE)))
    }

    @Test
    fun `valid zapper receipt and signed request preserve sender and amount`() = runTest {
        val request = request(senderSigner)
        val decision = authenticateZapReceipt(
            receipt(zapperSigner, request),
            ownPubkey = recipient,
            ownAuthority = OwnZapServiceAuthority.Trusted(zapper),
        )

        val accepted = decision.accepted()
        assertEquals(1_000L, accepted.sats)
        assertEquals(ZapAttribution.Identified(sender, "great post"), accepted.attribution)
        assertNull(accepted.requestFailureRenderedAnonymous)
    }

    @Test
    fun `tampered request signature is credited anonymously only behind trusted zapper`() = runTest {
        val signed = request(senderSigner)
        val request = signed.copy(sig = tamper(signed.sig))
        val decision = authenticateZapReceipt(
            receipt(zapperSigner, request),
            ownPubkey = recipient,
            ownAuthority = OwnZapServiceAuthority.Trusted(zapper),
        )

        val accepted = decision.accepted()
        assertEquals(ZapAttribution.Unverified, accepted.attribution)
        assertEquals(1_000L, accepted.sats)
        assertEquals(ZapRequestFailure.INVALID_SIGNATURE, accepted.requestFailureRenderedAnonymous)
    }

    @Test
    fun `tampered attribution cannot bypass independent amount mismatch rejection`() = runTest {
        val signed = request(senderSigner, amountMsats = 2_000_000L)
        val tampered = signed.copy(sig = tamper(signed.sig))

        val rejected = authenticateZapReceipt(
            receipt(zapperSigner, tampered),
            ownPubkey = recipient,
            ownAuthority = OwnZapServiceAuthority.Trusted(zapper),
        ) as ZapReceiptDecision.Rejected

        assertEquals(ZapReceiptRejection.AMOUNT_MISMATCH, rejected.reason)
        assertEquals(ZapRequestFailure.INVALID_SIGNATURE, rejected.requestFailure)
    }

    @Test
    fun `swapped request pubkey cannot forge sender attribution`() = runTest {
        val signed = request(senderSigner)
        val swapped = signed.copy(pubkey = recipient)

        val accepted = authenticateZapReceipt(
            receipt(zapperSigner, swapped),
            ownPubkey = recipient,
            ownAuthority = OwnZapServiceAuthority.Trusted(zapper),
        ).accepted()

        assertEquals(ZapAttribution.Unverified, accepted.attribution)
        assertEquals(ZapRequestFailure.INVALID_SIGNATURE, accepted.requestFailureRenderedAnonymous)
    }

    @Test
    fun `receipt signed by anyone except own zapper is not credited`() = runTest {
        val decision = authenticateZapReceipt(
            receipt(attackerSigner, request(senderSigner)),
            ownPubkey = recipient,
            ownAuthority = OwnZapServiceAuthority.Trusted(zapper),
        )

        assertEquals(
            ZapReceiptRejection.WRONG_ZAPPER,
            (decision as ZapReceiptDecision.Rejected).reason,
        )
    }

    @Test
    fun `invoice and signed request amount mismatch is rejected`() = runTest {
        val request = request(senderSigner, amountMsats = 2_000_000L)
        val decision = authenticateZapReceipt(
            receipt(zapperSigner, request),
            ownPubkey = recipient,
            ownAuthority = OwnZapServiceAuthority.Trusted(zapper),
        )

        assertEquals(
            ZapReceiptRejection.AMOUNT_MISMATCH,
            (decision as ZapReceiptDecision.Rejected).reason,
        )
    }

    @Test
    fun `negative request amount cannot reduce aggregates`() = runTest {
        val request = request(senderSigner, amountMsats = -100_000_000L)
        val decision = authenticateZapReceipt(
            receipt(zapperSigner, request),
            ownPubkey = recipient,
            ownAuthority = OwnZapServiceAuthority.Trusted(zapper),
        )

        assertEquals(
            ZapReceiptRejection.INVALID_REQUEST_AMOUNT,
            (decision as ZapReceiptDecision.Rejected).reason,
        )
    }

    @Test
    fun `unverified request for another recipient is not credited anonymously`() = runTest {
        val signed = request(senderSigner)
        val forged = signed.copy(sig = tamper(signed.sig))
        val decision = authenticateZapReceipt(
            receipt(attackerSigner, forged),
            ownPubkey = "55".repeat(32),
            ownAuthority = OwnZapServiceAuthority.Unavailable,
        )

        val rejected = decision as ZapReceiptDecision.Rejected
        assertEquals(ZapReceiptRejection.UNVERIFIED_REQUEST_WITHOUT_ZAPPER_AUTHORITY, rejected.reason)
        assertEquals(ZapRequestFailure.INVALID_SIGNATURE, rejected.requestFailure)
    }

    @Test
    fun `own receipt waits rather than failing open while zapper authority is unavailable`() = runTest {
        val decision = authenticateZapReceipt(
            receipt(zapperSigner, request(senderSigner)),
            ownPubkey = recipient,
            ownAuthority = OwnZapServiceAuthority.Unavailable,
        )

        assertEquals(ZapReceiptDecision.PendingOwnAuthority, decision)
    }

    @Test
    fun `genuine signed anonymous request remains counted and anonymous`() = runTest {
        val anonymousSigner = crypto.identity("66")
        val request = request(anonymousSigner, anonCiphertext = "")
        val accepted = authenticateZapReceipt(
            receipt(zapperSigner, request),
            ownPubkey = recipient,
            ownAuthority = OwnZapServiceAuthority.Trusted(zapper),
        ).accepted()

        assertEquals(ZapAttribution.IntentionallyAnonymous, accepted.attribution)
        assertEquals(1_000L, accepted.sats)
        assertNotNull("anonymous signing may carry a private ciphertext, never an identity", accepted.privateEnvelope)
    }

    @Test
    fun `valid signed private payload authenticates recovered sender`() = runTest {
        val pending = pendingPrivateZap()
        val inner = signedEvent(
            signer = senderSigner,
            kind = 9733,
            tags = listOf(listOf("p", recipient), listOf("e", target)),
            content = "secret hello",
        )

        val decrypted = authenticatePrivateZapPayload(NostrJson.encodeToString(inner), pending)

        assertNotNull(decrypted)
        assertEquals(sender, decrypted!!.senderPubkey)
        assertEquals("secret hello", decrypted.comment)
    }

    @Test
    fun `private payload bad signature wrong kind recipient or target never patches sender`() = runTest {
        val pending = pendingPrivateZap()
        val valid = signedEvent(
            signer = senderSigner,
            kind = 9733,
            tags = listOf(listOf("p", recipient), listOf("e", target)),
            content = "secret hello",
        )
        val cases = listOf(
            valid.copy(sig = tamper(valid.sig)),
            signedEvent(senderSigner, 1, valid.tags, valid.content),
            signedEvent(senderSigner, 9733, listOf(listOf("p", attackerSigner.pubkey), listOf("e", target)), valid.content),
            signedEvent(senderSigner, 9733, listOf(listOf("p", recipient), listOf("e", "cd".repeat(32))), valid.content),
        )

        for (event in cases) {
            assertNull(authenticatePrivateZapPayload(NostrJson.encodeToString(event), pending))
        }
    }

    @Test
    fun `store exposes an authenticated receipt to totals drawer and notifications`() = runTest {
        val store = ownRecipientStore()
        val receipt = receipt(zapperSigner, request(senderSigner))

        store.insert(receipt)

        assertEquals(1, store.zapStats(target).count)
        assertEquals(1_000L, store.zapStats(target).totalSats)
        val detail = store.zapDetailsForEvent(target).single()
        assertEquals(ZapAttribution.Identified(sender, "great post"), detail.attribution)
        val notification = store.getNotifications(recipient, limit = 50).single() as NotificationRow.Grouped
        assertEquals(sender, notification.actors.single().pubkey)
        assertEquals(0, notification.anonymousCount)
        assertEquals(1_000L, notification.sumSats)
        assertEquals(MemoryEventStore.ZapAuthStats(finalized = 1, gate2Rejected = 0), store.zapAuthStatsForTest())
    }

    @Test
    fun `forged own sender claim is counted anonymously and is not self-suppressed`() = runTest {
        val store = ownRecipientStore()
        val signed = request(senderSigner)
        val forgedOwnClaim = signed.copy(pubkey = recipient)

        store.insert(receipt(zapperSigner, forgedOwnClaim))

        val detail = store.zapDetailsForEvent(target).single()
        assertEquals(ZapAttribution.Unverified, detail.attribution)
        assertEquals(1_000L, detail.sats)
        val notification = store.getNotifications(recipient, limit = 50).single() as NotificationRow.Grouped
        assertTrue(notification.actors.isEmpty())
        assertEquals(1, notification.anonymousCount)
        assertEquals(1_000L, notification.anonymousSats)
        assertEquals(MemoryEventStore.ZapAuthStats(finalized = 1, gate2Rejected = 1), store.zapAuthStatsForTest())
    }

    @Test
    fun `wrong zapper and invalid amount cannot change store aggregates`() = runTest {
        val wrongZapperStore = ownRecipientStore()
        val wrongZapperReceipt = receipt(attackerSigner, request(senderSigner))
        assertTrue(!wrongZapperStore.insert(wrongZapperReceipt))
        assertEquals(0, wrongZapperStore.zapStats(target).count)
        assertTrue(wrongZapperStore.getNotifications(recipient, limit = 50).isEmpty())
        assertNull(wrongZapperStore.getNostrEvent(wrongZapperReceipt.id))

        val negativeStore = ownRecipientStore()
        val negativeReceipt = receipt(zapperSigner, request(senderSigner, amountMsats = -100_000_000L))
        assertTrue(!negativeStore.insert(negativeReceipt))
        assertEquals(0, negativeStore.zapStats(target).count)
        assertTrue(negativeStore.getNotifications(recipient, limit = 50).isEmpty())
        assertNull(negativeStore.getNostrEvent(negativeReceipt.id))
    }

    @Test
    fun `pending own receipt materializes only after zapper authority resolves`() = runTest {
        val store = storeWithTarget()
        val pendingReceipt = receipt(zapperSigner, request(senderSigner))
        store.insert(pendingReceipt)

        assertEquals(0, store.zapStats(target).count)
        assertTrue(store.getNotifications(recipient, limit = 50).isEmpty())
        assertNotNull(store.getNostrEvent(pendingReceipt.id))

        store.updateOwnZapServiceAuthority(recipient, OwnZapServiceAuthority.Trusted(zapper))

        assertEquals(1, store.zapStats(target).count)
        assertEquals(1_000L, store.zapStats(target).totalSats)
        assertEquals(1, store.getNotifications(recipient, limit = 50).size)
    }

    @Test
    fun `pending receipt is discarded when resolved authority rejects its signer`() = runTest {
        val store = storeWithTarget()
        val pendingReceipt = receipt(attackerSigner, request(senderSigner))
        store.insert(pendingReceipt)
        assertNotNull(store.getNostrEvent(pendingReceipt.id))

        store.updateOwnZapServiceAuthority(recipient, OwnZapServiceAuthority.Trusted(zapper))

        assertNull(store.getNostrEvent(pendingReceipt.id))
        assertEquals(0, store.zapStats(target).count)
        assertTrue(store.getNotifications(recipient, limit = 50).isEmpty())
    }

    @Test
    fun `authority-pending forged owner mentions remain inside the kind cap`() {
        val store = storeWithTarget()
        repeat(300) { index ->
            store.insert(
                NostrEvent(
                    id = index.toString(16).padStart(64, '0'),
                    pubkey = attackerSigner.pubkey,
                    kind = 9735,
                    content = "",
                    createdAt = 1_675_000_000L + index,
                    tags = listOf(listOf("p", recipient)),
                    sig = "",
                    relayUrl = "wss://test.invalid",
                    replyToId = null,
                    rootId = null,
                    hasContentWarning = false,
                    contentWarningReason = null,
                    firstSeenAt = 1_675_000_000_000L + index,
                    relaysSeen = mutableSetOf("wss://test.invalid"),
                )
            )
        }

        assertTrue(store.snapshotSize().eventsByKind.getValue(9735) <= 250)
    }

    @Test
    fun `transient authority outage does not erase an already authenticated receipt`() = runTest {
        val store = ownRecipientStore()
        store.insert(receipt(zapperSigner, request(senderSigner)))

        store.updateOwnZapServiceAuthority(recipient, OwnZapServiceAuthority.Unavailable)

        assertEquals(1, store.zapStats(target).count)
        assertEquals(1_000L, store.zapStats(target).totalSats)
        assertEquals(ZapAttribution.Identified(sender, "great post"), store.zapDetailsForEvent(target).single().attribution)
    }

    @Test
    fun `snapshot receipt remains hidden until authority reauthenticates it`() = runTest {
        val source = ownRecipientStore()
        source.insert(receipt(zapperSigner, request(senderSigner)))
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { source.saveSnapshotBinary(it) }

        val restored = storeWithTarget(includeTarget = false)
        DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use {
            restored.restoreSnapshotBinary(it)
        }

        assertEquals(0, restored.zapStats(target).count)
        assertTrue(restored.zapDetailsForEvent(target).isEmpty())

        restored.updateOwnZapServiceAuthority(recipient, OwnZapServiceAuthority.Trusted(zapper))

        assertEquals(1, restored.zapStats(target).count)
        assertEquals(1_000L, restored.zapStats(target).totalSats)
        assertEquals(ZapAttribution.Identified(sender, "great post"), restored.zapDetailsForEvent(target).single().attribution)
    }

    @Test
    fun `genuine anonymous attribution remains distinct in the store`() = runTest {
        val store = ownRecipientStore()
        val anonymousSigner = crypto.identity("66")

        store.insert(receipt(zapperSigner, request(anonymousSigner, anonCiphertext = "")))

        assertEquals(
            ZapAttribution.IntentionallyAnonymous,
            store.zapDetailsForEvent(target).single().attribution,
        )
    }

    @Test
    fun `pending private zap work is derived replayed and removed after verification`() = runTest {
        val store = ownRecipientStore()
        val anonymousSigner = crypto.identity("66")
        val privateReceipt = receipt(
            zapperSigner,
            request(anonymousSigner, anonCiphertext = "opaque-ciphertext"),
        )
        store.insert(privateReceipt)

        val firstCollection = store.pendingPrivateZapDecrypts.first()
        val lateCollection = store.pendingPrivateZapDecrypts.first()
        assertEquals(setOf(privateReceipt.id), firstCollection.map { it.zapReceiptId }.toSet())
        assertEquals(firstCollection, lateCollection)

        store.acceptVerifiedPrivateZap(
            zapReceiptId = privateReceipt.id,
            verified = VerifiedPrivateZap(senderPubkey = sender, comment = "private"),
            targetId = target,
        )

        assertTrue(store.pendingPrivateZapDecrypts.first().isEmpty())
    }

    @Test
    fun `verified coordinate receipt resolves to article and collapses optimistic row`() = runTest {
        val fixture = ZapCryptoTestFixture()
        val articleId = "cd".repeat(32)
        val coordinate = "30023:${fixture.recipient.pubkey}:article"
        val store = MemoryEventStore(
            object : MuteKeyProvider {},
            com.unsilence.app.data.relay.stubTimelineServiceProvider(),
        ).also {
            it.ownPubkey = fixture.recipient.pubkey
            it.registerArticleCoord(articleId, coordinate)
            it.updateOwnZapServiceAuthority(
                fixture.recipient.pubkey,
                OwnZapServiceAuthority.Trusted(fixture.zapper.pubkey),
            )
            it.addOptimisticZapDetail(articleId, fixture.sender.pubkey, 1_000, "great post")
        }
        val request = fixture.request(targetTagName = "a", targetId = coordinate)

        store.insert(
            fixture.receipt(
                request = request,
                targetTagName = "a",
                targetId = coordinate,
            )
        )

        assertEquals(1, store.zapStats(articleId).count)
        assertEquals(1_000L, store.zapStats(articleId).totalSats)
        val rows = store.zapDetailsForEvent(articleId)
        assertEquals(1, rows.size)
        assertNotNull(rows.single().eventId)
        assertEquals(fixture.sender.pubkey, rows.single().senderPubkey)
    }

    private fun ownRecipientStore(): MemoryEventStore = storeWithTarget().also {
        it.updateOwnZapServiceAuthority(recipient, OwnZapServiceAuthority.Trusted(zapper))
    }

    private fun storeWithTarget(includeTarget: Boolean = true): MemoryEventStore = MemoryEventStore(
        object : MuteKeyProvider {},
        com.unsilence.app.data.relay.stubTimelineServiceProvider(),
    ).also { store ->
        store.ownPubkey = recipient
        if (includeTarget) store.insert(
            NostrEvent(
                id = target,
                pubkey = recipient,
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

    private suspend fun request(
        signer: ZapCryptoTestFixture.Identity,
        amountMsats: Long = 1_000_000L,
        anonCiphertext: String? = null,
    ): EventDto = crypto.request(signer, amountMsats = amountMsats, anonCiphertext = anonCiphertext)

    private suspend fun receipt(signer: ZapCryptoTestFixture.Identity, request: EventDto) =
        crypto.receipt(signer, request)

    private suspend fun signedEvent(
        signer: ZapCryptoTestFixture.Identity,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): EventDto = crypto.signedEvent(signer, kind, tags, content)

    private fun pendingPrivateZap() = crypto.pendingPrivateZap()

    private fun tamper(value: String): String = crypto.tamper(value)

    private fun ZapReceiptDecision.accepted(): AuthenticatedZapReceipt {
        assertTrue("expected accepted, got $this", this is ZapReceiptDecision.Accepted)
        return (this as ZapReceiptDecision.Accepted).receipt
    }
}
