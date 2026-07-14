package com.unsilence.app.ui.navigation

import androidx.lifecycle.ViewModel
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.GLOBAL_RELAY_URLS
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.RelayPreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@HiltViewModel
class DeepLinkNavigationViewModel @Inject constructor(
    private val router: DeepLinkRouter,
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
    private val relayPreferencesStore: RelayPreferencesStore,
) : ViewModel() {
    val pendingTarget = router.pendingTarget
    val pendingFailure = router.pendingFailure

    fun consume(target: DeepLinkTarget): Boolean = router.consume(target)

    fun consumeFailure(): Boolean = router.consumeFailure()

    fun prefetchProfile(target: DeepLinkTarget.Profile) {
        if (target.relayHints.isNotEmpty()) {
            relayPool.fetchProfilesFromHints(mapOf(target.pubkey to target.relayHints))
        }
    }

    suspend fun resolveAddress(target: DeepLinkTarget.Address): String? {
        memoryEventStore.eventIdForAddress(target.kind, target.pubkey, target.dTag)?.let { return it }

        val relays = buildList {
            addAll(target.relayHints)
            addAll(memoryEventStore.writeRelaysFor(target.pubkey))
            addAll(relayPreferencesStore.indexerRelayUrlsSnapshot())
            addAll(GLOBAL_RELAY_URLS)
        }.distinct()
        relayPool.fetchAddressByCoord(
            rawRelayUrls = relays,
            kind = target.kind,
            author = target.pubkey,
            dTag = target.dTag,
        )

        memoryEventStore.eventIdForAddress(target.kind, target.pubkey, target.dTag)?.let { return it }
        return withTimeoutOrNull(8_000L) {
            memoryEventStore.eventIdForAddressFlow(target.kind, target.pubkey, target.dTag)
                .filterNotNull()
                .first()
        }
    }
}
