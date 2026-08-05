package com.unsilence.app.data.memory

import com.unsilence.app.data.auth.MuteKeyProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Persisted zap summary↔drawer consistency (Commit 2a).
 *
 * insertFromSnapshot restores kind-9735 receipt events WITHOUT running
 * handleZapReceipt, so per-zap detail rows come only from the separately
 * persisted contrib section — which can drift from the aggregate section.
 * Result seen on device: action-bar count persists (raw aggregate fallback)
 * but the drawer is empty.
 *
 * Fix: (1) a restore repair pass that reconstructs missing detail rows from
 * retained receipt events and recomputes the aggregate from them; (2) a drawer
 * fallback that synthesizes one anonymous row from the raw aggregate so the
 * drawer is never empty while the summary shows sats.
 */
class ZapDrawerConsistencyTest {

    private lateinit var store: MemoryEventStore

    @Before
    fun setUp() {
        store = MemoryEventStore(object : MuteKeyProvider {}, com.unsilence.app.data.relay.stubTimelineServiceProvider())
    }

    private val author = "f".repeat(64)
    private val sender = "a".repeat(64)

    private fun event(
        id: String,
        pubkey: String,
        kind: Int,
        tags: List<List<String>> = emptyList(),
    ) = NostrEvent(
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
    fun `repair reconstructs the detail row from a restored receipt with no detail`() {
        // Mirror a cold restore: the receipt EVENT is present (insertFromSnapshot
        // does NOT run handleZapReceipt) and the raw aggregate is stale/present,
        // but the per-zap detail row is missing.
        store.insertFromSnapshot(note("note-1"))
        store.insertFromSnapshot(zapReceipt("zr1", target = "note-1", senderInDesc = sender, sats = 21))
        store.incrementZapStats("note-1", 999L) // stale raw aggregate that disagrees

        store.repairZapDetailsFromReceipts()

        val rows = store.zapDetailsForEvent("note-1")
        val receiptRow = rows.first { it.eventId != null }
        assertEquals("zr1", receiptRow.eventId)
        assertEquals(sender, receiptRow.senderPubkey)
        // Summary now matches the receipt-backed row, not the stale 999 aggregate.
        assertEquals(1, store.zapStats("note-1").count)
        assertEquals(21L, store.zapStats("note-1").totalSats)
    }

    @Test
    fun `aggregate-only with no receipt event yields a synthetic anonymous drawer row`() {
        store.insertFromSnapshot(note("note-1"))
        store.incrementZapStats("note-1", 50L) // raw aggregate; NO receipt event, NO detail

        store.repairZapDetailsFromReceipts() // leaves legacy aggregate-only entries alone

        // Summary still shows the raw aggregate.
        assertEquals(1, store.zapStats("note-1").count)
        assertEquals(50L, store.zapStats("note-1").totalSats)
        // Drawer is NOT empty — one synthetic anonymous row carrying the sats.
        val rows = store.zapDetailsForEvent("note-1")
        assertEquals(1, rows.size)
        assertNull("synthetic row is anonymous", rows.first().senderPubkey)
        assertNull("synthetic row is not receipt-backed", rows.first().eventId)
        assertEquals(50L, rows.first().sats)
    }

    @Test
    fun `repair is idempotent — running twice does not duplicate or double-count`() {
        store.insertFromSnapshot(note("note-1"))
        store.insertFromSnapshot(zapReceipt("zr1", target = "note-1", senderInDesc = sender, sats = 21))

        store.repairZapDetailsFromReceipts()
        store.repairZapDetailsFromReceipts()

        val receiptRows = store.zapDetailsForEvent("note-1").count { it.eventId != null }
        assertEquals(1, receiptRows)
        assertEquals(1, store.zapStats("note-1").count)
        assertEquals(21L, store.zapStats("note-1").totalSats)
    }
}
