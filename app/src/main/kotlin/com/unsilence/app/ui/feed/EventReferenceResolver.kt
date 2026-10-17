package com.unsilence.app.ui.feed

import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.ProfilePipeline
import com.unsilence.app.data.relay.RelayPool
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Shared read-only recovery for embedded references and thread ancestors. */
@Singleton
class EventReferenceResolver @Inject constructor(
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val keyManager: KeyManager,
    private val profilePipeline: ProfilePipeline,
) {
    private val resolution = ReferenceResolution(memoryEventStore, keyManager::getPublicKeyHex, ::fetchOnce)

    suspend fun lookup(
        target: EventReferenceTarget,
        fallbackRelays: List<String> = emptyList(),
    ): EventEntity? = resolution.lookup(target, fallbackRelays)

    fun observe(target: EventReferenceTarget): Flow<ReferenceState<EventEntity>> = resolution.observe(target)

    private suspend fun fetchOnce(request: ReferenceResolution.Request, retry: Boolean): EventEntity? =
        withContext(Dispatchers.IO) {
            withReferenceDiscovery(
                discover = {
                    // Pipeline-owned work is already coalesced. This child only waits
                    // for relay facts; resolution cancels that wait when it finishes.
                    request.target.authorPubkey?.let { author ->
                        profilePipeline.fetchProfileRelayFacts(author)
                    }
                },
            ) { resolveReference(request, retry) }
        }

    private suspend fun resolveReference(request: ReferenceResolution.Request, retry: Boolean): EventEntity? {
        val target = request.target
        resolution.cached(request)?.let { return it }

        val curatedFallback = run {
            val ownPk = request.owner.orEmpty()
            request.fallbackRelays.ifEmpty {
                memoryEventStore.readRelaysFor(ownPk).ifEmpty { GLOBAL_RELAY_URLS }
            }
        }

        val id = target.eventId
        val idSuppressed = id?.let(relayPool::isEventUnresolved) == true

        val outboxRelays = target.authorPubkey
            ?.let(memoryEventStore::lookupWriteRelaysFor)
            .orEmpty()
        val relayLadder = eventReferenceRelayLadder(
            target = target,
            browseRelayHints = relayPool.activeFeedRelayHints(),
            idFallbackRelays = outboxRelays + curatedFallback,
        )
        val idTargets = relayLadder.eventId
        val idHintTargets = idTargets?.hints.orEmpty()
        val fallbackTargets = idTargets?.fallback.orEmpty()
        val bridgeIdTargets = relayLadder.eventIdBridgeFallback
        val phases = ReferencePhaseBudget(
            steps = listOf(
                id != null && !idSuppressed && idHintTargets.isNotEmpty(),
                id != null && !idSuppressed && fallbackTargets.isNotEmpty(),
                id != null && bridgeIdTargets.isNotEmpty(),
            ).count { it } + if (target.address != null) 3 else 0,
        )

        // Phase 1: wire hints/source relay. Completion now means EOSE/timeout,
        // not merely that the coalescer dispatched the REQ.
        if (id != null && !idSuppressed && idHintTargets.isNotEmpty()) {
            phases.run {
                relayPool.fetchEventById(id, idHintTargets, bypassDedup = retry)
            }
            resolution.cached(request)?.let { return it }
        }

        // Phase 2: cached/discovered author outbox plus curated read relays.
        if (id != null && !idSuppressed && fallbackTargets.isNotEmpty()) {
            phases.run {
                relayPool.fetchEventsByIdsFromTargets(
                    eventIds = listOf(id),
                    targetRelayUrls = fallbackTargets,
                    bypassDedup = true,
                    excludedRelayUrls = idHintTargets,
                )
            }
            resolution.cached(request)?.let { return it }
        }

        // Phase 3: a stale/missing revision id cannot block an addressable
        // parent or repost. Include late-arriving outbox facts on this read.
        val addressLadder = target.address?.let { address ->
            eventReferenceRelayLadder(
                target = target,
                browseRelayHints = relayPool.activeFeedRelayHints(),
                idFallbackRelays = emptyList(),
                addressFallbackRelays =
                    memoryEventStore.lookupWriteRelaysFor(address.authorPubkey) + curatedFallback,
            )
        }
        target.address?.let { address ->
            val addressTargets = addressLadder?.address
            val addressHintTargets = addressTargets?.hints.orEmpty()
            val addressFallbackTargets = addressTargets?.fallback.orEmpty()
            if (addressHintTargets.isNotEmpty()) {
                phases.run {
                    relayPool.fetchAddressByCoord(
                        rawRelayUrls = addressHintTargets,
                        kind = address.kind,
                        author = address.authorPubkey,
                        dTag = address.dTag,
                    )
                }
                resolution.cached(request)?.let { return it }
            }
            if (addressFallbackTargets.isNotEmpty()) {
                phases.run {
                    relayPool.fetchAddressByCoord(
                        rawRelayUrls = addressFallbackTargets,
                        kind = address.kind,
                        author = address.authorPubkey,
                        dTag = address.dTag,
                    )
                }
            }
            resolution.cached(request)?.let { return it }
        }

        // Final miss tier: the bridge is intentionally after every existing
        // ID and coordinate phase. It is never part of curated/global traffic.
        if (id != null && bridgeIdTargets.isNotEmpty()) {
            phases.run {
                relayPool.fetchEventsByIdsFromBridgeOnMiss(
                    eventIds = listOf(id),
                    alreadyTriedRelayUrls = idTargets?.all.orEmpty(),
                )
            }
            resolution.cached(request)?.let { return it }
        }

        target.address?.let { address ->
            val bridgeAddressTargets = addressLadder?.addressBridgeFallback.orEmpty()
            if (bridgeAddressTargets.isNotEmpty()) {
                phases.run {
                    relayPool.fetchAddressByCoord(
                        rawRelayUrls = bridgeAddressTargets,
                        kind = address.kind,
                        author = address.authorPubkey,
                        dTag = address.dTag,
                    )
                }
            }
            resolution.cached(request)?.let { return it }
        }

        // Relay completion is not a verified-ingestion barrier. Keep a short settle
        // window; the independent observer remains alive after both attempts miss.
        return withTimeoutOrNull(500L) { resolution.watch(request).filterNotNull().first() }
    }
}
