package com.unsilence.app.ui.feed

import com.unsilence.app.data.memory.EventEntity
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.toEventEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Store/account boundary shared by cards and explicit thread lookups. */
internal class ReferenceResolution(
    private val memoryEventStore: MemoryEventStore,
    private val currentOwner: () -> String?,
    fetch: suspend (Request, Boolean) -> EventEntity?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    internal data class Request(
        val owner: String?, val target: EventReferenceTarget, val fallbackRelays: List<String>,
    )
    private data class Key(val owner: String?, val id: String?, val address: EventAddressReference?)

    private val recovery = ReferenceRecovery(
        cached = ::cached,
        watch = ::watch,
        fetch = fetch,
        retain = { request: Request -> memoryEventStore.retainRequestedEvent(request.target.eventId) },
        identity = { request: Request ->
            Key(request.owner, request.target.eventId, request.target.address)
        },
    )

    suspend fun lookup(target: EventReferenceTarget, fallbackRelays: List<String> = emptyList()): EventEntity? {
        if (target.lookupKey.isBlank()) return null
        val request = Request(currentOwner(), target, fallbackRelays)
        return recovery.lookup(request).also { event ->
            checkOwner(request)
            event?.let { memoryEventStore.markTouched(it.id) }
        }
    }

    fun observe(target: EventReferenceTarget): Flow<ReferenceState<EventEntity>> = flow {
        if (target.lookupKey.isBlank()) {
            emit(ReferenceState(loading = false))
            return@flow
        }
        val request = Request(currentOwner(), target, emptyList())
        emitAll(recovery.observe(request, watchDisplay(request)))
        checkOwner(request)
    }.flowOn(dispatcher)

    private fun checkOwner(request: Request) {
        if (request.owner != currentOwner()) throw CancellationException("Account changed")
    }

    fun cached(request: Request): EventEntity? {
        checkOwner(request)
        val target = request.target
        return target.eventId?.let(memoryEventStore::getEventEntity)
            ?: target.address?.let { address ->
                memoryEventStore.eventIdForAddress(address.kind, address.authorPubkey, address.dTag)
                    ?.let(memoryEventStore::getEventEntity)
            }
    }

    fun watch(request: Request): Flow<EventEntity?> =
        memoryEventStore.feedSignalFlow.map {
            checkOwner(request)
            storedEvent(request.target)
        // Do not serialize EventEntity tags or parse models on unrelated feed signals.
        }.distinctUntilChanged().map { event ->
            event?.toEventEntity()
        }.flowOn(dispatcher)

    private fun storedEvent(target: EventReferenceTarget): NostrEvent? =
        target.eventId?.let(memoryEventStore::getNostrEvent)
            ?: target.address?.let { address ->
                memoryEventStore.eventIdForAddress(address.kind, address.authorPubkey, address.dTag)
                    ?.let(memoryEventStore::getNostrEvent)
            }

    /**
     * One payload per composed observer, never a global cache or retention lease.
     * Cache pressure is not content revocation. Tombstones still revoke the
     * retained payload, including deletions received after it left the store.
     * Lookup/arrival races continue to use [watch], not this display snapshot.
     */
    private fun watchDisplay(request: Request): Flow<EventEntity?> = flow {
        var retained: NostrEvent? = null
        emitAll(memoryEventStore.feedSignalFlow.map {
            checkOwner(request)
            storedEvent(request.target)?.let { retained = it }
            if (retained?.let(memoryEventStore::isDeleted) == true) retained = null
            retained
        }.distinctUntilChanged().map { it?.toEventEntity() })
    }.flowOn(dispatcher)

}
