package com.unsilence.app.ui.profile

import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.relay.PROFILE_NOTE_REPLY_EVENT_KINDS
import com.unsilence.app.ui.feed.FeedContentFilter

internal fun profileKindsForTab(tab: ProfileTab): List<Int> = when (tab) {
    ProfileTab.NOTES, ProfileTab.REPLIES -> PROFILE_NOTE_REPLY_EVENT_KINDS
    ProfileTab.LONGFORM -> listOf(30023)
}

internal fun matchesProfileContentFilter(
    event: NostrEvent,
    filter: FeedContentFilter,
): Boolean {
    val isRepost = event.kind == 6 || event.kind == 16
    return when (filter) {
        FeedContentFilter.NOTES_ONLY ->
            isRepost || (
                event.kind != 1111 &&
                    event.replyToId == null &&
                    event.rootId == null
                )
        // NIP-22 addressable comments can carry only A/a coordinates, with no event id.
        FeedContentFilter.REPLIES_ONLY ->
            event.kind == 1111 ||
                (!isRepost && (event.replyToId != null || event.rootId != null))
    }
}
