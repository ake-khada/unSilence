package com.unsilence.app.data.blossom

import com.unsilence.app.data.init.InitGate
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.di.ImageClient
import dagger.Lazy
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Bounded failure metadata and in-flight work only. Verified bytes belong to Coil's existing cache. */
@Singleton
class AvatarRecovery @Inject constructor(
    private val mes: Lazy<MemoryEventStore>,
    private val preferences: RelayPreferencesStore,
    private val gate: InitGate,
    @ImageClient imageClient: OkHttpClient,
) {
    internal val transport = AvatarTransport(imageClient.newBuilder()
        .followRedirects(false).followSslRedirects(false).callTimeout(5, TimeUnit.SECONDS).build())
    private val lock = Any()
    private val failed = AvatarFailureBackoff()
    private val active = mutableSetOf<Job>()
    private val pending = mutableMapOf<String, CompletableDeferred<VerifiedAvatar?>>()
    private val permits = Semaphore(2)

    fun clear() = synchronized(lock) {
        failed.clear()
        active.toList().forEach { it.cancel() }
        active.clear()
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    internal suspend fun recover(author: String, original: String, blob: AvatarBlob): VerifiedAvatar? {
        val session = gate.currentSession
        repeat(2) {
            try {
                return recoverOnce(author, original, blob)
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (!gate.isCurrent(session)) throw cancelled
                // Another card owned the cancelled attempt. A still-visible caller can retry once.
            }
        }
        return null
    }

    private suspend fun recoverOnce(author: String, original: String, blob: AvatarBlob): VerifiedAvatar? = coroutineScope {
        val session = gate.currentSession
        val prefs = preferences.avatarFallbackPreferences.value
        val store = mes.get()
        val key = "$session|$author|$original|$prefs|${store.blossomServersFor(author).take(3)}"
        val deferred: CompletableDeferred<VerifiedAvatar?>
        val leader: Boolean
        synchronized(lock) {
            if (failed.contains(key)) return@coroutineScope null
            val existing = pending[key]
            if (existing == null && pending.size >= 16) return@coroutineScope null
            leader = existing == null
            deferred = existing ?: CompletableDeferred<VerifiedAvatar?>().also { pending[key] = it }
        }
        if (!leader) return@coroutineScope deferred.await()
        val job = currentCoroutineContext()[Job]!!
        synchronized(lock) { active.add(job) }
        try {
            val result = withAvatarAttemptBudget(permits) attempt@ {
                fun current() = gate.isCurrent(session) && preferences.avatarFallbackPreferences.value == prefs
                if (!current()) return@attempt null
                val candidates = AvatarFallbackPolicy.candidates(blob, original, store.blossomServersFor(author), prefs)
                var attempts = 0
                for (url in candidates) {
                    if (!current()) return@attempt null
                    val attemptKey = "attempt:$session:${blob.hash}:$url"
                    if (failed.contains(attemptKey)) continue
                    if (attempts++ == 6) break
                    val image = transport.fetch(url, blob.hash,
                        hostBlocked = { failed.contains("host:$session:$it") },
                        onTransportFailure = { if (current()) failed.record("host:$session:$it") })
                    if (image == null && current()) failed.record(attemptKey)
                    if (image != null && current()) return@attempt image
                }
                null
            }
            if (result == null && gate.isCurrent(session)) failed.record(key)
            deferred.complete(result)
            result
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                if (pending[key] === deferred) pending.remove(key)
                deferred.cancel(cancelled)
            }
            throw cancelled
        } catch (error: Exception) {
            deferred.completeExceptionally(error)
            throw error
        } finally {
            synchronized(lock) {
                active.remove(job)
                if (pending[key] === deferred) pending.remove(key)
            }
        }
    }
}

/** Budget the actual recovery separately from waiting for one of the bounded slots. */
internal suspend fun <T> withAvatarAttemptBudget(permits: Semaphore, block: suspend () -> T): T? =
    permits.withPermit { withTimeoutOrNull(15_000) { block() } }
