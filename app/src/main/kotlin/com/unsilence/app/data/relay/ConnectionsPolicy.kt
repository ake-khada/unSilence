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

internal fun maxFollowerCount(counts: Collection<Long?>): Long? =
    counts.filterNotNull().maxOrNull()
