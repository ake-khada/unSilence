package com.unsilence.app.ui.thread

import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.OutboxRelayResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.transform

/** One root and its request targets; published together to the active observation. */
internal data class ThreadRelayRoute(
    val rootId: String,
    val compatibilityRelays: List<String>,
    val replyRelays: List<String>,
    val rootAuthor: String? = null,
)

/** Route-only changes must reach the observer even when root and coordinate match. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun MutableStateFlow<ThreadRelayRoute?>.withThreadCoordinate(
    feedSignals: Flow<Long>,
    articleCoord: (String) -> String?,
    rootIsKnown: (String) -> Boolean = { false },
    refreshMissingRoot: suspend (ThreadRelayRoute) -> ThreadRelayRoute = { it },
): Flow<Pair<ThreadRelayRoute, String?>> = flatMapLatest { route ->
    if (route == null) emptyFlow()
    else feedSignals.transform {
        // One local lookup only while identity is missing, not a relay-list rescan
        // per insert. The root can arrive after the ancestor walk has finished.
        if (route.rootAuthor == null && rootIsKnown(route.rootId)) {
            val refined = refreshMissingRoot(route)
            if (refined != route) {
                compareAndSet(route, refined)
                return@transform // The replacement observation emits the complete route.
            }
        }
        emit(route to articleCoord(route.rootId))
    }.distinctUntilChanged()
}

internal fun resolveThreadRelayRoute(
    rootId: String,
    tappedId: String,
    event: (String) -> EventEntity?,
    lookupWriteRelays: (String) -> List<String>,
    resolver: OutboxRelayResolver,
    ownReadRelays: List<String>,
    blockedRelays: Set<String>,
    hints: List<String>,
): ThreadRelayRoute {
    val rootAuthor = event(rootId)?.pubkey
    val tapped = event(tappedId)
    val relatedAuthors = listOfNotNull(tapped?.pubkey, tapped?.replyToId?.let(event)?.pubkey)
    val compatibility = if (rootAuthor != null) {
        (lookupWriteRelays(rootAuthor) + resolver.resolveEngagementRelays(
            rootAuthor, ownReadRelays, blockedRelays, includeAuthorInbox = false,
        )).distinct()
    } else ownReadRelays.ifEmpty { GLOBAL_RELAY_URLS }
    val urls = (hints + compatibility).distinct()
    val replies = resolver.resolveThreadReplyRelays(rootAuthor, relatedAuthors, ownReadRelays, hints, blockedRelays)
    return ThreadRelayRoute(rootId, urls, replies, rootAuthor)
}
