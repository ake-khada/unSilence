package com.unsilence.app.data.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** User-editable mute-list dimensions supported by [MemoryEventStore]. */
@Serializable
internal enum class MuteMutationKind {
    User,
    Word,
    Hashtag,
}

/**
 * One desired change to the signed-in user's kind-10000.
 *
 * A mute is always private in unSilence. An unmute removes the value from both
 * the public and private halves because another client may have published it
 * publicly. Keeping this intent instead of a full copied list lets a late relay
 * event become the authoritative base without losing the local edit.
 */
@Serializable
internal data class MuteMutation(
    val kind: MuteMutationKind,
    val value: String,
    val muted: Boolean,
)

/**
 * Durable, per-owner journal of mute edits that have not reached any relay yet.
 * Linked map order is retained on disk because mute-list tag order is the only
 * chronology available to FiltersScreen.
 */
@Serializable
internal data class PendingMutePublish(
    val ownerPubkey: String,
    val revision: Long,
    val userChanges: Map<String, Boolean> = emptyMap(),
    val wordChanges: Map<String, Boolean> = emptyMap(),
    val hashtagChanges: Map<String, Boolean> = emptyMap(),
) {
    val changeCount: Int
        get() = userChanges.size + wordChanges.size + hashtagChanges.size

    fun withMutation(mutation: MuteMutation): PendingMutePublish {
        val normalized = when (mutation.kind) {
            MuteMutationKind.User -> mutation.value.trim()
            MuteMutationKind.Word,
            MuteMutationKind.Hashtag,
            -> mutation.value.trim().lowercase()
        }
        if (normalized.isEmpty()) return this

        fun updated(source: Map<String, Boolean>): Map<String, Boolean> =
            LinkedHashMap(source).apply {
                // Reinsert so a later unmute->mute moves to the newest position.
                remove(normalized)
                put(normalized, mutation.muted)
            }

        return when (mutation.kind) {
            MuteMutationKind.User -> copy(
                revision = revision + 1L,
                userChanges = updated(userChanges),
            )
            MuteMutationKind.Word -> copy(
                revision = revision + 1L,
                wordChanges = updated(wordChanges),
            )
            MuteMutationKind.Hashtag -> copy(
                revision = revision + 1L,
                hashtagChanges = updated(hashtagChanges),
            )
        }
    }

    /** Overlay local intent on the newest authoritative relay/snapshot list. */
    fun applyTo(base: MuteList): MuteList {
        val publicUsers = LinkedHashSet(base.pubkeys)
        val privateUsers = LinkedHashSet(base.privatePubkeys)
        val publicWords = LinkedHashSet(base.words)
        val privateWords = LinkedHashSet(base.privateWords)
        val publicHashtags = LinkedHashSet(base.hashtags)
        val privateHashtags = LinkedHashSet(base.privateHashtags)

        for ((value, muted) in userChanges) {
            if (muted) privateUsers.add(value) else {
                publicUsers.remove(value)
                privateUsers.remove(value)
            }
        }
        for ((value, muted) in wordChanges) {
            if (muted) privateWords.add(value) else {
                publicWords.remove(value)
                privateWords.remove(value)
            }
        }
        for ((value, muted) in hashtagChanges) {
            if (muted) privateHashtags.add(value) else {
                publicHashtags.remove(value)
                privateHashtags.remove(value)
            }
        }

        return base.copy(
            pubkeys = publicUsers,
            words = publicWords,
            hashtags = publicHashtags,
            privatePubkeys = privateUsers,
            privateWords = privateWords,
            privateHashtags = privateHashtags,
        )
    }
}

/** Immutable input to one sign/publish attempt. */
internal data class MutePublishSnapshot(
    val pending: PendingMutePublish,
    val muteList: MuteList,
    val baseEventId: String?,
    val baseCreatedAt: Long?,
)

/**
 * Merge a journal restored from disk with edits made while restore was still
 * running. The in-process journal is newer and wins per key.
 */
internal fun mergePendingMutePublishes(
    restored: PendingMutePublish,
    inProcess: PendingMutePublish,
): PendingMutePublish {
    require(restored.ownerPubkey == inProcess.ownerPubkey)
    if (restored == inProcess) return inProcess

    fun merged(
        older: Map<String, Boolean>,
        newer: Map<String, Boolean>,
    ): Map<String, Boolean> = LinkedHashMap<String, Boolean>().apply {
        putAll(older)
        for ((key, value) in newer) {
            remove(key)
            put(key, value)
        }
    }

    return PendingMutePublish(
        ownerPubkey = restored.ownerPubkey,
        revision = maxOf(restored.revision, inProcess.revision) + 1L,
        userChanges = merged(restored.userChanges, inProcess.userChanges),
        wordChanges = merged(restored.wordChanges, inProcess.wordChanges),
        hashtagChanges = merged(restored.hashtagChanges, inProcess.hashtagChanges),
    )
}

internal fun emptyMuteList(): MuteList = MuteList(
    pubkeys = emptySet(),
    hashtags = emptySet(),
    words = emptySet(),
    eventIds = emptySet(),
)

/** Stable codec shared by the encrypted crash journal and JVM tests. */
internal object PendingMuteJournalCodec {
    private const val MAX_ENCODED_CHARS = 1024 * 1024
    private const val MAX_CHANGES = 100_000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(pending: PendingMutePublish): String? = runCatching {
        json.encodeToString(pending).takeIf { it.length <= MAX_ENCODED_CHARS }
    }.getOrNull()

    fun decode(encoded: String, expectedOwner: String): PendingMutePublish? {
        if (encoded.length > MAX_ENCODED_CHARS) return null
        return runCatching { json.decodeFromString<PendingMutePublish>(encoded) }
            .getOrNull()
            ?.takeIf {
                it.ownerPubkey == expectedOwner &&
                    it.revision >= 0L &&
                    it.changeCount <= MAX_CHANGES
            }
    }
}
