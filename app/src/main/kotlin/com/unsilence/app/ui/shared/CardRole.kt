package com.unsilence.app.ui.shared

/**
 * Controls density and feature toggles for [EventCard] rendering.
 *
 * Each role is a rendering preset, not a screen identifier.
 * A screen chooses the role that matches the density it wants.
 */
enum class CardRole {
    /** Full-density feed card with inline video, media grid, actions. */
    Feed,

    /** Thread focused note — full density, no inline video. */
    Thread,

    /** Thread reply — full density with indent decoration handled by caller. */
    Reply,

    /** Profile posts/replies tab — same density as Feed. */
    Profile,

    /** Article card — hero image, title, summary, action bar. */
    Article,

    /** Search results — no inline video. */
    Search,

    /** Compact embedded card inside a quote or address reference. */
    Embedded,

    /** Compact card inside a notification row. */
    NotificationCompact,
}
