package com.unsilence.app.ui.search

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchInitialLoadingTest {
    @Test fun `rapid edits debounce once and the final query gets its own bounded initial wait`() = runTest {
        val query = MutableStateFlow("nos")
        val resumed = MutableStateFlow(true)
        val requests = SearchRequestLifetime(closeRequest = {})
        var loading = true // search() exposes loading synchronously during debounce.
        var dispatches = 0
        backgroundScope.launch {
            resumed.searchQueriesWhileResumed(query).collectLatest { value ->
                requests.close()
                if (value == null) return@collectLatest
                val attempt = requests.begin()
                try {
                    if (attempt.fetch) dispatches++
                    MutableSharedFlow<List<String>>().collectWithInitialSearchLoading(
                        attempt.fetch, { requests.token == attempt.token && query.value == value && resumed.value },
                        { it.isNotEmpty() }, { loading = it }, {},
                    )
                } finally { requests.close(attempt.token) }
            }
        }
        runCurrent()
        advanceTimeBy(100)
        requests.queryChanged(); query.value = "nost"; loading = true; runCurrent()
        advanceTimeBy(100)
        requests.queryChanged(); query.value = "nostr"; loading = true; runCurrent()
        advanceTimeBy(999); runCurrent()
        assertTrue(loading)
        assertEquals(0, dispatches)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, dispatches)
        advanceTimeBy(2_999); runCurrent()
        assertTrue(loading)
        advanceTimeBy(1); runCurrent()
        assertFalse(loading)
        resumed.value = false; runCurrent()
        resumed.value = true; runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertFalse(loading)
        assertEquals(1, dispatches) // Back never reissues the retained query.
    }

    @Test fun `silent empty projection settles at three seconds without another emission`() = runTest {
        val rows = MutableStateFlow(emptyList<String>())
        var loading = true
        val transitions = mutableListOf<Boolean>()
        var projections = 0
        backgroundScope.launch {
            rows.collectWithInitialSearchLoading(true, { true }, { it.isNotEmpty() }, {
                loading = it; transitions += it
            }) { projections++ }
        }
        runCurrent()
        advanceTimeBy(2_999); runCurrent()
        assertTrue(loading)
        advanceTimeBy(1); runCurrent()
        assertFalse(loading)
        assertEquals(1, projections)
        assertEquals(listOf(false), transitions)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(false), transitions) // One deadline, no polling.
    }

    @Test fun `first results settle once and disappearing results cannot restart loading`() = runTest {
        val rows = MutableStateFlow(emptyList<String>())
        var loading = true
        var displayed = emptyList<String>()
        val transitions = mutableListOf<Boolean>()
        backgroundScope.launch {
            rows.collectWithInitialSearchLoading(true, { true }, { it.isNotEmpty() }, {
                loading = it; transitions += it
            }) { displayed = it }
        }
        runCurrent()
        advanceTimeBy(100)
        rows.value = listOf("early"); runCurrent()
        assertFalse(loading)
        rows.value = emptyList(); runCurrent()
        assertFalse(loading)
        assertTrue(displayed.isEmpty())
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(false), transitions)
    }

    @Test fun `late results still appear after the initial wait expires`() = runTest {
        val rows = MutableSharedFlow<List<String>>()
        var loading = true
        var displayed = emptyList<String>()
        backgroundScope.launch {
            rows.collectWithInitialSearchLoading(true, { true }, { it.isNotEmpty() }, { loading = it }) {
                displayed = it
            }
        }
        runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertFalse(loading)
        rows.emit(listOf("late")); runCurrent()
        assertEquals(listOf("late"), displayed)
        assertFalse(loading)
    }

    @Test fun `cached return settles immediately even before its first local projection`() = runTest {
        var loading = true
        backgroundScope.launch {
            MutableSharedFlow<List<String>>().collectWithInitialSearchLoading(
                false, { true }, { it.isNotEmpty() }, { loading = it }, {},
            )
        }
        runCurrent()
        assertFalse(loading)
    }

    @Test fun `old deadline and late projection cannot publish into a replacement query`() = runTest {
        val rows = MutableSharedFlow<List<String>>()
        var current = true
        var writes = 0
        var projections = 0
        backgroundScope.launch {
            rows.collectWithInitialSearchLoading(true, { current }, { it.isNotEmpty() }, { writes++ }) {
                projections++
            }
        }
        runCurrent()
        advanceTimeBy(2_999)
        current = false // Query/token/session fence changes before debounce cancels the collector.
        advanceTimeBy(1); runCurrent()
        rows.emit(listOf("stale")); runCurrent()
        assertEquals(0, writes)
        assertEquals(0, projections)
    }

    @Test fun `cover or teardown cancels the deadline with the projection`() = runTest {
        var current = true
        var writes = 0
        val job = backgroundScope.launch {
            MutableSharedFlow<List<String>>().collectWithInitialSearchLoading(
                true, { current }, { it.isNotEmpty() }, { writes++ }, {},
            )
        }
        runCurrent()
        advanceTimeBy(500)
        current = false
        job.cancel(); runCurrent()
        assertTrue(job.isCompleted)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0, writes)
    }

    @Test fun `projection failure ends loading and still propagates the failure`() = runTest {
        var loading = true
        val failure = IllegalStateException("fixture")
        val result = runCatching {
            flow<List<String>> { throw failure }.collectWithInitialSearchLoading(
                true, { true }, { it.isNotEmpty() }, { loading = it }, {},
            )
        }
        // Coroutine stack-trace recovery may copy the exception across the child scope.
        assertEquals(failure.javaClass, result.exceptionOrNull()?.javaClass)
        assertEquals(failure.message, result.exceptionOrNull()?.message)
        assertFalse(loading)
    }
}
