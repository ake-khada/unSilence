package com.unsilence.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** Pass the state, not its value: only timestamp leaves subscribe to the clock. */
internal val LocalMinuteClock = staticCompositionLocalOf<State<Long>> {
    mutableLongStateOf(System.currentTimeMillis())
}

internal fun millisUntilNextMinute(nowMillis: Long): Long =
    60_000L - Math.floorMod(nowMillis, 60_000L)

/** One Activity-root ticker, suspended in background; refresh immediately on resume. */
@Composable
fun ProvideMinuteClock(content: @Composable () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clock = produceState(System.currentTimeMillis(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = System.currentTimeMillis()
                value = now
                delay(millisUntilNextMinute(now))
            }
        }
    }
    CompositionLocalProvider(LocalMinuteClock provides clock, content = content)
}
