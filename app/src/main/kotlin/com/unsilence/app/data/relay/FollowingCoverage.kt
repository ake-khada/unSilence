package com.unsilence.app.data.relay

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** Reuse the existing relay-metadata coalescing interval, not a new retry policy. */
internal const val RELAY_METADATA_DEBOUNCE_MS = 2_000L

/** Only loss of coverage that removed fallback authors triggers a background rebuild. */
@OptIn(FlowPreview::class)
internal fun followingCoverageLosses(
    dependencies: Flow<Set<String>>,
    healthyRelays: Flow<Set<String>>,
): Flow<Unit> = combine(dependencies, healthyRelays) { required, healthy -> required - healthy }
    .distinctUntilChanged()
    .debounce(RELAY_METADATA_DEBOUNCE_MS)
    .filter { it.isNotEmpty() }
    .map { Unit }
