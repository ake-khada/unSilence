package com.unsilence.app.data.relay

internal const val FOLLOWERS_PAGE_SIZE = 100

internal data class ContactListSnapshot(
    val author: String,
    val eventId: String,
    val createdAt: Long,
    val follows: Set<String>,
)

internal fun latestContactLists(
    events: Collection<ContactListSnapshot>,
): Map<String, ContactListSnapshot> = events
    .groupBy(ContactListSnapshot::author)
    .mapValues { (_, candidates) ->
        candidates.maxWith(
            compareBy<ContactListSnapshot> { it.createdAt }
                .thenByDescending { it.eventId },
        )
    }

internal fun followersFromLatestContactLists(
    events: Collection<ContactListSnapshot>,
    subjectPubkey: String,
): Set<String> = latestContactLists(events)
    .values
    .asSequence()
    .filter { subjectPubkey in it.follows }
    .mapTo(linkedSetOf(), ContactListSnapshot::author)

internal fun followsViewer(follows: Set<String>?, viewerPubkey: String?): Boolean =
    viewerPubkey != null && follows?.contains(viewerPubkey) == true

internal fun nextFollowersCursor(oldestCreatedAt: Long): Long? =
    oldestCreatedAt.takeIf { it > 0L }?.minus(1L)?.coerceAtLeast(0L)

internal data class Nip45CountResult(
    val count: Long,
    val limited: Boolean,
)

internal fun maxFollowerCount(counts: Collection<Nip45CountResult?>): Long? =
    counts.asSequence()
        .filterNotNull()
        .filterNot(Nip45CountResult::limited)
        .map(Nip45CountResult::count)
        .filter { it >= 0L }
        .maxOrNull()

/** Approximate display precision intentionally tops out at two decimals for million-scale counts. */
internal fun formatFollowerCount(count: Long): String {
    val nonNegative = count.coerceAtLeast(0L)
    if (nonNegative < 100L) return nonNegative.toString()

    val rounded = if (nonNegative < 1_000L) {
        (nonNegative + 25L) / 50L * 50L
    } else {
        (nonNegative + 50L) / 100L * 100L
    }
    if (rounded < 1_000L) return "~$rounded"
    if (rounded < 1_000_000L) {
        val whole = rounded / 1_000L
        val tenths = rounded % 1_000L / 100L
        return if (tenths == 0L) "~${whole}k" else "~$whole.${tenths}k"
    }

    val hundredthsOfMillion = (rounded + 5_000L) / 10_000L
    val whole = hundredthsOfMillion / 100L
    val fraction = (hundredthsOfMillion % 100L).toString().padStart(2, '0').trimEnd('0')
    return if (fraction.isEmpty()) "~${whole}M" else "~$whole.${fraction}M"
}
