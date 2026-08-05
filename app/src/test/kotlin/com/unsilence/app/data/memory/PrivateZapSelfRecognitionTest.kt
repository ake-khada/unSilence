package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Private-zap self-recognition (Commit 2). A private zap to another user is
 * anon-signed, so the kind-9735 receipt's embedded kind-9734 carries the anon
 * pubkey (== the outgoing zap request's signed.pubKey by NIP-57), not ours.
 * Without recognition the optimistic own row and the anon receipt row both
 * survive dedup → drawer shows two rows, count doubles.
 *
 * Fix = PATCH-AS-OWN: we register our outgoing private-zap anon pubkey; MES
 * rewrites a matching receipt's sender → own BEFORE dedup so the existing
 * (sender, sats) dedup collapses it against the optimistic own row. The
 * promotion is sender-local only — never published, the receipt stays
 * anonymous to every other client.
 */
class PrivateZapSelfRecognitionTest {

    private lateinit var store: MemoryEventStore

    @Before
    fun setUp() {
        store = MemoryEventStore(object : MuteKeyProvider {}, com.unsilence.app.data.relay.stubTimelineServiceProvider())
    }

    private val author = "f".repeat(64)
    private val own = "0".repeat(64)
    private val anon = "a".repeat(64)
    private val otherAnon = "b".repeat(64)

    private fun event(id: String, pubkey: String, kind: Int, tags: List<List<String>> = emptyList()) = NostrEvent(
        id = id, pubkey = pubkey, kind = kind, content = "",
        createdAt = System.currentTimeMillis() / 1000, tags = tags,
        sig = "sig", relayUrl = "wss://r.example", replyToId = null, rootId = null,
        hasContentWarning = false, contentWarningReason = null,
        firstSeenAt = System.currentTimeMillis(), relaysSeen = mutableSetOf("wss://r.example"),
    )

    private fun note(id: String) = event(id = id, pubkey = author, kind = 1)

    private fun zapReceipt(id: String, target: String, senderInDesc: String, sats: Long) = event(
        id = id, pubkey = "lnurl".padEnd(64, '0'), kind = 9735,
        tags = listOf(
            listOf("e", target),
            listOf("amount", (sats * 1000).toString()),
            listOf("description", """{"pubkey":"$senderInDesc","content":""}"""),
        ),
    )

    @Test
    fun `register then anon receipt promotes to own and collapses to one row`() {
        store.ownPubkey = own
        store.insert(note("note-1"))
        store.addOptimisticZapDetail("note-1", senderPubkey = own, sats = 21, comment = "gm")
        store.registerPendingPrivateZap("note-1", anon)

        store.insert(zapReceipt("zr1", target = "note-1", senderInDesc = anon, sats = 21))

        val rows = store.zapDetailsForEvent("note-1")
        assertEquals(1, rows.size)
        assertEquals("promoted to own", own, rows.first().senderPubkey)
        assertEquals("receipt-backed row survives", "zr1", rows.first().eventId)
        assertTrue(store.hasOwnZapReceipt("note-1"))
        assertEquals(1, store.zapStats("note-1").count)
        assertEquals(21L, store.zapStats("note-1").totalSats)
    }

    @Test
    fun `register after the anon receipt already arrived reconciles it`() {
        store.ownPubkey = own
        store.insert(note("note-1"))
        store.addOptimisticZapDetail("note-1", senderPubkey = own, sats = 21, comment = "gm")
        store.insert(zapReceipt("zr1", target = "note-1", senderInDesc = anon, sats = 21))

        // Before registration: optimistic(own) + receipt(anon) both survive.
        assertEquals(2, store.zapDetailsForEvent("note-1").size)

        val matched = store.registerPendingPrivateZap("note-1", anon)

        assertTrue("reconcile reports it patched an existing receipt", matched)
        val rows = store.zapDetailsForEvent("note-1")
        assertEquals(1, rows.size)
        assertEquals(own, rows.first().senderPubkey)
        assertEquals("zr1", rows.first().eventId)
        assertTrue(store.hasOwnZapReceipt("note-1"))
    }

    @Test
    fun `unrelated anon receipt stays anonymous and is not recognized as own`() {
        store.ownPubkey = own
        store.insert(note("note-1"))
        store.registerPendingPrivateZap("note-1", anon) // OUR anon key — not the receipt's

        store.insert(zapReceipt("zr1", target = "note-1", senderInDesc = otherAnon, sats = 21))

        val rows = store.zapDetailsForEvent("note-1")
        assertEquals(1, rows.size)
        assertEquals("untouched — stays anonymous", otherAnon, rows.first().senderPubkey)
        assertFalse(store.hasOwnZapReceipt("note-1"))
    }

    @Test
    fun `public zap is unchanged — own sender collapses optimistic and counts once`() {
        store.ownPubkey = own
        store.insert(note("note-1"))
        store.addOptimisticZapDetail("note-1", senderPubkey = own, sats = 21, comment = "gm")

        store.insert(zapReceipt("zr1", target = "note-1", senderInDesc = own, sats = 21))

        val rows = store.zapDetailsForEvent("note-1")
        assertEquals(1, rows.size)
        assertEquals(own, rows.first().senderPubkey)
        assertEquals("zr1", rows.first().eventId)
        assertTrue(store.hasOwnZapReceipt("note-1"))
        assertEquals(1, store.zapStats("note-1").count)
    }

    @Test
    fun `pending anon mapping persists across a snapshot round-trip and still promotes`() {
        // Session 1: register the anon, optimistic own row added, but the receipt
        // has NOT arrived yet (app backgrounded before the LNURL receipt round-trips).
        store.ownPubkey = own
        store.insert(note("note-1"))
        store.addOptimisticZapDetail("note-1", senderPubkey = own, sats = 21, comment = "gm")
        store.registerPendingPrivateZap("note-1", anon)

        val bytes = ByteArrayOutputStream()
        runBlocking { store.saveSnapshotBinary(DataOutputStream(bytes)) }

        // Session 2: fresh store restores the snapshot, THEN the receipt arrives.
        val store2 = MemoryEventStore(object : MuteKeyProvider {}, com.unsilence.app.data.relay.stubTimelineServiceProvider())
        store2.ownPubkey = own
        runBlocking { store2.restoreSnapshotBinary(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))) }

        store2.insert(zapReceipt("zr1", target = "note-1", senderInDesc = anon, sats = 21))

        val rows = store2.zapDetailsForEvent("note-1")
        assertEquals(1, rows.size)
        assertEquals(own, rows.first().senderPubkey)
        assertEquals("zr1", rows.first().eventId)
    }
}
