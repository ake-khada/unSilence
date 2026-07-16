package com.unsilence.app.ui.search

import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.ui.navigation.DeepLinkTarget
import com.unsilence.app.ui.navigation.resolveDeepLink

private val SEARCH_HEX_PUBKEY_REGEX = Regex("^[0-9a-fA-F]{64}$")
private val SEARCH_ENTITY_PREFIXES = listOf("npub1", "nprofile1", "note1", "nevent1", "naddr1")

internal sealed interface SearchEntityInput {
    data object None : SearchEntityInput
    data object RejectedSecret : SearchEntityInput
    data class Resolved(val target: DeepLinkTarget) : SearchEntityInput
}

/** Detect only address-like search input; ordinary text never pays the NIP-19 parse cost. */
internal fun detectSearchEntityInput(
    query: String,
    resolver: (String) -> DeepLinkTarget? = ::resolveDeepLink,
): SearchEntityInput {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return SearchEntityInput.None
    val hasScheme = trimmed.startsWith("nostr:", ignoreCase = true)
    val payload = if (hasScheme) trimmed.substringAfter(':').trim() else trimmed
    if (payload.startsWith("nsec1", ignoreCase = true)) {
        return SearchEntityInput.RejectedSecret
    }
    val looksNavigable = SEARCH_HEX_PUBKEY_REGEX.matches(payload) ||
        SEARCH_ENTITY_PREFIXES.any { payload.startsWith(it, ignoreCase = true) }
    if (!looksNavigable) return SearchEntityInput.None

    return resolver("nostr:$payload")?.let(SearchEntityInput::Resolved) ?: SearchEntityInput.None
}

/** A directly resolved profile owns the first row; text matches continue below without duplicates. */
internal fun peopleBelowEntityResult(
    target: DeepLinkTarget?,
    people: List<UserEntity>,
): List<UserEntity> = when (target) {
    is DeepLinkTarget.Profile -> people.filterNot { it.pubkey.equals(target.pubkey, ignoreCase = true) }
    else -> people
}
