package com.unsilence.app.data.repository

import com.unsilence.app.data.memory.MuteMutation
import com.unsilence.app.data.memory.MuteMutationKind
import com.unsilence.app.data.memory.MutePublishSnapshot
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.PendingMutePublish
import kotlinx.coroutines.CancellationException

enum class MuteResult {
    /** Local mutation is durable and an immediate sync attempt is queued. */
    Queued,
    /** Local mutation is durable and will sync when bootstrap/network state permits. */
    PendingSync,
    /** No active account, invalid input, or the defensive journal cap was reached. */
    Unavailable,
}

enum class MuteSyncState {
    Preparing,
    Ready,
    WaitingForRelayList,
    EncryptionUnavailable,
}

/**
 * Shared entry-point gate for all six mute/unmute actions. Keeping these mappings
 * together makes it executable in JVM tests and prevents one action from silently
 * forgetting durability or the publish-safe gate.
 */
internal class MuteMutationCoordinator(
    private val record: (MuteMutation) -> PendingMutePublish?,
    private val persist: (PendingMutePublish) -> Boolean,
    private val isPublishSafe: () -> Boolean,
    private val requestPublish: () -> Unit,
) {
    fun muteUser(value: String): MuteResult = mutate(MuteMutationKind.User, value, true)
    fun unmuteUser(value: String): MuteResult = mutate(MuteMutationKind.User, value, false)
    fun muteWord(value: String): MuteResult = mutate(MuteMutationKind.Word, value, true)
    fun unmuteWord(value: String): MuteResult = mutate(MuteMutationKind.Word, value, false)
    fun muteHashtag(value: String): MuteResult = mutate(MuteMutationKind.Hashtag, value, true)
    fun unmuteHashtag(value: String): MuteResult = mutate(MuteMutationKind.Hashtag, value, false)

    private fun mutate(kind: MuteMutationKind, value: String, muted: Boolean): MuteResult {
        val pending = record(MuteMutation(kind, value, muted)) ?: return MuteResult.Unavailable
        if (!persist(pending)) return MuteResult.Unavailable
        return if (isPublishSafe()) {
            requestPublish()
            MuteResult.Queued
        } else {
            MuteResult.PendingSync
        }
    }
}

internal data class SignedMuteList(
    val eventId: String,
    val eventJson: String,
    val event: NostrEvent,
)

internal sealed interface MutePublishResult {
    data object NoPending : MutePublishResult
    data object SigningFailed : MutePublishResult
    data object ChangedWhileSigning : MutePublishResult
    data object NoRelayAccepted : MutePublishResult
    data object SupersededAfterAcceptance : MutePublishResult
    data class Success(val revision: Long, val createdAt: Long) : MutePublishResult
}

/** Safety-critical kind-10000 sign/publish transaction, Android-free for tests. */
internal class MutePublishCoordinator(
    private val loadSnapshot: (String) -> MutePublishSnapshot?,
    private val sign: suspend (MutePublishSnapshot, createdAt: Long) -> SignedMuteList?,
    private val beginPublish: (MutePublishSnapshot) -> Boolean,
    private val rememberSelfPublished: (String) -> Unit,
    private val publishAndAwait: suspend (SignedMuteList) -> Boolean,
    private val commitAccepted: (snapshot: MutePublishSnapshot, event: NostrEvent) -> Boolean,
    private val requestRetry: () -> Unit,
    private val nowSeconds: () -> Long,
) {
    suspend fun publishPending(pubkey: String): MutePublishResult {
        val snapshot = loadSnapshot(pubkey) ?: return MutePublishResult.NoPending
        val createdAt = maxOf(nowSeconds(), (snapshot.baseCreatedAt ?: 0L) + 1L)
        val signed = try {
            sign(snapshot, createdAt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (signed == null) return MutePublishResult.SigningFailed

        if (!beginPublish(snapshot)) {
            requestRetry()
            return MutePublishResult.ChangedWhileSigning
        }
        rememberSelfPublished(signed.eventId)

        val accepted = try {
            publishAndAwait(signed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

        if (!accepted) {
            requestRetry()
            return MutePublishResult.NoRelayAccepted
        }

        if (!commitAccepted(snapshot, signed.event)) {
            requestRetry()
            return MutePublishResult.SupersededAfterAcceptance
        }
        return MutePublishResult.Success(snapshot.pending.revision, createdAt)
    }
}

/** Bounded multi-ID self-echo fence; relay echoes can arrive long after an OK. */
internal class SelfPublishedEventTracker(
    private val maxSize: Int = 32,
    private val ttlMs: Long = 10 * 60 * 1_000L,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val ids = LinkedHashMap<String, Long>()

    @Synchronized
    fun add(eventId: String) {
        pruneLocked()
        ids.remove(eventId)
        ids[eventId] = nowMillis()
        while (ids.size > maxSize) ids.remove(ids.keys.first())
    }

    @Synchronized
    fun contains(eventId: String): Boolean {
        pruneLocked()
        return eventId in ids
    }

    @Synchronized
    internal fun size(): Int {
        pruneLocked()
        return ids.size
    }

    private fun pruneLocked() {
        val cutoff = nowMillis() - ttlMs
        val iterator = ids.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) iterator.remove()
        }
    }
}
