package com.unsilence.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import java.util.UUID
import kotlinx.serialization.Serializable

/** Small, serializable destinations; never retain an event, profile or screen in the stack. */
@Serializable
internal sealed interface AppDestination {
    @Serializable data object Tabs : AppDestination
    @Serializable data class Thread(
        val eventId: String,
        val relayHints: List<String> = emptyList(),
        val openArticleOnLoad: Boolean = false,
    ) : AppDestination
    @Serializable data class Profile(val pubkey: String) : AppDestination
    @Serializable data class Compose(
        val replyToEventId: String? = null,
        val quoteEventId: String? = null,
        val articleComment: com.unsilence.app.ui.compose.ArticleCommentTarget? = null,
    ) : AppDestination
    @Serializable data class Article(
        val eventId: String,
        val relayHints: List<String> = emptyList(),
        val focusedCommentId: String? = null,
    ) : AppDestination
    @Serializable data class ResumeDraft(val key: String) : AppDestination
    @Serializable data class EditRelaySet(val dTag: String) : AppDestination
    @Serializable data object Settings : AppDestination
    @Serializable data object EditProfile : AppDestination
    @Serializable data object MediaUploads : AppDestination
    @Serializable data object Filters : AppDestination
    @Serializable data object Keys : AppDestination
    @Serializable data object Console : AppDestination
    @Serializable data object SocialGraph : AppDestination
    @Serializable data object Drafts : AppDestination
    @Serializable data class Connections(
        val pubkey: String,
        val tab: com.unsilence.app.ui.profile.ConnectionsTab,
    ) : AppDestination
    @Serializable data class ProfileRelays(val pubkey: String) : AppDestination
    @Serializable data class RelayDetail(val url: String) : AppDestination
    @Serializable data object RelaySettings : AppDestination
    @Serializable data object RelayDiscovery : AppDestination
    @Serializable data object CreateRelaySet : AppDestination
    @Serializable data object EmojiSettings : AppDestination
    @Serializable data object ZapSettings : AppDestination
    @Serializable data object StartGraph : AppDestination
}

/** Identity belongs to the visit, not the event: repeated visits must not share stores. */
@Serializable
internal data class AppEntry(val id: String, val destination: AppDestination) : NavKey

internal class AppNavigator(
    val backStack: MutableList<NavKey>,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    // Stable across Activity recreation; the navigator object itself is not.
    val stateKey: String = (backStack.first() as AppEntry).id

    fun push(destination: AppDestination) {
        // Ignore a double tap on the current destination, but allow revisiting it later.
        if ((backStack.lastOrNull() as? AppEntry)?.destination == destination) return
        backStack.add(AppEntry(newId(), destination))
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun popToTabs() {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun replaceAboveTabs(destination: AppDestination) {
        popToTabs()
        push(destination)
    }
}

@Composable
internal fun rememberAppNavigator(sessionKey: String): AppNavigator = key(sessionKey) {
    val backStack = rememberNavBackStack(AppEntry("tabs:$sessionKey", AppDestination.Tabs))
    remember(backStack) { AppNavigator(backStack) }
}
