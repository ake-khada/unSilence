package com.unsilence.app.ui.common

import androidx.compose.runtime.compositionLocalOf
import com.unsilence.app.data.network.Nip05VerificationController
import com.unsilence.app.data.network.Nip05VerificationStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

private object NoOpNip05VerificationController : Nip05VerificationController {
    override fun verificationFlow(
        pubkey: String,
        nip05: String,
    ): Flow<Nip05VerificationStatus> = flowOf(Nip05VerificationStatus.UNKNOWN)

    override fun requestIfEligible(pubkey: String, nip05: String) = Unit

    override fun markProfileOpened(pubkey: String) = Unit
}

internal val LocalNip05VerificationController = compositionLocalOf<Nip05VerificationController> {
    NoOpNip05VerificationController
}
