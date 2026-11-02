package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.RelayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*

/** Following coalesces migrations while retaining coarse first-arrival milestones. */
@OptIn(FlowPreview::class)
internal fun followingRelayMetadataRevision(
    lists: Flow<Map<String, RelayList>>,
    follows: Flow<Set<String>>,
): Flow<Int> = flow {
    var previous = emptyMap<String, Set<String>>()
    var previousMilestone = 0
    var revision = 0
    emit(revision)
    combine(lists, follows) { entries, authors ->
        val writes = buildMap {
            for (author in authors) {
                val urls = entries[author]?.write.orEmpty().mapNotNull(::normalizeRelayUrl).toSet()
                if (urls.isNotEmpty()) put(author, urls)
            }
        }
        val pct = if (authors.isEmpty()) 0 else writes.size * 100 / authors.size
        val milestone = when {
            pct >= 90 -> 4
            pct >= 75 -> 3
            pct >= 50 -> 2
            pct >= 25 -> 1
            else -> 0
        }
        writes to milestone
    }.distinctUntilChanged().debounce(RELAY_METADATA_DEBOUNCE_MS)
        .distinctUntilChanged().collect { (current, milestone) ->
            // Pure first arrivals use coverage thresholds; existing authors' changed
            // or removed routes must also invalidate equal-size routing plans.
            val migrated = previous.any { (author, urls) -> current[author] != urls }
            if (migrated || milestone != previousMilestone) emit(++revision)
            previous = current
            previousMilestone = milestone
        }
}

internal data class ProfileRelayRoute<T>(val pubkey: String, val group: T, val urls: List<String>)

/** Shared own/viewed-profile subscription input. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> profileRelayRoutes(
    pubkeys: Flow<String>,
    groups: Flow<T>,
    writes: (String) -> Flow<List<String>>,
): Flow<ProfileRelayRoute<T>> = combine(pubkeys, groups) { pk, group -> pk to group }
    .flatMapLatest { (pk, group) ->
        writes(pk).map { urls ->
            urls.mapNotNull(::normalizeRelayUrl).distinct().sorted().ifEmpty { GLOBAL_RELAY_URLS }
        }.distinctUntilChanged().coalesceRelayChanges().distinctUntilChanged()
            .map { urls -> ProfileRelayRoute(pk, group, urls) }
    }

/** First route is immediate; metadata bursts reuse the existing coalescing interval. */
@OptIn(FlowPreview::class)
private fun <T> Flow<T>.coalesceRelayChanges(): Flow<T> = flow {
    var first = true
    emitAll(debounce {
        if (first) { first = false; 0L } else RELAY_METADATA_DEBOUNCE_MS
    })
}
