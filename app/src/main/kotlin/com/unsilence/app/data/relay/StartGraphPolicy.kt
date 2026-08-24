package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.WotAssertionEntity

internal const val FOLLOW_PACK_KIND = 39089
internal const val FOLLOW_PACK_FETCH_LIMIT = 40
internal const val FOLLOW_PACK_MIN_MEMBERS = 3
internal const val FOLLOW_PACK_MAX_MEMBERS = 500
internal const val NOTABLE_PEOPLE_LIMIT = 30

internal data class FollowPack(
    val eventId: String,
    val authorPubkey: String,
    val createdAt: Long,
    val dTag: String,
    val title: String,
    val imageUrl: String?,
    val memberPubkeys: List<String>,
) {
    val coordinate: String get() = "$FOLLOW_PACK_KIND:$authorPubkey:$dTag"
}

internal data class RankedFollowPack(
    val pack: FollowPack,
    val creatorRank: Int?,
    val grapevineOverlap: Int,
)

internal enum class GraphLanding {
    FOLLOWING,
    GLOBAL_TRUSTED,
}

internal fun parseFollowPack(event: NostrEvent): FollowPack? {
    if (event.kind != FOLLOW_PACK_KIND) return null
    val dTag = event.tags.firstTagValue("d")?.takeIf(String::isNotBlank) ?: return null
    val members = event.tags.asSequence()
        .filter { it.firstOrNull() == "p" }
        .mapNotNull { normalizeWotPubkey(it.getOrNull(1)) }
        .distinct()
        .toList()
    if (members.size !in FOLLOW_PACK_MIN_MEMBERS..FOLLOW_PACK_MAX_MEMBERS) return null

    val title = event.tags.firstTagValue("title")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: dTag
    return FollowPack(
        eventId = event.id,
        authorPubkey = event.pubkey,
        createdAt = event.createdAt,
        dTag = dTag,
        title = title,
        imageUrl = event.tags.firstTagValue("image")?.trim()?.takeIf(String::isNotBlank),
        memberPubkeys = members,
    )
}

/** NIP-01 replaceable tie-break: newest timestamp, then lexicographically lowest id. */
internal fun latestFollowPacks(events: Collection<NostrEvent>): List<FollowPack> = events
    .asSequence()
    .mapNotNull(::parseFollowPack)
    .groupBy(FollowPack::coordinate)
    .values
    .map { candidates ->
        candidates.maxWith(
            compareBy<FollowPack> { it.createdAt }
                .thenByDescending { it.eventId },
        )
    }

internal fun rankFollowPacks(
    packs: Collection<FollowPack>,
    assertions: Map<String, WotAssertionEntity>,
): List<RankedFollowPack> = packs
    .map { pack ->
        RankedFollowPack(
            pack = pack,
            creatorRank = assertions[pack.authorPubkey]?.rank,
            grapevineOverlap = pack.memberPubkeys.count(assertions::containsKey),
        )
    }
    .sortedWith(
        compareByDescending<RankedFollowPack> { it.creatorRank ?: Int.MIN_VALUE }
            .thenByDescending { it.grapevineOverlap }
            .thenByDescending { it.pack.createdAt }
            .thenBy { it.pack.title.lowercase() }
            .thenBy { it.pack.eventId },
    )

internal fun topFollowPackMembers(
    pack: FollowPack,
    assertions: Map<String, WotAssertionEntity>,
    limit: Int = 3,
): List<String> = pack.memberPubkeys
    .withIndex()
    .sortedWith(
        compareByDescending<IndexedValue<String>> { assertions[it.value]?.rank ?: Int.MIN_VALUE }
            .thenBy(IndexedValue<String>::index),
    )
    .take(limit)
    .map(IndexedValue<String>::value)

internal fun topNotablePubkeys(
    assertions: Collection<WotAssertionEntity>,
    ownPubkey: String?,
    alreadyFollowed: Set<String>,
    limit: Int = NOTABLE_PEOPLE_LIMIT,
): List<String> = assertions.asSequence()
    .filter { it.subjectPubkey != ownPubkey && it.subjectPubkey !in alreadyFollowed }
    .sortedWith(
        compareByDescending<WotAssertionEntity>(WotAssertionEntity::rank)
            .thenBy(WotAssertionEntity::subjectPubkey),
    )
    .take(limit)
    .map(WotAssertionEntity::subjectPubkey)
    .toList()

internal fun mergeFollowSelections(
    existingFollows: Collection<String>,
    selectedFollows: Collection<String>,
): List<String> = (existingFollows.asSequence() + selectedFollows.asSequence())
    .mapNotNull(::normalizeWotPubkey)
    .distinct()
    .sorted()
    .toList()

internal fun buildFollowContactTags(
    existingFollows: Collection<String>,
    selectedFollows: Collection<String>,
): List<List<String>> = mergeFollowSelections(existingFollows, selectedFollows)
    .map { pubkey -> listOf("p", pubkey) }

internal fun shouldAutoOpenStartGraph(
    freshIdentityPending: Boolean,
    onboardingCompleted: Boolean,
    follows: Set<String>?,
): Boolean = !onboardingCompleted && when {
    freshIdentityPending -> follows.isNullOrEmpty()
    else -> follows != null && follows.isEmpty()
}

/**
 * An identity generated locally has no prior contact list to preserve, so its
 * unresolved graph can be materialized as known-empty before the first publish.
 * Imported and Amber identities remain unresolved until relay state proves otherwise.
 */
internal fun shouldMaterializeEmptyFollows(
    follows: Set<String>?,
    freshIdentityPending: Boolean,
    graphKnownEmpty: Boolean,
): Boolean = follows == null && (freshIdentityPending || graphKnownEmpty)

internal fun shouldShowEmptyFollowingEntry(
    followsResolved: Boolean,
    follows: Set<String>?,
): Boolean = followsResolved && follows != null && follows.isEmpty()

internal fun graphLanding(followsAfterAction: Collection<String>): GraphLanding =
    if (followsAfterAction.isEmpty()) GraphLanding.GLOBAL_TRUSTED else GraphLanding.FOLLOWING

private fun List<List<String>>.firstTagValue(name: String): String? =
    firstOrNull { it.firstOrNull() == name }?.getOrNull(1)
