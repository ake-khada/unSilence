package com.unsilence.app.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unsilence.app.data.memory.WotLookup
import kotlinx.coroutines.flow.StateFlow

/** Capture the State, not its map value: hydration must not replace a whole list's host. */
@Composable
internal fun rememberCardWotLookup(flow: StateFlow<Map<String, WotLookup>>): (String) -> WotLookup? {
    val lookups = flow.collectAsStateWithLifecycle()
    return rememberCardWotLookup(lookups)
}

@Composable
internal fun rememberCardWotLookup(lookups: State<Map<String, WotLookup>>): (String) -> WotLookup? {
    return remember(lookups) { { pubkey -> lookups.value[pubkey] } }
}

internal fun cardWotState(pubkey: String, lookup: ((String) -> WotLookup?)?): State<WotLookup?> =
    derivedStateOf(structuralEqualityPolicy()) { lookup?.invoke(pubkey) }

@Composable
internal fun rememberCardWot(pubkey: String, lookup: ((String) -> WotLookup?)?): WotLookup? =
    remember(pubkey, lookup) { cardWotState(pubkey, lookup) }.value
