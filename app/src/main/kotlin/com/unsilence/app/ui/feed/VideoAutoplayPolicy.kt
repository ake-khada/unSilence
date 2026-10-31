package com.unsilence.app.ui.feed

import com.unsilence.app.data.media.automaticMediaAllowed
import com.unsilence.app.data.relay.NetworkConditions
import com.unsilence.app.domain.model.AutomaticMediaMode

internal fun videoAutoplayBlocked(mode: AutomaticMediaMode, conditions: NetworkConditions): Boolean =
    !automaticMediaAllowed(mode, conditions)

internal fun shouldPrepareAfterPolicyStop(isIdle: Boolean, stoppedUrl: String?, currentUrl: String?): Boolean =
    isIdle && stoppedUrl != null && stoppedUrl == currentUrl
