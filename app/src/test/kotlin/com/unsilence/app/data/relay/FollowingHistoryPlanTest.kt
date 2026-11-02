package com.unsilence.app.data.relay

import com.unsilence.app.ui.shared.collectLatestWhileActive
import com.unsilence.app.domain.model.FeedFilter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FollowingHistoryPlanTest {
    private fun scope(candidates: Map<String, List<String>>) = FollowingHistoryScope("owner", candidates, FeedFilter())
    private fun event(author: String, time: Long) = EventDto(
        id = "%064x".format(time), pubkey = author, kind = 1, content = "fixture", tags = emptyList(),
        createdAt = time, sig = "0".repeat(128),
    ).toNostrEvent("wss://fixture.test")

    @Test fun `forty omitted authors get first turns before retries and fifth candidate is reachable`() = runTest {
        val candidates = (1..40).associate { "%064x".format(it) to (1..6).map { n -> "wss://r$it-$n.test" } }
        val plan = FollowingHistoryPlan(scope(candidates), candidates, NostrFilter(kinds = listOf(1), since = 12), 100) { testScheduler.currentTime }
        val attempted = mutableListOf<Pair<String, String>>()
        val retained = mutableListOf<String>()
        plan.run({ url, filter ->
            val author = filter.authors!!.single()
            attempted += author to url
            assertEquals(12L, filter.since); assertEquals(100L, filter.until); assertEquals(300, filter.limit)
            if (url.endsWith("-5.test")) FollowingHistoryPage(listOf(event(author, 50)), OneShotOutcome.EOSE)
            else FollowingHistoryPage(outcome = OneShotOutcome.CLOSED)
        }, { batch -> retained += batch.map { it.pubkey } })
        assertEquals(200, attempted.size)
        assertEquals(candidates.keys, attempted.take(40).map { it.first }.toSet())
        assertTrue(attempted.take(40).all { it.second.endsWith("-1.test") })
        assertEquals(candidates.keys, retained.toSet())
        assertTrue(plan.progress.values.all { it.retrieved })
        assertTrue(testScheduler.currentTime >= 4_000)
        plan.run({ _, _ -> error("Completed turn repeated") }, { error("Unexpected merge") })
    }

    @Test fun `five failures leave partial without a sixth candidate or idle retries`() = runTest {
        val candidates = mapOf("author" to (1..10).map { "wss://r$it.test" })
        val plan = FollowingHistoryPlan(scope(candidates), candidates, NostrFilter(), 100) { testScheduler.currentTime }
        var calls = 0
        repeat(2) { plan.run({ _, _ -> calls++; FollowingHistoryPage(outcome = OneShotOutcome.FAILED) }, {}) }
        assertEquals(5, calls)
        assertTrue(plan.progress.getValue("author").finished)
        assertFalse(plan.progress.getValue("author").retrieved)
    }

    @Test fun `empty genuine page finishes lookup while full page remains bounded history`() = runTest {
        val candidates = mapOf("empty" to listOf("wss://empty.test"), "full" to listOf("wss://full.test"))
        val plan = FollowingHistoryPlan(scope(candidates), candidates, NostrFilter(limit = 1_000, until = 80), 100)
        plan.run({ _, filter ->
            assertEquals(80L, filter.until); assertNull(filter.since)
            FollowingHistoryPage(if (filter.authors!!.single() == "full") (1..300).map { event("full", it.toLong()) } else emptyList(), OneShotOutcome.EOSE)
        }, {})
        assertTrue(plan.progress.values.all { it.retrieved })
        assertFalse(plan.progress.getValue("empty").fullPage)
        assertTrue(plan.progress.getValue("full").fullPage)
    }

    @Test fun `busy admission does not spend candidate and cancellation resumes only unfinished turns`() = runTest {
        val candidates = mapOf("a" to listOf("wss://a.test"), "b" to listOf("wss://b.test"))
        val plan = FollowingHistoryPlan(scope(candidates), candidates, NostrFilter(), 100)
        plan.run({ _, _ -> FollowingHistoryPage(deferred = true) }, {})
        assertTrue(plan.progress.values.all { it.next == 0 && !it.retrieved })
        val active = MutableStateFlow(true)
        val attempts = mutableListOf<String>()
        var hold = true
        val job = launch {
            MutableStateFlow(plan).collectLatestWhileActive(active) { current ->
                current.run({ _, filter ->
                    val author = filter.authors!!.single(); attempts += author
                    if (author == "b" && hold) awaitCancellation()
                    FollowingHistoryPage(outcome = OneShotOutcome.EOSE)
                }, {})
            }
        }
        runCurrent(); active.value = false; runCurrent()
        assertEquals(listOf("a", "b"), attempts)
        assertTrue(plan.progress.getValue("a").retrieved)
        assertEquals(0, plan.progress.getValue("b").next)
        hold = false; active.value = true; runCurrent()
        assertEquals(listOf("a", "b", "b"), attempts)
        active.value = false; runCurrent(); active.value = true; runCurrent()
        assertEquals(listOf("a", "b", "b"), attempts)
        job.cancelAndJoin()
    }

    @Test fun `each author gets a separate limit and older missing posts are not cut off by another head`() = runTest {
        val candidates = mapOf("prolific" to listOf("wss://shared.test"), "quiet" to listOf("wss://shared.test"))
        val plan = FollowingHistoryPlan(scope(candidates), candidates, NostrFilter(kinds = listOf(1)), 1_000)
        var displayed = listOf(event("live", 999))
        plan.run({ _, filter ->
            assertNull("No global-head since", filter.since)
            val author = filter.authors!!.single()
            FollowingHistoryPage(if (author == "prolific") (100..399).map { event(author, it.toLong()) } else listOf(event(author, 1)), OneShotOutcome.EOSE)
        }, { displayed = TimelineMerge.merge(displayed, it) })
        assertTrue(displayed.any { it.pubkey == "quiet" && it.createdAt == 1L })
        assertTrue(displayed.any { it.pubkey == "live" })
        assertTrue(plan.progress.values.all { it.retrieved })
    }
    @Test fun `candidate refresh joins cancelled page and adds newly omitted authors without repeating completed work`() = runTest {
        val writes = mapOf("a" to listOf("wss://a.test"), "b" to listOf("wss://b.test"), "c" to listOf("wss://c.test"))
        val initial = writes - "c"
        val plan = FollowingHistoryPlan(scope(writes), initial, NostrFilter(), 100)
        val routing = MutableStateFlow(initial)
        val active = MutableStateFlow(true)
        val attempts = mutableListOf<String>()
        val delivered = mutableListOf<String>()
        var released = false
        var firstB = true
        val job = launch {
            routing.collectLatestWhileActive(active) { candidates ->
                // Same ownership as FeedViewModel: collectLatest cancels AND joins
                // the entire previous action before mutating plan progress.
                if (candidates.size == 3) assertTrue(released)
                plan.updateCandidates(candidates)
                plan.run({ _, filter ->
                    val author = filter.authors!!.single(); attempts += author
                    if (author == "b" && firstB) {
                        firstB = false
                        try { awaitCancellation() } finally {
                            withContext(NonCancellable) { delay(10); released = true }
                        }
                    }
                    FollowingHistoryPage(listOf(event(author, 1)), OneShotOutcome.EOSE)
                }, { delivered += it.map { event -> event.pubkey } })
            }
        }
        runCurrent(); routing.value = writes; advanceUntilIdle()
        assertEquals(listOf("a", "b", "b", "c"), attempts)
        assertEquals(listOf("a", "b", "c"), delivered)
        // A temporarily live author and reordered candidates do not lose its completion.
        routing.value = writes - "a"; runCurrent()
        routing.value = writes.mapValues { (_, urls) -> listOf("wss://other.test") + urls }; runCurrent()
        assertEquals(4, attempts.size)
        job.cancelAndJoin()
    }

    @Test fun `retired filter page cannot merge even when transport returns after cancellation`() = runTest {
        val candidates = mapOf("a" to listOf("wss://a.test"))
        val original = scope(candidates)
        val changed = original.copy(filter = FeedFilter(minReplies = 1))
        assertNotEquals(original, changed) // Same wire kinds, genuinely different UI filter.
        assertNotEquals(original, original.copy(account = "other"))
        assertNotEquals(original, original.copy(writes = candidates + ("b" to listOf("wss://b.test"))))
        val scopes = MutableStateFlow(original)
        val delivered = mutableListOf<Long>()
        val plans = mutableListOf<FollowingHistoryPlan>()
        val job = launch {
            scopes.collectLatestWhileActive(MutableStateFlow(true)) { identity ->
                val plan = FollowingHistoryPlan(identity, candidates, NostrFilter(since = if (identity == original) 1 else 20), 100)
                plans += plan
                plan.run({ _, filter ->
                    if (identity == original) {
                        try { awaitCancellation() } catch (_: CancellationException) { /* transport completion racing disposal */ }
                        FollowingHistoryPage(listOf(event("a", 10)), OneShotOutcome.EOSE)
                    } else {
                        assertEquals(20L, filter.since)
                        FollowingHistoryPage(listOf(event("a", 30)), OneShotOutcome.EOSE)
                    }
                }, { delivered += it.map { event -> event.createdAt } })
            }
        }
        runCurrent(); scopes.value = changed; runCurrent()
        assertEquals(listOf(30L), delivered)
        assertFalse(plans.first().progress.getValue("a").retrieved)
        assertTrue(plans.last().progress.getValue("a").retrieved)
        job.cancelAndJoin()
    }

    @Test fun `Following history uses existing paging merge without resetting the reading window`() = runTest {
        val original = (1..1_100).map { event("live", it.toLong()) }.reversed()
        val window = com.unsilence.app.ui.feed.FeedPagingWindow().advance(
            com.unsilence.app.ui.feed.FeedPageCursor(original.last().createdAt, original.last().id))
        var timeline = com.unsilence.app.ui.feed.FeedTimeline(events = original, paging = window)
        val candidates = mapOf("missing" to listOf("wss://missing.test"))
        val plan = FollowingHistoryPlan(scope(candidates), candidates, NostrFilter(), 2_000)
        plan.run({ _, _ -> FollowingHistoryPage((1_101..1_400).map { event("missing", it.toLong()) }, OneShotOutcome.EOSE) },
            { timeline = timeline.merge(it) })
        assertEquals(window, timeline.paging)
        assertEquals(original, timeline.events)
        assertEquals(1_100, timeline.events.size)
        assertTrue(plan.progress.getValue("missing").retrieved)
    }

    @Test fun `retirement before collector cancellation never spends the pending turn including an empty page`() = runTest {
        val candidates = mapOf("a" to listOf("wss://a.test"))
        val plan = FollowingHistoryPlan(scope(candidates), candidates, NostrFilter(), 100)
        var current = true
        plan.run({ _, _ -> current = false; FollowingHistoryPage(outcome = OneShotOutcome.EOSE) },
            { error("retired page merged") }, { current })
        assertFalse(plan.progress.getValue("a").retrieved)
        assertEquals(0, plan.progress.getValue("a").next)
        current = true
        plan.run({ _, _ -> FollowingHistoryPage(outcome = OneShotOutcome.EOSE) }, {}, { current })
        assertTrue(plan.progress.getValue("a").retrieved)
    }

    @Test fun `per author page preserves caller constraints without a head cursor`() = runTest {
        val candidates = mapOf("a" to listOf("wss://a.test"))
        val filter = NostrFilter(kinds = listOf(1, 6), ids = listOf("id"), since = 30, until = 90,
            limit = 25, search = "fixture", tags = mapOf("t" to listOf("topic")))
        val plan = FollowingHistoryPlan(scope(candidates), candidates, filter, 100)
        plan.run({ _, sent ->
            assertEquals(filter.copy(authors = listOf("a")), sent)
            FollowingHistoryPage(outcome = OneShotOutcome.EOSE)
        }, {})
    }

}
