package com.unsilence.app.data.relay

import android.util.Log
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.WotLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val WOT_TAG = "WotHydrator"

@Singleton
class WotHydrationCoalescer @Inject constructor(
    private val memoryEventStore: MemoryEventStore,
    private val relayPool: RelayPool,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingSubjects: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val staleProfileSubjects: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val inFlight = AtomicBoolean(false)
    private var debounceJob: Job? = null

    fun requestHydration(pubkeys: Collection<String>) {
        val candidates = selectWotHydrationCandidates(
            pubkeys = pubkeys,
            lookup = memoryEventStore::wotFor,
            nowSeconds = System.currentTimeMillis() / 1000L,
            refreshStaleScored = false,
        )
        if (candidates.isEmpty()) return
        pendingSubjects.addAll(candidates)
        schedule()
    }

    fun requestProfileHydration(pubkey: String) {
        val subject = normalizeWotPubkey(pubkey) ?: return
        val state = memoryEventStore.wotFor(subject)
        val nowSeconds = System.currentTimeMillis() / 1000L
        when (state) {
            WotLookup.Pending -> pendingSubjects.add(subject)
            is WotLookup.Scored -> {
                if (nowSeconds - state.assertion.updatedAt >= WOT_STALENESS_SECONDS) {
                    staleProfileSubjects.add(subject)
                }
            }
            WotLookup.Absent -> return
        }
        schedule()
    }

    private fun schedule() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(WOT_HYDRATION_WINDOW_MS)
            dispatch()
        }
    }

    private fun dispatch() {
        if (!inFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val subjects = drainEligibleSubjects()
                if (subjects.isEmpty()) return@launch
                val provider = memoryEventStore.activeWotProvider()
                val ok = relayPool.fetchWotAssertions(
                    providerPubkey = provider.providerPubkey,
                    relayHint = provider.relayHint,
                    subjects = subjects,
                )
                if (!ok) Log.w(WOT_TAG, "WoT hydration failed for ${subjects.size} subjects")
            } finally {
                inFlight.set(false)
                if (pendingSubjects.isNotEmpty() || staleProfileSubjects.isNotEmpty()) {
                    schedule()
                }
            }
        }
    }

    private fun drainEligibleSubjects(): List<String> {
        val pending = pendingSubjects.toList()
        pending.forEach { pendingSubjects.remove(it) }

        val stale = staleProfileSubjects.toList()
        stale.forEach { staleProfileSubjects.remove(it) }

        val nowSeconds = System.currentTimeMillis() / 1000L
        val selected = LinkedHashSet<String>()
        selected.addAll(
            selectWotHydrationCandidates(
                pubkeys = pending,
                lookup = memoryEventStore::wotFor,
                nowSeconds = nowSeconds,
                refreshStaleScored = false,
            )
        )
        selected.addAll(
            selectWotHydrationCandidates(
                pubkeys = stale,
                lookup = memoryEventStore::wotFor,
                nowSeconds = nowSeconds,
                refreshStaleScored = true,
            )
        )
        return selected.toList()
    }
}
