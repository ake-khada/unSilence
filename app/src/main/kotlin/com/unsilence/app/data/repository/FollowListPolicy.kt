package com.unsilence.app.data.repository

/** Metadata retained from the owner's latest raw NIP-02 contact-list event. */
internal data class RetainedFollowList(
    val tags: List<List<String>>,
    val content: String,
)

/** Complete kind-3 payload to sign after applying an authoritative follow-set change. */
internal data class FollowListDraft(
    val tags: List<List<String>>,
    val content: String,
)

/**
 * Merges the authoritative materialized follow set with metadata from the last
 * raw kind-3. The set always wins: removed pubkeys disappear, newly-added ones
 * receive bare p-tags, and retained p-tags only decorate pubkeys still present.
 * Non-p tags and legacy NIP-02 content are carried without interpretation.
 */
internal fun mergeFollowListDraft(
    authoritativeFollows: Set<String>,
    retained: RetainedFollowList?,
): FollowListDraft {
    if (retained == null) {
        return FollowListDraft(
            tags = authoritativeFollows.sorted().map { pubkey -> listOf("p", pubkey) },
            content = "",
        )
    }

    // Malformed lists can contain duplicate p-tags. Prefer the row carrying the
    // most metadata so an earlier bare duplicate cannot erase a later hint.
    val bestRetainedTagByPubkey = HashMap<String, List<String>>()
    for (tag in retained.tags) {
        if (tag.firstOrNull() != "p") continue
        val pubkey = tag.getOrNull(1) ?: continue
        if (pubkey !in authoritativeFollows) continue
        val current = bestRetainedTagByPubkey[pubkey]
        if (current == null || tag.size > current.size) {
            bestRetainedTagByPubkey[pubkey] = tag
        }
    }

    val emittedPubkeys = HashSet<String>(authoritativeFollows.size)
    val mergedTags = ArrayList<List<String>>(retained.tags.size + authoritativeFollows.size)

    for (tag in retained.tags) {
        if (tag.firstOrNull() != "p") {
            mergedTags += tag.toList()
            continue
        }

        val pubkey = tag.getOrNull(1) ?: continue
        val bestTag = bestRetainedTagByPubkey[pubkey]
        if (bestTag != null && tag == bestTag && emittedPubkeys.add(pubkey)) {
            mergedTags += bestTag.toList()
        }
    }

    authoritativeFollows
        .asSequence()
        .filterNot(emittedPubkeys::contains)
        .sorted()
        .forEach { pubkey -> mergedTags += listOf("p", pubkey) }

    return FollowListDraft(
        tags = mergedTags,
        content = retained.content,
    )
}
