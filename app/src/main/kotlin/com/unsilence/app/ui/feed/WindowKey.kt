package com.unsilence.app.ui.feed

import com.unsilence.app.domain.model.FeedFilter
import com.unsilence.app.ui.profile.ProfileTab

sealed class WindowKey {
    data class Home(
        val feedType: FeedType,
        val contentFilter: FeedContentFilter,
        val filter: FeedFilter,
    ) : WindowKey()

    data class Profile(
        val pubkey: String,
        val tab: ProfileTab,
    ) : WindowKey()
}
