package com.unsilence.app.ui.search

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Initial-result feedback, not proof of relay completion. One child deadline also
 * settles a silent projection; collection continues so late results still appear.
 * Both projection and deadline use the visit's token/query/session fence on Main.
 */
internal suspend fun <T> Flow<T>.collectWithInitialSearchLoading(
    fetch: Boolean,
    isCurrent: () -> Boolean,
    hasResults: (T) -> Boolean,
    onLoadingChanged: (Boolean) -> Unit,
    onProjection: (T) -> Unit,
) = coroutineScope {
    var settled = false
    fun settle() {
        if (!settled && isCurrent()) {
            settled = true
            onLoadingChanged(false)
        }
    }
    val deadline = if (fetch) launch {
        delay(3_000)
        settle()
    } else null
    if (!fetch) settle()
    try {
        collect { projection ->
            if (isCurrent()) {
                onProjection(projection)
                if (hasResults(projection)) {
                    deadline?.cancel()
                    settle()
                }
            }
        }
    } finally {
        deadline?.cancel()
        settle() // Failure/completion must not strand a current visit's indicator.
    }
}
