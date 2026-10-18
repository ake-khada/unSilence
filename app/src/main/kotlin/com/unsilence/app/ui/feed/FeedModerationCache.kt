package com.unsilence.app.ui.feed

import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.exceedsHashtagCap
import com.unsilence.app.data.memory.isMuted
import com.unsilence.app.data.model.RepostPayload
import java.lang.ref.WeakReference

/**
 * Owned by one account-scoped FeedViewModel. A pass holds its policy snapshot,
 * so concurrent feed/pending passes cannot mix verdicts across settings changes.
 * Mute lists and event content are immutable snapshots; relay provenance is not
 * part of moderation. No lowercase content or parsed hashtag lists are retained.
 */
internal class FeedModerationCache(private val maxEntries: Int = 1000) {
    init {
        require(maxEntries > 0)
    }

    private var current: Verdicts? = null

    @Synchronized
    fun forPolicy(muteList: MuteList?, hashtagCap: Int?): Verdicts {
        val existing = current
        // A restarted collector can see an equal replacement mute-list snapshot.
        // Compare once per pass, never once per event; retain the identity fast path.
        if (existing != null && existing.hashtagCap == hashtagCap &&
            (existing.muteList === muteList || existing.muteList == muteList)
        ) {
            return existing
        }
        return Verdicts(muteList, hashtagCap, maxEntries).also { current = it }
    }

    @Synchronized
    fun clear() {
        current = null
    }

    internal class Verdicts internal constructor(
        internal val muteList: MuteList?,
        internal val hashtagCap: Int?,
        private val maxEntries: Int,
    ) {
        private class Entry(event: NostrEvent, val excluded: Boolean) {
            // Evicted MES/timeline content must not be kept alive by this cache.
            val event = WeakReference(event)
        }

        private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
        // IDs only: a verified target's eviction must not undo its exclusion.
        // Bounded independently so ordinary-note scans cannot flush these facts.
        private val excludedReferences = LinkedHashMap<String, Unit>(16, 0.75f, true)

        fun excludes(
            event: NostrEvent,
            targetResolver: (String) -> NostrEvent?,
            cacheResult: Boolean = true,
        ): Boolean {
            val referenceOnly = (event.kind == 6 || event.kind == 16) &&
                event.repostInfo?.payload !is RepostPayload.VerifiedEmbedded
            if (referenceOnly) {
                synchronized(excludedReferences) {
                    if (excludedReferences[event.id] != null) return true
                }
            }
            // Unresolved/allowed references stay live so late verified targets
            // can exclude them. Only a new policy (or bounded-cache eviction)
            // can forget an exclusion; never cache an allowed missing target.
            val cacheable = cacheResult && !referenceOnly
            if (cacheable) {
                synchronized(entries) {
                    entries[event.id]?.let { if (it.event.get() === event) return it.excluded }
                }
            }

            // Keep the original Unicode/trust policy, including short-circuiting.
            // Do expensive work outside the lock; concurrent misses may duplicate
            // one calculation, but never block each other's entire projection.
            val excluded = isMuted(event, muteList, targetResolver) ||
                exceedsHashtagCap(event, hashtagCap, targetResolver)
            if (referenceOnly) {
                synchronized(excludedReferences) {
                    if (excluded && cacheResult) {
                        excludedReferences[event.id] = Unit
                        if (excludedReferences.size > maxEntries) {
                            val oldest = excludedReferences.entries.iterator()
                            oldest.next()
                            oldest.remove()
                        }
                    }
                    // A concurrent pass may have resolved and excluded this
                    // target while this pass saw a cache miss. Exclusion wins.
                    return excluded || excludedReferences[event.id] != null
                }
            }
            if (cacheable) {
                synchronized(entries) {
                    entries[event.id] = Entry(event, excluded)
                    if (entries.size > maxEntries) {
                        val oldest = entries.entries.iterator()
                        oldest.next()
                        oldest.remove()
                    }
                }
            }
            return excluded
        }
    }
}
