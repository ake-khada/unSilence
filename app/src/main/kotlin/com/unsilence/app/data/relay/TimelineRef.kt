package com.unsilence.app.data.relay

/**
 * Compact reference to an event in a persistent timeline cache. Mirrors
 * Jumble's TTimelineRef = [string, number]. Stored in
 * TimelineService.timelines[key].refs for cached display and pagination.
 * Query coverage is tracked separately; a cached head is not a safe since cursor.
 */
data class TimelineRef(
    val id: String,
    val createdAt: Long,
)
