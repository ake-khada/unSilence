package com.unsilence.app.ui.search

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Leaving bypasses the query debounce so collectLatest cancels the covered projection. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal fun StateFlow<Boolean>.searchQueriesWhileResumed(query: StateFlow<String>): Flow<String?> =
    flatMapLatest { resumed ->
        if (resumed) query.debounce(1_000).distinctUntilChanged() else flowOf(null)
    }

/** A retained visit keeps rows, never a covered screen's relay subscriptions. */
internal class SearchRequestLifetime(
    private val closeRequest: (Long) -> Unit,
    private val nextToken: () -> Long = {
        tokens.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) }
    },
) {
    private val current = AtomicLong(0)
    private val wireToken = AtomicLong(0)
    private var needsRequest = true // Main-thread owned, like query changes.
    val token: Long get() = current.get()

    fun queryChanged() {
        close()
        needsRequest = true
    }

    fun begin(): SearchRequestAttempt {
        close()
        val attempt = SearchRequestAttempt(nextToken(), needsRequest)
        needsRequest = false
        current.set(attempt.token)
        if (attempt.fetch) wireToken.set(attempt.token)
        return attempt
    }

    fun close(token: Long = current.get()) {
        if (token != 0L && current.compareAndSet(token, 0) && wireToken.compareAndSet(token, 0)) {
            closeRequest(token)
        }
    }

    private companion object { val tokens = AtomicLong(0) }
}

internal data class SearchRequestAttempt(val token: Long, val fetch: Boolean)
