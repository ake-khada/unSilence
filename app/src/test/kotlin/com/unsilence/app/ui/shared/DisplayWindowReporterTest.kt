package com.unsilence.app.ui.shared

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import org.junit.Assert.*
import org.junit.Test

class DisplayWindowReporterTest {
    @Test
    fun `shared reporter protects repost identity only while surface is active`() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val target = event("target")
        val wrapper = event("wrapper").copy(kind = 6, rootId = target.id, tags = listOf(listOf("e", target.id)))
        store.insert(target)
        store.insert(wrapper)
        val row = store.synthesizeFeedRow(wrapper)
        val reporter = DisplayWindowReporter(store)
        reporter.setActive(true)
        reporter.report(listOf(row), setOf(row.id))
        store.evictOldContentEventsForTest(mapOf(1 to 0, 6 to 0))
        assertNotNull(store.getNostrEvent(target.id))
        assertNotNull(store.getNostrEvent(wrapper.id))
        reporter.setActive(false)
        // A late asynchronous warm completion cannot resurrect the hidden window.
        reporter.report(listOf(row), setOf(row.id))
        store.evictOldContentEventsForTest(mapOf(1 to 0, 6 to 0))
        assertNull(store.getNostrEvent(target.id))
        assertNull(store.getNostrEvent(wrapper.id))
    }

    @Test
    fun `visible report touches original LRU but lookahead does not`() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val visible = event("visible")
        val lookahead = event("lookahead")
        store.insert(visible)
        store.insert(lookahead)
        repeat(4_998) { store.insert(event("other-$it")) }
        val reporter = DisplayWindowReporter(store)
        reporter.setActive(true)
        reporter.report(listOf(store.synthesizeFeedRow(visible), store.synthesizeFeedRow(lookahead)), setOf(visible.id))
        reporter.close()
        assertTrue(store.insert(event("newcomer")))
        assertNotNull(store.getNostrEvent(visible.id))
        assertNull(store.getNostrEvent(lookahead.id))
    }

    @Test
    fun `nearby row and its late engagement survive admission and sweep until window moves`() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val visible = event("visible")
        val nearby = event("nearby")
        store.insert(visible)
        store.insert(nearby)
        val reporter = DisplayWindowReporter(store)
        reporter.setActive(true)
        reporter.report(listOf(store.synthesizeFeedRow(visible), store.synthesizeFeedRow(nearby)), setOf(visible.id))
        val reaction = event("nearby-reaction").copy(kind = 7, tags = listOf(listOf("e", nearby.id)))
        store.insert(reaction)
        repeat(5_000) { store.insert(event("pressure-$it")) }
        repeat(1_000) { store.insert(event("reaction-$it").copy(kind = 7, tags = listOf(listOf("e", "elsewhere")))) }
        assertTrue(store.snapshotEvictionMetrics().admissionReplaced > 0)
        assertNotNull("Nearby reply must not disappear before it reaches the viewport", store.getNostrEvent(nearby.id))
        assertNotNull("Nearby engagement must remain retained", store.getNostrEvent(reaction.id))
        store.evictOldContentEventsForTest(mapOf(1 to 0, 7 to 0))
        assertNotNull(store.getNostrEvent(visible.id))
        assertNotNull(store.getNostrEvent(nearby.id))
        assertNotNull(store.getNostrEvent(reaction.id))
        reporter.report(listOf(store.synthesizeFeedRow(nearby)), setOf(nearby.id))
        store.evictOldContentEventsForTest(mapOf(1 to 0))
        assertNull("Old window released on move", store.getNostrEvent(visible.id))
        assertNotNull(store.getNostrEvent(nearby.id))
        reporter.setActive(false)
        store.evictOldContentEventsForTest(mapOf(1 to 0, 7 to 0))
        assertNull(store.getNostrEvent(nearby.id))
        assertNull(store.getNostrEvent(reaction.id))
    }

    @Test
    fun `visible rows take precedence over nearby rows within the existing bound`() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val rows = (0..63).map { event("row-$it").also(store::insert) }.map(store::synthesizeFeedRow)
        val reporter = DisplayWindowReporter(store)
        reporter.setActive(true)
        reporter.report(rows, setOf(rows.last().id))
        store.evictOldContentEventsForTest(mapOf(1 to 0))
        assertNotNull(store.getNostrEvent(rows.last().id))
        assertEquals("Protection stays bounded", 32, store.snapshotSize().eventsByKind[1])
    }

    @Test
    fun `window selection and dispatch prefer visible then nearby without protecting whole thread`() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val rows = (0..99).map { event("row-$it").also(store::insert) }.map(store::synthesizeFeedRow)
        val reporter = DisplayWindowReporter(store)
        reporter.setActive(true)
        val ids = setOf(rows[40].id, rows[41].id)
        val selected = protectedDisplayRows(rows, 40, 41, ids)
        reporter.report(selected, ids)
        assertEquals(setOf("row-38", "row-39", "row-40", "row-41", "row-42", "row-43", "row-44", "row-45", "row-46", "row-47"), selected.map { it.id }.toSet())
        val targets = listOf("row-90", "row-42", "row-40").map {
            com.unsilence.app.data.relay.CardHydrator.EngagementTarget(it, null, "author", 1L)
        }
        val ordered = com.unsilence.app.data.relay.prioritizeEngagementTargets(targets, store.displayPriorities())
        assertEquals(listOf("row-40", "row-42", "row-90"), ordered.map { it.id })
        val batches = com.unsilence.app.data.relay.coalesceByRelay(
            ordered.associate { it.id to listOf("wss://relay.example") }, 1, 1,
        )
        assertEquals(listOf("row-40", "row-42", "row-90"), batches.flatMap { it.second })
        store.evictOldContentEventsForTest(mapOf(1 to 10))
        assertEquals(10, store.snapshotSize().eventsByKind[1])
        assertNull("Remaining conversation is not pinned", store.getNostrEvent("row-90"))
        assertNotNull(store.getNostrEvent("row-40"))
        reporter.setActive(false)
        assertTrue(store.displayPriorities().isEmpty())
    }

    @Test
    fun `nearby authenticated receipt survives pressure and releases with its window`() = kotlinx.coroutines.test.runTest {
        val crypto = com.unsilence.app.data.zap.ZapCryptoTestFixture()
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val target = event(crypto.defaultTarget).copy(pubkey = crypto.recipient.pubkey)
        store.insert(target)
        val visible = event("visible")
        store.insert(visible)
        val reporter = DisplayWindowReporter(store)
        reporter.setActive(true)
        reporter.report(listOf(store.synthesizeFeedRow(visible), store.synthesizeFeedRow(target)), setOf(visible.id))
        val receipt = crypto.receipt(request = crypto.request())
        val lost = mutableSetOf<String>()
        store.engagementEvicted = { lost += it }
        store.insert(receipt)
        val other = "cd".repeat(32)
        val request = crypto.request(targetId = other)
        repeat(250) { index -> store.insert(crypto.receipt(request = request, targetId = other, createdAt = 1_675_000_002L + index)) }
        assertTrue(store.snapshotEvictionMetrics().admissionReplaced > 0)
        assertEquals(1, store.zapStats(target.id).count)
        assertFalse(target.id in lost)
        store.evictOldContentEventsForTest(mapOf(9735 to 0))
        assertNotNull(store.getNostrEvent(receipt.id))
        reporter.setActive(false)
        store.evictOldContentEventsForTest(mapOf(9735 to 0))
        assertNull(store.getNostrEvent(receipt.id))
        assertEquals(0, store.zapStats(target.id).count)
        assertTrue("Lost authenticated receipt data must permit recovery", target.id in lost)
    }

    @Test
    fun `zero width still protects the window and an empty feed releases it`() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val note = event("visible")
        store.insert(note)
        val rows = listOf(store.synthesizeFeedRow(note))
        val reporter = DisplayWindowReporter(store)
        reporter.setActive(true)
        assertFalse("Asset work waits for width", reporter.prepareWarmWindow(rows, 0, 0, setOf(note.id), 0))
        store.evictOldContentEventsForTest(mapOf(1 to 0))
        assertNotNull("Protection cannot wait for width", store.getNostrEvent(note.id))
        assertFalse(reporter.prepareWarmWindow(emptyList(), 0, 0, emptySet(), 0))
        store.evictOldContentEventsForTest(mapOf(1 to 0))
        assertNull("No viewport emission needed to release an emptied feed", store.getNostrEvent(note.id))
    }

    @Test
    fun `unchanged visible reports keep LRU recent without touching nearby rows`() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val visible = event("visible")
        val nearby = event("nearby")
        store.insert(visible)
        store.insert(nearby)
        val rows = listOf(store.synthesizeFeedRow(visible), store.synthesizeFeedRow(nearby))
        val reporter = DisplayWindowReporter(store)
        reporter.setActive(true)
        reporter.report(rows, setOf(visible.id))
        repeat(4_998) { store.insert(event("other-$it")) }
        reporter.report(rows, setOf(visible.id))
        reporter.close()
        store.insert(event("new"))
        store.insert(event("newer"))
        assertNotNull(store.getNostrEvent(visible.id))
        assertNull(store.getNostrEvent(nearby.id))
    }

    @Test
    fun `visible relay batches lead nearby and older queued work without changing coverage`() {
        val batches = listOf(
            "wss://old.test" to listOf("old"),
            "wss://near.test" to listOf("near"),
            "wss://visible.test" to listOf("visible"),
            "wss://mixed.test" to listOf("other", "visible"),
        )
        val ordered = com.unsilence.app.data.relay.prioritizeEngagementBatches(batches, mapOf("visible" to 0, "near" to 1))
        assertEquals(listOf(batches[2], batches[3], batches[1], batches[0]), ordered)
        assertEquals(batches.toSet(), ordered.toSet())
    }

    @Test
    fun `visible IDs anchor protection inside a broader asset warm range`() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val rows = (0..11).map { event("row-$it").also(store::insert) }.map(store::synthesizeFeedRow)
        val reporter = DisplayWindowReporter(store)
        reporter.setActive(true)
        reporter.prepareWarmWindow(rows, 0, rows.lastIndex, setOf("row-8"), 100)
        store.evictOldContentEventsForTest(mapOf(1 to 0))
        assertEquals((6..11).map { "row-$it" }.toSet(), store.displayPriorities().keys)
        assertNull(store.getNostrEvent("row-0"))
        assertNull(store.getNostrEvent("row-1"))
        assertNotNull(store.getNostrEvent("row-8"))
    }

    @Test fun `covered authenticated receipts yield to a cold receipt at the unchanged cap`() = kotlinx.coroutines.test.runTest {
        val crypto = com.unsilence.app.data.zap.ZapCryptoTestFixture()
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val target = event(crypto.defaultTarget).copy(pubkey = crypto.recipient.pubkey)
        store.insert(target)
        val request = crypto.request()
        repeat(250) { index -> store.insert(crypto.receipt(request = request, createdAt = 1_675_000_002L + index)) }
        assertEquals(250, store.zapStats(target.id).count)
        val reporter = DisplayWindowReporter(store)
        reporter.setActive(true)
        reporter.report(listOf(store.synthesizeFeedRow(target)), setOf(target.id))
        reporter.setActive(false)
        val other = "cd".repeat(32)
        val incoming = crypto.receipt(request = crypto.request(targetId = other), targetId = other, createdAt = 1_675_001_000L)
        assertTrue(store.insert(incoming))
        assertNotNull(store.getNostrEvent(incoming.id))
        assertEquals(250, store.snapshotSize().eventsByKind[9735])
        assertEquals(249, store.zapStats(target.id).count)
        assertEquals(1, store.zapStats(other).count)
        reporter.close()
    }

    @Test fun `inactive reporter created before account clear cannot register old rows`() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val old = DisplayWindowReporter(store)
        store.clearUserState()
        val row = event("old-row").also(store::insert).let(store::synthesizeFeedRow)
        old.setActive(true)
        old.report(listOf(row), setOf(row.id))
        assertTrue(store.displayPriorities().isEmpty())
        old.close()
    }

    private fun event(id: String) = NostrEvent(
        id = id, pubkey = "unfollowed", kind = 1, content = "fixture", createdAt = 1L,
        tags = emptyList(), sig = "", relayUrl = "wss://relay.example", replyToId = null, rootId = null,
        hasContentWarning = false, contentWarningReason = null, firstSeenAt = 1L,
        relaysSeen = mutableSetOf("wss://relay.example"),
    )
}
