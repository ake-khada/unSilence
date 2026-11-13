package com.unsilence.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.unsilence.app.ui.shared.LocalDisplayWindowActive

/** One motion and ownership policy for content destinations; screen code owns neither. */
@Composable
@OptIn(ExperimentalAnimationApi::class)
internal fun AppNavDisplay(navigator: AppNavigator, content: @Composable (AppEntry) -> Unit) {
    val hostState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    key(navigator.stateKey) {
        NavDisplay(
            backStack = navigator.backStack,
            onBack = navigator::pop,
            modifier = Modifier.fillMaxSize(),
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            transitionSpec = {
                (slideInHorizontally(tween(240), initialOffsetX = { it / 5 }) + fadeIn(tween(160)))
                    .togetherWith(slideOutHorizontally(tween(240), targetOffsetX = { -it / 12 }) + fadeOut(tween(160)))
            },
            popTransitionSpec = {
                (slideInHorizontally(tween(220), initialOffsetX = { -it / 12 }) + fadeIn(tween(160)))
                    .togetherWith(slideOutHorizontally(tween(220), targetOffsetX = { it / 5 }) + fadeOut(tween(160)))
            },
            predictivePopTransitionSpec = {
                (slideInHorizontally(initialOffsetX = { -it / 12 }) + fadeIn())
                    .togetherWith(slideOutHorizontally(targetOffsetX = { it / 5 }) + fadeOut())
            },
            entryProvider = { navKey ->
                val entry = navKey as AppEntry
                NavEntry<NavKey>(entry, contentKey = entry.id) {
                    val transition = LocalNavAnimatedContentScope.current.transition
                    // Predictive Back exposes its incoming entry before the stack is popped.
                    val previous = navigator.backStack.getOrNull(navigator.backStack.lastIndex - 1)
                    val entering = entry == previous && transition.currentState != transition.targetState &&
                        transition.targetState == EnterExitState.Visible
                    val top = entry == navigator.backStack.lastOrNull() &&
                        transition.targetState != EnterExitState.PostExit
                    CompositionLocalProvider(LocalDisplayWindowActive provides
                        ((top || entering) && hostState.isAtLeast(Lifecycle.State.STARTED))) {
                        content(entry)
                    }
                }
            },
        )
    }
}
