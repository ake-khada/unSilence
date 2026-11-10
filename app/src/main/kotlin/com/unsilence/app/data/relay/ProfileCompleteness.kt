package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.UserEntity
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow

internal fun profileMissingPicture(user: UserEntity?): Boolean =
    user?.picture.isNullOrBlank()

/** Wait for the current profile phase to settle before considering another tier. */
internal suspend fun awaitProfilePhaseSettlement(
    profileSignal: StateFlow<Long>,
    allPicturesReady: () -> Boolean,
    deadlineMs: Long,
) {
    // Only a fully satisfied batch releases early. Missing/partial profiles keep
    // the original deadline, so no fallback tier starts earlier on incomplete data.
    withTimeoutOrNull(deadlineMs) {
        profileSignal.first { allPicturesReady() }
    }
}
