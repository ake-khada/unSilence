package com.unsilence.app.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.unsilence.app.ui.navigation.AppNavigation

@Composable
fun RootScreen(viewModel: RootViewModel = hiltViewModel()) {
    if (viewModel.isLoggedIn) {
        AppNavigation()
    } else {
        OnboardingScreen(
            keyManager = viewModel.keyManager,
            onComplete = viewModel::onOnboardingComplete,
        )
    }
}
