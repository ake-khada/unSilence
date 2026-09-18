package com.unsilence.app.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Resolve the synchronous seed once per handle/mount, not on every card recomposition. */
@Composable
internal fun <T> CardDataFlow<T>.collectCardDataAsState(): State<T> {
    val initial = remember(this) { value }
    return collectAsStateWithLifecycle(initialValue = initial)
}
