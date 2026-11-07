package com.unsilence.app.ui.profile

/** Presentation of already-loaded lists; an unresolved list makes no inbound claim. */
internal data class ProfileFollowState(
    val followingCount: Long? = null,
    val isFollowing: Boolean? = null,
    val followsYou: Boolean = false,
) {
    val relationship: String?
        get() = when {
            followsYou && isFollowing == true -> "Mutual follow"
            followsYou -> "Follows you"
            else -> null
        }
    val actionLabel: String?
        get() = when {
            isFollowing == null -> null
            isFollowing -> "Following"
            followsYou -> "Follow back"
            else -> "Follow"
        }
}

internal fun profileFollowState(
    target: String?,
    viewer: String?,
    targetFollows: Set<String>?,
    viewerFollows: Set<String>?,
): ProfileFollowState = ProfileFollowState(
    followingCount = targetFollows?.size?.toLong(),
    isFollowing = if (target != null) viewerFollows?.contains(target) else null,
    followsYou = viewer != null && target != viewer && viewer in targetFollows.orEmpty(),
)
