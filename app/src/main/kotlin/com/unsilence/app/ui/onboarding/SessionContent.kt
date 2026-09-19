package com.unsilence.app.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator

/**
 * The account boundary owns the shell VMs as well as its nested navigation stores.
 * Keep decorators outside the changing session: replacing the single entry clears
 * the old store, whereas key(session) alone only drops composition. No transition
 * retains a signed-out account's UI. Configuration recreation keeps the same key.
 */
@Composable
internal fun SessionContent(sessionKey: String?, content: @Composable () -> Unit) {
    val currentContent by rememberUpdatedState(content)
    val entryKey = sessionKey?.let { "session:$it" } ?: "signed-out"
    val entries = rememberDecoratedNavEntries(
        backStack = listOf(entryKey),
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = { key -> NavEntry(key) { currentContent() } },
    )
    key(entryKey) { entries.single().Content() }
}
