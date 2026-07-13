package com.unsilence.app.ui.feed

import com.unsilence.app.data.memory.CustomEmoji
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

internal fun pinnedEmojisFlow(
    pinnedShortcodes: Flow<Set<String>>,
    resolvedEmojis: Flow<List<CustomEmoji>>,
): Flow<List<CustomEmoji>> = combine(pinnedShortcodes, resolvedEmojis, ::resolvePinnedEmojis)

internal fun resolvePinnedEmojis(
    pinnedShortcodes: Set<String>,
    resolvedEmojis: List<CustomEmoji>,
): List<CustomEmoji> {
    if (pinnedShortcodes.isEmpty() || resolvedEmojis.isEmpty()) return emptyList()
    val byShortcode = resolvedEmojis.associateBy { it.shortcode }
    return pinnedShortcodes.mapNotNull(byShortcode::get)
}

internal enum class RepostStripAction {
    BOOST,
    QUOTE,
}

internal fun dispatchRepostStripAction(
    action: RepostStripAction,
    noteId: String,
    onRepost: () -> Unit,
    onQuote: (String) -> Unit,
) {
    when (action) {
        RepostStripAction.BOOST -> onRepost()
        RepostStripAction.QUOTE -> onQuote(noteId)
    }
}
