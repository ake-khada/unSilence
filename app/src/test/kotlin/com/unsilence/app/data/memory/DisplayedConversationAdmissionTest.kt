package com.unsilence.app.data.memory

import app.cash.turbine.test
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import com.unsilence.app.data.zap.ZapCryptoTestFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Real admission pressure while the production thread projection is observed. */
class DisplayedConversationAdmissionTest {
    @Test
    fun `displayed reply survives unrelated note admissions`() = runTest {
        val store = store()
        val root = note("root")
        val reply = note("reply").copy(
            rootId = root.id,
            replyToId = root.id,
            tags = listOf(listOf("e", root.id, "", "root")),
        )
        assertTrue(store.insert(root))
        assertTrue(store.insert(reply))
        // The surface reports its visible root and reply.
        store.openDisplayWindow().use { window ->
            window.update(listOf(root.id to root.id, "reply" to "reply"), setOf(root.id, "reply"))
            store.threadFeedRowFlow(root.id).test {
                assertEquals(setOf(root.id, reply.id), awaitItem().map { it.id }.toSet())
                repeat(CONTENT_EVENT_KIND_CAPS.getValue(1)) { index ->
                    store.insert(note("unrelated-$index"))
                }
                assertAdmissionPressureWithoutSweep(store, 1)
                assertEquals("An actively displayed reply must survive admission", 1, store.replyCount(root.id))
                assertNotNull(store.getNostrEvent(reply.id))
                cancelAndIgnoreRemainingEvents()
            }
        }
        store.evictOldContentEventsForTest(mapOf(1 to 0))
        assertNull("The same reply becomes evictable after observer cancellation", store.getNostrEvent(reply.id))
    }

    @Test
    fun `authenticated receipt on displayed root survives receipt admissions`() = runTest {
        val crypto = ZapCryptoTestFixture()
        val store = store()
        val root = note(crypto.defaultTarget).copy(pubkey = crypto.recipient.pubkey)
        assertTrue(store.insert(root))
        val displayedReceipt = crypto.receipt(request = crypto.request())
        assertTrue(store.insert(displayedReceipt))
        assertEquals(1, store.zapStats(root.id).count)

        store.openDisplayWindow().use { window ->
            window.update(listOf(root.id to root.id), setOf(root.id))
            store.threadFeedRowFlow(root.id).test {
                assertEquals(listOf(root.id), awaitItem().map { it.id })
                val otherTarget = "cd".repeat(32)
                val otherRequest = crypto.request(targetId = otherTarget)
                repeat(CONTENT_EVENT_KIND_CAPS.getValue(9735)) { index ->
                    assertTrue(store.insert(crypto.receipt(
                        request = otherRequest,
                        targetId = otherTarget,
                        createdAt = 1_675_000_002L + index,
                    )))
                }
                assertAdmissionPressureWithoutSweep(store, 9735)
                assertEquals("The displayed root's authenticated receipt must survive admission", 1, store.zapStats(root.id).count)
                assertNotNull(store.getNostrEvent(displayedReceipt.id))
                cancelAndIgnoreRemainingEvents()
            }
        }
        store.evictOldContentEventsForTest(mapOf(9735 to 0))
        assertNull("The same receipt becomes evictable after observer cancellation", store.getNostrEvent(displayedReceipt.id))
        assertEquals(0, store.zapStats(root.id).count)
    }

    @Test
    fun `fully protected receipt cap rejects newcomer until window closes`() = runTest {
        val store = store()
        val crypto = ZapCryptoTestFixture()
        val root = note(crypto.defaultTarget).copy(pubkey = crypto.recipient.pubkey)
        store.insert(root)
        val request = crypto.request()
        val window = store.openDisplayWindow()
        window.update(listOf(root.id to root.id), setOf(root.id))
        repeat(CONTENT_EVENT_KIND_CAPS.getValue(9735)) { i ->
            assertTrue(store.insert(crypto.receipt(request = request, createdAt = 1_675_000_001L + i)))
        }
        val other = "cd".repeat(32)
        val incoming = crypto.receipt(request = crypto.request(targetId = other), targetId = other)
        assertFalse(store.insert(incoming))
        assertEquals(250, store.snapshotSize().eventsByKind[9735])
        store.evictOldContentEventsForTest(mapOf(9735 to 0))
        assertEquals("Sweep cannot remove protected receipts", 250, store.snapshotSize().eventsByKind[9735])
        window.close()
        assertTrue(store.insert(incoming))
        assertEquals(250, store.snapshotSize().eventsByKind[9735])
    }

    @Test
    fun `oversized thread does not block unrelated note admission`() = runTest {
        val store = store()
        val root = note("root")
        store.insert(root)
        repeat(CONTENT_EVENT_KIND_CAPS.getValue(1) - 1) { i ->
            store.insert(note("reply-$i").copy(rootId = root.id, replyToId = root.id))
        }
        store.openDisplayWindow().use { window ->
            window.update(listOf(root.id to root.id, "reply-0" to "reply-0"), setOf(root.id, "reply-0"))
            store.threadFeedRowFlow(root.id).test(timeout = 20.seconds) {
                assertEquals(5_000, awaitItem().size)
                assertTrue(store.insert(note("unrelated-new")))
                assertNotNull(store.getNostrEvent(root.id))
                assertNotNull(store.getNostrEvent("reply-0"))
                assertEquals(5_000, store.snapshotSize().eventsByKind[1])
                assertEquals("Off-window replies remain evictable", 4_998, store.replyCount(root.id))
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    private fun store() = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider()).apply {
        ownPubkey = "ef".repeat(32)
    }

    private fun assertAdmissionPressureWithoutSweep(store: MemoryEventStore, kind: Int) {
        val metrics = store.snapshotEvictionMetrics()
        assertTrue("Fixture must exercise admission replacement", metrics.admissionReplaced > 0)
        assertEquals("No sweep removal may explain the result", metrics.admissionReplaced, metrics.evicted)
        assertEquals(setOf(kind), metrics.evictedByKind.keys)
    }

    private fun note(id: String) = NostrEvent(
        id = id, pubkey = "unfollowed-author", kind = 1, content = "fixture", createdAt = 1L,
        tags = emptyList(), sig = "", relayUrl = "wss://relay.example", replyToId = null,
        rootId = null, hasContentWarning = false, contentWarningReason = null,
        firstSeenAt = 1L, relaysSeen = mutableSetOf("wss://relay.example"),
    )
}
