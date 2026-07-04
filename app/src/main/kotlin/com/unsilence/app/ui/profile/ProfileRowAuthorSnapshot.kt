package com.unsilence.app.ui.profile

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.UserEntity

internal fun FeedRow.withProfileAuthorSnapshot(profile: UserEntity?): FeedRow {
    if (profile == null || pubkey != profile.pubkey) return this
    return copy(
        authorName = profile.name?.takeIf { it.isNotBlank() } ?: authorName,
        authorDisplayName = profile.displayName?.takeIf { it.isNotBlank() } ?: authorDisplayName,
        authorPicture = profile.picture?.takeIf { it.isNotBlank() } ?: authorPicture,
        authorNip05 = profile.nip05?.takeIf { it.isNotBlank() } ?: authorNip05,
    )
}
