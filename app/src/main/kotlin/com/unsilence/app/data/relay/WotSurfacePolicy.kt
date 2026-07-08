package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.memory.toEventModel
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.Segment

private const val DISTANT_HOPS_THRESHOLD = 5

enum class FeedWotDisplayMode {
    NUMBERS,
    EXCEPTIONS_ONLY,
    OFF,
}

sealed interface FeedWotSignal {
    data class Rank(val rank: Int, val distrusted: Boolean = false) : FeedWotSignal
    data object Absent : FeedWotSignal
    data class Distant(val hops: Int) : FeedWotSignal
    data class Distrusted(val muters: Long, val reporters: Long) : FeedWotSignal
}

internal fun wotFeedException(lookup: WotLookup): FeedWotSignal? = when (lookup) {
    WotLookup.Pending -> null
    WotLookup.Absent -> FeedWotSignal.Absent
    is WotLookup.Scored -> {
        val assertion = lookup.assertion
        val muters = assertion.verifiedMuters ?: 0L
        val reporters = assertion.verifiedReporters ?: 0L
        when {
            muters > 0L || reporters > 0L -> FeedWotSignal.Distrusted(muters, reporters)
            (assertion.hops ?: 0) >= DISTANT_HOPS_THRESHOLD -> FeedWotSignal.Distant(assertion.hops!!)
            else -> null
        }
    }
}

internal fun wotFeedSignal(lookup: WotLookup?, mode: FeedWotDisplayMode): FeedWotSignal? {
    val state = lookup ?: WotLookup.Pending
    return when (mode) {
        FeedWotDisplayMode.OFF -> null
        FeedWotDisplayMode.EXCEPTIONS_ONLY -> wotFeedException(state)
        FeedWotDisplayMode.NUMBERS -> when (state) {
            WotLookup.Pending -> null
            WotLookup.Absent -> FeedWotSignal.Absent
            is WotLookup.Scored -> {
                val assertion = state.assertion
                val distrusted = (assertion.verifiedMuters ?: 0L) > 0L ||
                    (assertion.verifiedReporters ?: 0L) > 0L
                FeedWotSignal.Rank(assertion.rank, distrusted)
            }
        }
    }
}

internal fun wotRank(lookup: WotLookup?): Int? =
    (lookup as? WotLookup.Scored)?.assertion?.rank

internal fun wotVerifiedFollowers(lookup: WotLookup?): Long? =
    (lookup as? WotLookup.Scored)?.assertion?.verifiedFollowers

internal fun sortPeopleForSearch(
    users: List<UserEntity>,
    lookup: (String) -> WotLookup?,
): List<UserEntity> =
    users.sortedWith(
        compareByDescending<UserEntity> { wotRank(lookup(it.pubkey)) ?: Int.MIN_VALUE }
            .thenByDescending { wotVerifiedFollowers(lookup(it.pubkey)) ?: Long.MIN_VALUE }
            .thenBy { it.displayName?.lowercase() ?: it.name?.lowercase() ?: it.pubkey },
    )

internal fun sortPeopleByWotFollowers(
    users: List<UserEntity>,
    lookup: (String) -> WotLookup?,
): List<UserEntity> =
    users.sortedWith(
        compareByDescending<UserEntity> { wotVerifiedFollowers(lookup(it.pubkey)) ?: Long.MIN_VALUE }
            .thenByDescending { wotRank(lookup(it.pubkey)) ?: Int.MIN_VALUE }
            .thenBy { it.displayName?.lowercase() ?: it.name?.lowercase() ?: it.pubkey },
    )

internal fun sortMentionsByWotRank(
    users: List<UserEntity>,
    lookup: (String) -> WotLookup?,
): List<UserEntity> =
    users.sortedWith(
        compareByDescending<UserEntity> { wotRank(lookup(it.pubkey)) ?: Int.MIN_VALUE }
            .thenBy { it.displayName?.lowercase() ?: it.name?.lowercase() ?: it.pubkey },
    )

internal fun wotLookupSnapshot(
    pubkeys: Collection<String>,
    lookup: (String) -> WotLookup,
): Map<String, WotLookup> =
    pubkeys.mapNotNull(::normalizeWotPubkey)
        .distinct()
        .associateWith(lookup)

internal fun wotSubjectsForFeedRows(
    rows: Collection<FeedRow>,
    modelProvider: ((String) -> EventModel?)? = null,
): Set<String> {
    val subjects = LinkedHashSet<String>()
    fun addModelSubjects(model: EventModel) {
        normalizeWotPubkey(model.pubkey)?.let(subjects::add)
        normalizeWotPubkey(model.sourcePubkey)?.let(subjects::add)
        normalizeWotPubkey(model.repost?.targetAuthorPubkey)?.let(subjects::add)
        for (segment in model.segments) {
            when (segment) {
                is Segment.QuoteEvent -> {
                    normalizeWotPubkey(segment.author)?.let(subjects::add)
                    modelProvider?.invoke(segment.eventId)?.let { quoted ->
                        normalizeWotPubkey(quoted.pubkey)?.let(subjects::add)
                        normalizeWotPubkey(quoted.sourcePubkey)?.let(subjects::add)
                        normalizeWotPubkey(quoted.repost?.targetAuthorPubkey)?.let(subjects::add)
                    }
                }
                is Segment.QuoteAddress -> normalizeWotPubkey(segment.author)?.let(subjects::add)
                else -> Unit
            }
        }
    }
    for (row in rows) {
        normalizeWotPubkey(row.pubkey)?.let(subjects::add)
        val replyToId = row.replyToId
        if (!replyToId.isNullOrBlank()) {
            modelProvider?.invoke(replyToId)?.let(::addModelSubjects)
        }
        val rootId = row.rootId
        if (!rootId.isNullOrBlank() && rootId != replyToId) {
            modelProvider?.invoke(rootId)?.let(::addModelSubjects)
        }
        val model = modelProvider?.invoke(row.id) ?: runCatching { row.toEventModel() }.getOrNull()
        if (model != null) addModelSubjects(model)
    }
    return subjects
}
