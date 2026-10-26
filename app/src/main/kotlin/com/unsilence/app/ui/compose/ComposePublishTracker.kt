package com.unsilence.app.ui.compose

import com.unsilence.app.data.relay.normalizeRelayUrl
import java.util.concurrent.ConcurrentHashMap

/** Tracks exactly the resolved dispatch targets, including recipient inboxes. */
internal class ComposePublishTracker(targets: List<String>) {
    private val statuses = ConcurrentHashMap<String, RelayPublishStatus>().apply {
        targets.forEach { put(it, RelayPublishStatus.Pending) }
    }
    fun snapshot(): Map<String, RelayPublishStatus> = HashMap(statuses)
    val hasPending: Boolean get() = statuses.containsValue(RelayPublishStatus.Pending)

    /** Ignore OKs from unrelated sockets, preserving status coverage of the actual target set. */
    fun acknowledge(url: String, success: Boolean): Boolean {
        val key = normalizeRelayUrl(url) ?: url
        return statuses.replace(key, if (success) RelayPublishStatus.Accepted else RelayPublishStatus.Rejected) != null
    }

    fun expirePending() {
        for (url in statuses.keys) statuses.replace(url, RelayPublishStatus.Pending, RelayPublishStatus.TimedOut)
    }
}
