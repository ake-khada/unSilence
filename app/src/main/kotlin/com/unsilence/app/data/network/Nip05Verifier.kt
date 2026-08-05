package com.unsilence.app.data.network

import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.memory.SnapshotScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

interface Nip05VerificationController {
    fun verificationFlow(pubkey: String, nip05: String): Flow<Nip05VerificationStatus>
    fun requestIfEligible(pubkey: String, nip05: String)
    fun markProfileOpened(pubkey: String)
}

@Singleton
class Nip05Verifier @Inject constructor(
    httpClient: Nip05HttpClient,
    memoryEventStore: MemoryEventStore,
    snapshotScheduler: SnapshotScheduler,
) : Nip05VerificationController {
    private val coordinator = Nip05ResolutionCoordinator(
        memoryEventStore = memoryEventStore,
        fetch = httpClient::resolve,
        onStored = snapshotScheduler::scheduleImmediate,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    override fun verificationFlow(
        pubkey: String,
        nip05: String,
    ): Flow<Nip05VerificationStatus> =
        coordinator.verificationFlow(pubkey, nip05)

    override fun requestIfEligible(pubkey: String, nip05: String) {
        coordinator.requestIfEligible(pubkey, nip05)
    }

    override fun markProfileOpened(pubkey: String) {
        coordinator.markProfileOpened(pubkey)
    }
}

internal class Nip05ResolutionCoordinator(
    private val memoryEventStore: MemoryEventStore,
    private val fetch: suspend (Nip05VerificationCacheKey) -> Nip05VerificationStatus?,
    private val onStored: () -> Unit,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val staggerMs: (Nip05VerificationCacheKey) -> Long = { key ->
        MIN_STAGGER_MS + ((key.hashCode() and Int.MAX_VALUE) % STAGGER_SPREAD_MS)
    },
) {
    private val inFlight = ConcurrentHashMap<Nip05VerificationCacheKey, Job>()
    private val transientFailureAt = ConcurrentHashMap<Nip05VerificationCacheKey, Long>()
    private val networkPermits = Semaphore(Nip05HttpClient.MAX_CONCURRENT_RESOLUTIONS)
    private val eligibilityLock = Any()
    private val explicitlyOpenedPubkeys = LinkedHashSet<String>()
    private val visibleClaims = LinkedHashSet<Nip05VerificationCacheKey>()
    private var eligibilityOwner: String? = null

    init {
        scope.launch {
            memoryEventStore.followsSignalFlow.collect {
                retryVisibleClaims()
            }
        }
    }

    fun verificationFlow(
        pubkey: String,
        nip05: String,
    ): Flow<Nip05VerificationStatus> =
        memoryEventStore.nip05VerificationFlow(pubkey, nip05)

    fun markProfileOpened(pubkey: String) {
        val owner = synchronizeOwner() ?: return
        val target = normalizeNip05Pubkey(pubkey) ?: return
        synchronized(eligibilityLock) {
            if (eligibilityOwner != owner) return
            explicitlyOpenedPubkeys.remove(target)
            explicitlyOpenedPubkeys.add(target)
            while (explicitlyOpenedPubkeys.size > MAX_EXPLICITLY_OPENED) {
                explicitlyOpenedPubkeys.remove(explicitlyOpenedPubkeys.first())
            }
        }
        retryVisibleClaims(target)
    }

    fun requestIfEligible(pubkey: String, nip05: String) {
        val key = nip05VerificationCacheKey(pubkey, nip05) ?: return
        if (nip05LookupTarget(key.nip05) == null) return
        rememberVisibleClaim(key)
        enqueueIfEligible(key)
    }

    private fun enqueueIfEligible(key: Nip05VerificationCacheKey) {
        val now = nowMs()
        if (memoryEventStore.currentNip05Verification(key, now) != Nip05VerificationStatus.UNKNOWN) {
            forgetVisibleClaim(key)
            return
        }
        val recentFailure = transientFailureAt[key]
        if (recentFailure != null && now - recentFailure < TRANSIENT_RETRY_MS) return
        if (!isEligible(key.pubkey)) return
        if (inFlight.size >= MAX_PENDING_RESOLUTIONS) return

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                resolveIfEligible(key, applyStagger = true)
            } finally {
                currentCoroutineContext()[Job]?.let { runningJob ->
                    inFlight.remove(key, runningJob)
                }
                retryVisibleClaims()
            }
        }
        val existing = inFlight.putIfAbsent(key, job)
        if (existing == null) job.start() else job.cancel()
    }

    internal suspend fun resolveNowForTest(
        pubkey: String,
        nip05: String,
    ): Nip05VerificationStatus {
        val key = nip05VerificationCacheKey(pubkey, nip05)
            ?: return Nip05VerificationStatus.UNKNOWN
        return resolveIfEligible(key, applyStagger = false)
    }

    private suspend fun resolveIfEligible(
        key: Nip05VerificationCacheKey,
        applyStagger: Boolean,
    ): Nip05VerificationStatus {
        val ownerAtStart = synchronizeOwner() ?: return Nip05VerificationStatus.UNKNOWN
        val now = nowMs()
        val cached = memoryEventStore.currentNip05Verification(key, now)
        if (cached != Nip05VerificationStatus.UNKNOWN) return cached
        if (nip05LookupTarget(key.nip05) == null ||
            !memoryEventStore.isCurrentProfileNip05Claim(key) ||
            !isEligible(key.pubkey)
        ) {
            forgetVisibleClaim(key)
            return Nip05VerificationStatus.UNKNOWN
        }

        if (applyStagger) delay(staggerMs(key))
        if (synchronizeOwner() != ownerAtStart ||
            !memoryEventStore.isCurrentProfileNip05Claim(key) ||
            !isEligible(key.pubkey)
        ) {
            forgetVisibleClaim(key)
            return Nip05VerificationStatus.UNKNOWN
        }

        val fetched = networkPermits.withPermit { fetch(key) }
        if (synchronizeOwner() != ownerAtStart) return Nip05VerificationStatus.UNKNOWN
        if (fetched == null || fetched == Nip05VerificationStatus.UNKNOWN) {
            transientFailureAt[key] = nowMs()
            trimTransientFailures()
            return Nip05VerificationStatus.UNKNOWN
        }

        val completedAt = nowMs()
        val entry = Nip05VerificationCacheEntry(
            key = key,
            status = fetched,
            checkedAtMs = completedAt,
            resolvedPubkey = key.pubkey.takeIf { fetched == Nip05VerificationStatus.VERIFIED },
        )
        if (memoryEventStore.storeNip05Verification(entry, completedAt)) {
            transientFailureAt.remove(key)
            forgetVisibleClaim(key)
            onStored()
            return fetched
        }
        forgetVisibleClaim(key)
        return Nip05VerificationStatus.UNKNOWN
    }

    private fun isEligible(targetPubkey: String): Boolean {
        val owner = synchronizeOwner() ?: return false
        val explicitlyOpened = synchronized(eligibilityLock) {
            eligibilityOwner == owner && targetPubkey in explicitlyOpenedPubkeys
        }
        if (explicitlyOpened) return true
        return targetPubkey in memoryEventStore.getFollows(owner).orEmpty()
    }

    private fun rememberVisibleClaim(key: Nip05VerificationCacheKey) {
        synchronizeOwner() ?: return
        synchronized(eligibilityLock) {
            visibleClaims.remove(key)
            visibleClaims.add(key)
            while (visibleClaims.size > MAX_VISIBLE_CLAIMS) {
                visibleClaims.remove(visibleClaims.first())
            }
        }
    }

    private fun forgetVisibleClaim(key: Nip05VerificationCacheKey) {
        synchronized(eligibilityLock) { visibleClaims.remove(key) }
    }

    private fun retryVisibleClaims(targetPubkey: String? = null) {
        val owner = synchronizeOwner() ?: return
        val candidates = synchronized(eligibilityLock) {
            if (eligibilityOwner != owner) return
            visibleClaims.filter { targetPubkey == null || it.pubkey == targetPubkey }
        }
        candidates.forEach(::enqueueIfEligible)
    }

    private fun synchronizeOwner(): String? {
        val current = memoryEventStore.ownPubkey?.let(::normalizeNip05Pubkey)
        var changed = false
        synchronized(eligibilityLock) {
            if (eligibilityOwner != current) {
                eligibilityOwner = current
                explicitlyOpenedPubkeys.clear()
                visibleClaims.clear()
                changed = true
            }
        }
        if (changed) {
            transientFailureAt.clear()
            inFlight.values.forEach(Job::cancel)
            inFlight.clear()
        }
        return current
    }

    private fun trimTransientFailures() {
        if (transientFailureAt.size <= MAX_TRANSIENT_FAILURES) return
        transientFailureAt.entries
            .sortedBy { it.value }
            .take(transientFailureAt.size - MAX_TRANSIENT_FAILURES)
            .forEach { transientFailureAt.remove(it.key, it.value) }
    }

    companion object {
        private const val MIN_STAGGER_MS = 250L
        private const val STAGGER_SPREAD_MS = 1_000
        private const val TRANSIENT_RETRY_MS = 15L * 60 * 1_000
        private const val MAX_PENDING_RESOLUTIONS = 64
        private const val MAX_TRANSIENT_FAILURES = 512
        private const val MAX_EXPLICITLY_OPENED = 256
        private const val MAX_VISIBLE_CLAIMS = 512
    }
}
