package com.unsilence.app.data.relay

/**
 * Compact reference to an event in a persistent timeline cache. Mirrors
 * Jumble's TTimelineRef = [string, number]. Stored in
 * TimelineService.timelines[key].refs so a second subscribe with the
 * same key can inject `since = head.createdAt + 1` and only fetch deltas.
 */
data class TimelineRef(
    val id: String,
    val createdAt: Long,
)
