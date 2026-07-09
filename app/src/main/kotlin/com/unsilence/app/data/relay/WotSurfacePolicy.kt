package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.memory.toEventModel
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.Segment

private const val DISTANT_HOPS_THRESHOLD = 5
private const val IMPERSONATION_LOW_RANK_THRESHOLD = 10
private const val PROTECTED_PROFILE_RANK_THRESHOLD = 40

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

data class ProtectedProfile(
    val pubkey: String,
    val normalizedName: String,
    val displayLabel: String,
)

data class ImpersonationRisk(
    val protectedProfile: ProtectedProfile,
)

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
    query: String,
    lookup: (String) -> WotLookup?,
): List<UserEntity> =
    users.sortedWith(
        compareByDescending<UserEntity> { identitySearchMatchTier(it, query) }
            .thenByDescending { wotRank(lookup(it.pubkey)) ?: Int.MIN_VALUE }
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

fun identitySearchMatchTier(user: UserEntity, query: String): Int {
    val q = query.trim().lowercase()
    if (q.isBlank()) return -1
    return user.identitySearchFields()
        .mapNotNull { field ->
            val lower = field.lowercase()
            when {
                lower.startsWith(q) || lower.wordBoundaryContains(q) -> 2
                lower.contains(q) -> 1
                else -> null
            }
        }
        .maxOrNull() ?: -1
}

fun protectedProfileFor(user: UserEntity): ProtectedProfile? {
    val label = user.displayName?.takeIf { it.isNotBlank() }
        ?: user.name?.takeIf { it.isNotBlank() }
        ?: user.nip05?.substringBefore('@')?.takeIf { it.isNotBlank() }
        ?: return null
    val normalizedName = normalizeImpersonationName(label)
    if (normalizedName.isBlank()) return null
    return ProtectedProfile(
        pubkey = user.pubkey,
        normalizedName = normalizedName,
        displayLabel = label,
    )
}

fun detectImpersonationRisk(
    candidate: UserEntity,
    lookup: WotLookup?,
    protectedProfiles: List<ProtectedProfile>,
): ImpersonationRisk? {
    if (lookup == null || lookup == WotLookup.Pending) return null
    val rank = (lookup as? WotLookup.Scored)?.assertion?.rank
    if (rank != null && rank >= IMPERSONATION_LOW_RANK_THRESHOLD) return null

    val candidatePubkey = normalizeWotPubkey(candidate.pubkey) ?: return null
    if (protectedProfiles.any { normalizeWotPubkey(it.pubkey) == candidatePubkey }) return null

    val candidateNames = candidate.identitySearchFields()
        .map(::normalizeImpersonationName)
        .filter { it.isNotBlank() }
        .distinct()
    if (candidateNames.isEmpty()) return null

    return protectedProfiles.firstOrNull { protected ->
        normalizeWotPubkey(protected.pubkey) != candidatePubkey &&
            candidateNames.any { namesMatchForImpersonation(it, protected.normalizedName) }
    }?.let(::ImpersonationRisk)
}

fun isProtectedWotLookup(lookup: WotLookup?): Boolean =
    (lookup as? WotLookup.Scored)?.assertion?.rank?.let { it >= PROTECTED_PROFILE_RANK_THRESHOLD } == true

private fun UserEntity.identitySearchFields(): List<String> =
    listOfNotNull(
        name?.takeIf { it.isNotBlank() },
        displayName?.takeIf { it.isNotBlank() },
        nip05?.substringBefore('@')?.takeIf { it.isNotBlank() },
    )

private fun String.wordBoundaryContains(query: String): Boolean {
    var index = indexOf(query)
    while (index >= 0) {
        if (index == 0 || !this[index - 1].isLetterOrDigit()) return true
        index = indexOf(query, startIndex = index + 1)
    }
    return false
}

/**
 * V1 detector intentionally normalizes only ASCII letters/digits.
 *
 * Unicode homoglyph/lookalike detection is out of scope for this first pass; if a
 * name contains non-ASCII letters or digits, the detector leaves it unflagged
 * rather than guessing at script-equivalence.
 */
internal fun normalizeImpersonationName(value: String): String {
    var hasNonAsciiAlphanumeric = false
    val normalized = buildString {
        value.lowercase().forEach { char ->
            when {
                char in 'a'..'z' || char in '0'..'9' -> append(char)
                char.isLetterOrDigit() -> hasNonAsciiAlphanumeric = true
            }
        }
    }
    return if (hasNonAsciiAlphanumeric) "" else normalized
}

private fun namesMatchForImpersonation(candidate: String, protected: String): Boolean =
    candidate == protected || editDistanceAtMostOne(candidate, protected)

internal fun editDistanceAtMostOne(a: String, b: String): Boolean {
    if (a == b) return true
    val delta = a.length - b.length
    if (delta < -1 || delta > 1) return false

    var i = 0
    var j = 0
    var edits = 0
    while (i < a.length && j < b.length) {
        if (a[i] == b[j]) {
            i++
            j++
            continue
        }
        edits++
        if (edits > 1) return false
        when {
            a.length > b.length -> i++
            a.length < b.length -> j++
            else -> {
                i++
                j++
            }
        }
    }
    if (i < a.length || j < b.length) edits++
    return edits <= 1
}

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
