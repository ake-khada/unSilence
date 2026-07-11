package com.unsilence.app.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.hilt.navigation.compose.hiltViewModel
import com.unsilence.app.ui.common.LoadingScreen
import com.unsilence.app.ui.navigation.AppNavigation

@Composable
fun RootScreen(viewModel: RootViewModel = hiltViewModel()) {
    if (viewModel.isLoggingOut) {
        LoadingScreen()
    } else if (viewModel.isLoggedIn) {
        // key(pubkey) forces full composition tree recreation when the user
        // changes. Without this, AppNavigation and its NavBackStackEntry-scoped
        // ViewModels survive the logout/login transition and retain the old
        // user's pubkey in captured init values (e.g. FeedVM.ownPubkey).
        val pubkey = viewModel.keyManager.getPublicKeyHex() ?: ""
        val sessionKey = "$pubkey-${viewModel.sessionId}"
        key(sessionKey) {
            AppNavigation(
                ownPubkey = pubkey,
                sessionKey = sessionKey,
                onLogout = viewModel::logout,
            )
        }
    } else {
        OnboardingScreen(
            keyManager = viewModel.keyManager,
            onComplete = viewModel::onOnboardingComplete,
        )
    }
}
