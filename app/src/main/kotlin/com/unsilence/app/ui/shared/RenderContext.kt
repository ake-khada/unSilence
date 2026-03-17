package com.unsilence.app.ui.shared

/**
 * Controls density and feature toggles for [EventCard] rendering.
 *
 * Each context is a rendering preset, not a screen identifier.
 * A screen chooses the context that matches the density it wants.
 */
enum class RenderContext {
    /** Full-density feed card with inline video, media grid, actions. */
    Feed,

    /** Thread focused note — full density, no inline video. */
    Thread,

    /** Thread reply — full density with indent decoration handled by caller. */
    ThreadReply,

    /** Profile posts/replies tab — same density as Feed. */
    Profile,

    /** Search results — no inline video. */
    Search,

    /** Compact embedded card inside a notification row. */
    Notification,

    /** Compact embedded card inside a quote. */
    Quote,
}
