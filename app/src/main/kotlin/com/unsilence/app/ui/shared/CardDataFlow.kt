package com.unsilence.app.ui.shared

import kotlinx.coroutines.flow.Flow

/**
 * A cold, per-card observation with a synchronous first-frame snapshot.
 *
 * Unlike stateIn(viewModelScope), this handle creates no job just by being cached.
 * Composition/lifecycle owns collection; an evicted handle stays valid for an
 * already-visible card and becomes collectible as soon as that card leaves.
 */
class CardDataFlow<T>(
    updates: Flow<T>,
    private val snapshot: () -> T,
) : Flow<T> by updates {
    val value: T get() = snapshot()
}
