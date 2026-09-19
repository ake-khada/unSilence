package com.unsilence.app.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.unsilence.app.ui.common.LoadingScreen
import com.unsilence.app.ui.navigation.AppNavigation

@Composable
fun RootScreen(viewModel: RootViewModel = hiltViewModel()) {
    val pubkey = viewModel.keyManager.getPublicKeyHex().orEmpty()
    val sessionKey = if (viewModel.isLoggedIn && !viewModel.isLoggingOut) {
        "$pubkey-${viewModel.sessionId}"
    } else null
    SessionContent(sessionKey) {
        if (viewModel.isLoggingOut) {
            LoadingScreen()
        } else if (viewModel.isLoggedIn) {
            AppNavigation(
                ownPubkey = pubkey,
                sessionKey = requireNotNull(sessionKey),
                onLogout = viewModel::logout,
            )
        } else {
            OnboardingScreen(
                keyManager = viewModel.keyManager,
                onComplete = viewModel::onOnboardingComplete,
            )
        }
    }
}
