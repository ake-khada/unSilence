package com.unsilence.app.ui.thread

import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.fetchThreadRequests
import com.unsilence.app.data.relay.stubTimelineServiceProvider
import com.unsilence.app.data.zap.ZapCryptoTestFixture
import com.unsilence.app.ui.shared.collectLatestWhileActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Production route observation and completed one-shot dispatch, with only transport faked. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadResumeRecoveryTest {
    @Test fun `completed reply query resumes after its retained reply was reclaimed`() = runTest {
        checkResume(receiptOnly = false, reclaim = true)
    }

    @Test fun `completed receipt query resumes when replies remain but receipt was reclaimed`() = runTest {
        checkResume(receiptOnly = true, reclaim = true)
    }

    @Test fun `intact thread resume reuses every completed query`() = runTest {
        checkResume(receiptOnly = false, reclaim = false)
    }

    @Test fun `receipt loss before pause still retries the completed receipt query once`() = runTest {
        checkResume(receiptOnly = true, reclaim = true, lossBeforePause = true)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.checkResume(receiptOnly: Boolean, reclaim: Boolean, lossBeforePause: Boolean = false) {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val crypto = ZapCryptoTestFixture()
        val root = note(crypto.defaultTarget).copy(pubkey = crypto.recipient.pubkey)
        val reply = note("reply").copy(replyToId = root.id, tags = listOf(listOf("e", root.id)))
        val receipt = crypto.receipt(request = crypto.request())
        store.insertBatch(listOf(root, reply))
        store.insert(receipt)
        val held = ThreadUiState(focusedNote = store.synthesizeFeedRow(root),
            replies = listOf(DepthRow(store.synthesizeFeedRow(reply), 1)), loading = false)
        val route = MutableStateFlow<ThreadRelayRoute?>(ThreadRelayRoute(root.id, listOf("wss://relay.test"), listOf("wss://relay.test")))
        val active = MutableStateFlow(true)
        val completed = HashSet<Pair<String, String>>()
        var paused: ThreadRootEngagement? = null
        val sent = mutableListOf<String>()
        var pass = 0
        val observer = backgroundScope.launch {
            route.withThreadCoordinate(store.feedSignalFlow, store::articleCoordForEvent)
                .onStart {
                    missingThreadData(root.id, held, store, paused).invalidate(completed, null,
                        HashSet(), HashSet(), HashSet())
                }
                .flatMapLatest { (current, _) -> flow {
                    fetchThreadRequests(current.rootId, "${++pass}", current.compatibilityRelays, completed,
                        fetch = { sub, relays, _, onEose ->
                            val query = sub.substringBeforeLast('-')
                            sent += query
                            if (pass > 1) when (query) {
                                "thread-replies" -> store.insert(reply)
                                "thread-zaps" -> store.insert(receipt)
                            }
                            relays.forEach(onEose)
                        }, onCompleted = { query, relay -> completed += query to relay })
                    emit(Unit)
                } }.collectLatestWhileActive(active) {}
        }
        runCurrent()
        assertEquals(4, sent.size)
        fun reclaimBodies() {
            if (receiptOnly) store.evictOldContentEventsForTest(mapOf(9735 to 0))
            else {
                store.ownPubkey = root.pubkey // Keep only the root exempt during this forced sweep.
                store.evictOldContentEventsForTest(mapOf(1 to 0))
            }
            assertNotNull(store.getNostrEvent(root.id))
            if (receiptOnly) {
                assertNotNull(store.getNostrEvent(reply.id))
                assertEquals(0, store.zapStats(root.id).count)
            } else assertNull(store.getNostrEvent(reply.id))
        }
        if (reclaim && lossBeforePause) reclaimBodies()
        active.value = false
        runCurrent()
        paused = held.rootEngagementForResume(store)
        if (reclaim && !lossBeforePause) reclaimBodies()
        active.value = true
        runCurrent()
        assertEquals(if (reclaim) listOf(if (receiptOnly) "thread-zaps" else "thread-replies") else emptyList<String>(), sent.drop(4))
        assertEquals(1, store.replyCount(root.id))
        assertEquals(1, store.zapStats(root.id).count)
        // A second intact resume must not fetch again or inflate any aggregate.
        active.value = false; runCurrent()
        active.value = true; runCurrent()
        assertEquals(if (reclaim) 5 else 4, sent.size)
        observer.cancel()
    }

    @Test fun `pause captures a receipt delivered after the thread row was projected`() = runTest {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val crypto = ZapCryptoTestFixture()
        val root = note(crypto.defaultTarget).copy(pubkey = crypto.recipient.pubkey)
        store.insert(root)
        val early = ThreadUiState(focusedNote = store.synthesizeFeedRow(root))
        assertEquals(0, early.focusedNote!!.zapCount)
        store.insert(crypto.receipt(request = crypto.request()))
        val paused = early.rootEngagementForResume(store)
        assertEquals(1, paused!!.zaps)
        store.evictOldContentEventsForTest(mapOf(9735 to 0))
        assertEquals(setOf("thread-zaps"), missingThreadData(root.id, early, store, paused).queries)
    }

    @Test fun `valid deletion and another root do not masquerade as missing replies`() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val root = note("root")
        val reply = note("reply").copy(replyToId = root.id)
        store.insertBatch(listOf(root, reply))
        val held = ThreadUiState(focusedNote = store.synthesizeFeedRow(root),
            replies = listOf(DepthRow(store.synthesizeFeedRow(reply), 1)))
        store.insert(note("deletion").copy(kind = 5, tags = listOf(listOf("e", reply.id))))
        assertNull(store.getNostrEvent(reply.id))
        assertTrue(missingThreadData(root.id, held, store).queries.isEmpty())
        assertTrue(missingThreadData("another", held, store).queries.isEmpty())
        assertFalse(store.insert(reply))
    }

    @Test fun `cancelled resume preserves pending evidence without carrying it to another root`() = runTest {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val crypto = ZapCryptoTestFixture()
        val root = note(crypto.defaultTarget).copy(pubkey = crypto.recipient.pubkey)
        store.insert(root)
        store.insert(crypto.receipt(request = crypto.request()))
        val held = ThreadUiState(focusedNote = store.synthesizeFeedRow(root))
        val pending = held.rootEngagementForResume(store)
        store.evictOldContentEventsForTest(mapOf(9735 to 0))
        val current = ThreadUiState(focusedNote = store.synthesizeFeedRow(root))
        // No completion was invalidated before cancellation; the next pause must
        // preserve the one pending recovery hint even if projection already caught up.
        val retained = current.rootEngagementForResume(store, pending)
        assertEquals(setOf("thread-zaps"), missingThreadData(root.id, current, store, retained).queries)
        val other = note("other").also(store::insert)
        val otherState = ThreadUiState(focusedNote = store.synthesizeFeedRow(other))
        val otherCounts = otherState.rootEngagementForResume(store, retained)
        assertEquals(0, otherCounts!!.zaps)
        assertTrue(missingThreadData(other.id, otherState, store, retained).queries.isEmpty())
    }

    @Test fun `reaction decrease before pause retries once without resurrecting a valid deletion`() = runTest {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val root = note("root")
        val reaction = note("reaction").copy(kind = 7, content = "+", tags = listOf(listOf("e", root.id)))
        store.insertBatch(listOf(root, reaction))
        val held = ThreadUiState(focusedNote = store.synthesizeFeedRow(root))
        assertEquals(1, held.focusedNote!!.reactionCount)
        store.insert(note("delete-reaction").copy(kind = 5, tags = listOf(listOf("e", reaction.id))))
        assertEquals(0, store.reactionCount(root.id))
        val paused = held.rootEngagementForResume(store)
        val recovery = missingThreadData(root.id, held, store, paused)
        assertEquals(setOf("thread-reactions"), recovery.queries)
        val completed = setOf("thread-event", "thread-replies", "thread-reactions", "thread-zaps")
            .mapTo(HashSet()) { it to "wss://relay.test" }
        recovery.invalidate(completed, null, HashSet(), HashSet(), HashSet())
        val sent = mutableListOf<String>()
        fetchThreadRequests(root.id, "retry", listOf("wss://relay.test"), completed,
            fetch = { sub, relays, _, onEose ->
                sent += sub
                assertFalse("Retry never overrides author-qualified deletion", store.insert(reaction))
                relays.forEach(onEose)
            }, onCompleted = { query, relay -> completed += query to relay })
        assertEquals(listOf("thread-reactions-retry"), sent)
        val fresh = ThreadUiState(focusedNote = store.synthesizeFeedRow(root))
        val intact = missingThreadData(root.id, fresh, store, fresh.rootEngagementForResume(store))
        assertTrue("Fresh row reactions=${fresh.focusedNote!!.reactionCount}, MES=${store.reactionCount(root.id)}, queries=${intact.queries}", intact.queries.isEmpty())
        assertEquals(0, store.reactionCount(root.id))
    }

    @Test fun `article recovery invalidates only affected coordinate and parent completion`() {
        val store = MemoryEventStore(object : MuteKeyProvider {}, stubTimelineServiceProvider())
        val root = note("article").copy(kind = 30023, tags = listOf(listOf("d", "story")))
        val parent = note("parent").copy(kind = 1111, replyToId = root.id)
        val child = note("child").copy(replyToId = parent.id)
        store.insertBatch(listOf(root, parent, child))
        val held = ThreadUiState(focusedNote = store.synthesizeFeedRow(root),
            replies = listOf(DepthRow(store.synthesizeFeedRow(parent), 1), DepthRow(store.synthesizeFeedRow(child), 2)))
        store.evictOldContentEventsForTest(mapOf(1 to 0))
        val completed = mutableSetOf("thread-replies" to "relay", "thread-zaps" to "relay")
        val coordinate = "30023:${root.pubkey}:story"
        val coords = mutableSetOf(coordinate, "another-coordinate")
        val descendants = mutableSetOf(parent.id, child.id, "another-parent")
        val parents = mutableSetOf(child.id, "another-missing-parent")
        missingThreadData(root.id, held, store).invalidate(completed, coordinate, coords, descendants, parents)
        assertEquals(setOf("thread-zaps" to "relay"), completed)
        assertEquals(setOf("another-coordinate"), coords)
        assertEquals(setOf("another-parent"), descendants)
        assertEquals(setOf("another-missing-parent"), parents)
    }

    private fun note(id: String) = NostrEvent(id, "reply-author", 1, "fixture", 1L,
        emptyList(), "", "wss://relay.test", null, null, false, null, 1L, mutableSetOf("wss://relay.test"))
}
