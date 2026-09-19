package com.unsilence.app.ui.shared

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Count queued as well as executing writes; reads and cancellable uploads are not edits. */
internal class PendingEdits(private val scope: CoroutineScope) {
    private val pending = MutableStateFlow(0)
    val count = pending.asStateFlow()

    fun launch(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        pending.update { it + 1 }
        return scope.launch(context, block = block).also { job ->
            // Also handles cancellation before the coroutine's first instruction.
            job.invokeOnCompletion { pending.update { it - 1 } }
        }
    }
}
