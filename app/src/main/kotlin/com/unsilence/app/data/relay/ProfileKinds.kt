package com.unsilence.app.data.relay

/** Canonical author-timeline kinds shared by profile subscriptions and projections. */
internal val PROFILE_NOTE_REPLY_EVENT_KINDS = listOf(
    1, 6, 16, 20, 21, 22, 1111, 34235, 34236, 1068,
)

internal val PROFILE_NOTE_REPLY_EVENT_KIND_SET = PROFILE_NOTE_REPLY_EVENT_KINDS.toSet()
