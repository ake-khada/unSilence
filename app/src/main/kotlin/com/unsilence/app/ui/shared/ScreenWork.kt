package com.unsilence.app.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** Navigation previews may compose at STARTED; only the interactive screen warms its viewport. */
@Composable
internal fun ResumedEffect(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, *keys) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED, block)
    }
}

/** Cancels upstream AND in-flight work on hide; resumes from current state, not queued history. */
internal suspend fun <T> Flow<T>.collectLatestWhileActive(
    active: Flow<Boolean>,
    action: suspend (T) -> Unit,
) {
    active.distinctUntilChanged().collectLatest { visible ->
        if (visible) this@collectLatestWhileActive.collectLatest(action)
    }
}
