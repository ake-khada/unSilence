package com.unsilence.app.ui.feed

/**
 * Centralized constants for bounded-window feed hydration.
 * Used by [FeedWindowLoader] for bounded-window feed loading.
 */
object FeedWindowConfig {
    const val WINDOW_SIZE = 300
    const val ENGAGEMENT_RELAY_FANOUT = 7        // base / minimum
    const val ENGAGEMENT_RELAY_FANOUT_MAX = 20   // cap
    const val ENGAGEMENT_COVERAGE_TARGET = 0.85
    const val ENGAGEMENT_REFRESH_INTERVAL_MS = 120_000L
    const val PROFILE_FRESHNESS_TTL_MS = 6 * 60 * 60 * 1000L
    const val RELAY_LIST_FRESHNESS_TTL_MS = 24 * 60 * 60 * 1000L
    const val EVENT_DISCOVERY_TIMEOUT_MS = 5_000L
    const val HYDRATION_WORKER_TIMEOUT_MS = 8_000L
}
