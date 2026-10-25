package com.unsilence.app.ui.search

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SearchRequestLifetimeTest {
    @Test fun `production query flow cancels covered work immediately and return only projects cached rows`() = runTest {
        val resumed = MutableStateFlow(false)
        val query = MutableStateFlow("nostr")
        val sent = mutableListOf<Long>()
        val closed = mutableListOf<Long>()
        val visit = SearchRequestLifetime(closed::add)
        var projections = 0
        backgroundScope.launch {
            resumed.searchQueriesWhileResumed(query).collectLatest { value ->
                visit.close()
                if (value == null) return@collectLatest
                val attempt = visit.begin()
                try {
                    if (attempt.fetch) sent += attempt.token
                    projections++
                    awaitCancellation()
                } finally { visit.close(attempt.token) }
            }
        }
        runCurrent()
        advanceTimeBy(2_000)
        assertTrue(sent.isEmpty())
        resumed.value = true
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertTrue(sent.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, sent.size)
        resumed.value = false
        runCurrent() // No one-second debounce before cancellation/CLOSE.
        assertEquals(sent, closed)
        assertEquals(0L, visit.token)
        resumed.value = true
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, projections)
        assertEquals(1, sent.size)
        resumed.value = false
        runCurrent()
        assertEquals(sent, closed) // Returning did not own a second wire request.
    }

    @Test fun `leaving during debounce sends no request and next visit can finish the explicit query`() = runTest {
        val resumed = MutableStateFlow(true)
        val query = MutableStateFlow("nostr")
        val ready = mutableListOf<String>()
        backgroundScope.launch {
            resumed.searchQueriesWhileResumed(query).collect { if (it != null) ready += it }
        }
        runCurrent()
        advanceTimeBy(500)
        resumed.value = false
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(ready.isEmpty())
        resumed.value = true
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("nostr"), ready)
    }

    @Test fun `covered visit closes its request and returning does not dispatch again`() {
        val closed = mutableListOf<Long>()
        var sequence = 0L
        val visit = SearchRequestLifetime(closed::add) { ++sequence }
        val initial = visit.begin()
        assertTrue(initial.fetch)
        visit.close()
        assertEquals(0L, visit.token)
        assertEquals(listOf(initial.token), closed)
        val returned = visit.begin()
        assertFalse(returned.fetch)
        assertNotEquals(initial.token, returned.token)
        assertEquals(returned.token, visit.token)
        visit.close()
        assertEquals(listOf(initial.token), closed) // No CLOSE for a request never sent.
    }

    @Test fun `explicit query change dispatches but stale cleanup cannot close the new request`() {
        val closed = mutableListOf<Long>()
        var sequence = 0L
        val visit = SearchRequestLifetime(closed::add) { ++sequence }
        val old = visit.begin()
        visit.queryChanged()
        val next = visit.begin()
        visit.close(old.token)
        assertTrue(next.fetch)
        assertEquals(next.token, visit.token)
        assertEquals(listOf(old.token), closed)
        visit.close()
        visit.close()
        assertEquals(listOf(old.token, next.token), closed)
    }

    @Test fun `independent nested visits never share relay tokens`() {
        val parent = SearchRequestLifetime(closeRequest = { })
        val child = SearchRequestLifetime(closeRequest = { })
        assertNotEquals(parent.begin().token, child.begin().token)
    }
}
