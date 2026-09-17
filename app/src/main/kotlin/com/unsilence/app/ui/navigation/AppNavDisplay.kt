package com.unsilence.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay

/** One motion and ownership policy for content destinations; screen code owns neither. */
@Composable
internal fun AppNavDisplay(navigator: AppNavigator, content: @Composable (AppEntry) -> Unit) {
    key(navigator) {
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
                NavEntry<NavKey>(entry, contentKey = entry.id) { content(entry) }
            },
        )
    }
}
