package com.unsilence.app.ui.navigation

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.ui.profile.SettingsPage

internal fun articleDestination(row: FeedRow, focusedCommentId: String? = null) =
    AppDestination.Article(
        eventId = row.id,
        relayHints = (listOf(row.relayUrl) + row.relaysSeen).filter { it.isNotBlank() }.distinct().take(12),
        focusedCommentId = focusedCommentId,
    )

internal fun settingsDestination(page: SettingsPage): AppDestination = when (page) {
    SettingsPage.RELAYS -> AppDestination.RelaySettings
    SettingsPage.MEDIA_UPLOADS -> AppDestination.MediaUploads
    SettingsPage.ZAPS -> AppDestination.ZapSettings
    SettingsPage.FILTERS -> AppDestination.Filters
    SettingsPage.DRAFTS -> AppDestination.Drafts
    SettingsPage.EMOJIS -> AppDestination.EmojiSettings
    SettingsPage.SOCIAL_GRAPH -> AppDestination.SocialGraph
    SettingsPage.KEYS -> AppDestination.Keys
    SettingsPage.CONSOLE -> AppDestination.Console
}
