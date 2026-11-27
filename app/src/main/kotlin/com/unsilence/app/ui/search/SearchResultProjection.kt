package com.unsilence.app.ui.search

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.relay.ImpersonationRisk
import com.unsilence.app.data.relay.ProtectedProfile
import com.unsilence.app.data.relay.WotScoreFilter
import com.unsilence.app.data.relay.detectImpersonationRisk
import com.unsilence.app.data.relay.sortPeopleForSearch
import com.unsilence.app.data.relay.wotSubjectsForFeedRows
import com.unsilence.app.ui.feed.FeedModerationCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private const val SEARCH_PEOPLE_LIMIT = 50

internal data class SearchResultsBundle(
    val localNotes: List<FeedRow>,
    val tagNotes: List<FeedRow>,
    val relayNotes: List<FeedRow>,
    val people: List<UserEntity>,
    val muteList: MuteList? = null,
    val hashtagCap: Int? = null,
    val followedPubkeys: Set<String>? = null,
    val scoreFilter: WotScoreFilter = WotScoreFilter(),
)

internal data class SearchResultProjection(
    val notes: List<FeedRow>,
    val tags: List<FeedRow>,
    val people: List<UserEntity>,
    val peopleMinimum: Int?,
    val impersonationRisks: Map<String, ImpersonationRisk>,
    val wotSubjects: Set<String>,
)

/**
 * One background projection, with only the latest pending input/output retained.
 * Cached models enrich trust subjects; a cache miss must never tokenize a whole
 * article here. Content/quote discovery belongs to the bounded card warm window
 * and the existing onWotSubjectsVisible callbacks, not every result invalidation.
 */
internal fun Flow<SearchResultsBundle>.projectSearchResults(
    query: String,
    explicitHashtag: Boolean,
    eventProvider: (String) -> NostrEvent?,
    cachedModelProvider: (String) -> EventModel?,
    wotLookup: (String) -> WotLookup,
    protectedProfiles: () -> List<ProtectedProfile>,
): Flow<SearchResultProjection> = flow {
    // One bounded weak verdict cache per collection/query, released on cover,
    // query replacement or account teardown. Never retain normalized bodies.
    val moderation = FeedModerationCache(maxEntries = 1_000)
    // Bound new score requests per query even if each completed batch returns no matches.
    val requestedPeople = HashSet<String>()
    emitAll(this@projectSearchResults.conflate().map { results ->
        val verdicts = moderation.forPolicy(results.muteList, results.hashtagCap)
        val localNotes = filterSearchNoteRows(results.localNotes, results.muteList, results.hashtagCap, eventProvider, verdicts)
        val relayNotes = filterSearchNoteRows(results.relayNotes, results.muteList, results.hashtagCap, eventProvider, verdicts)
        val notes = (localNotes + relayNotes).distinctBy { it.id }.sortedByDescending { it.createdAt }
        val localTags = filterSearchNoteRows(results.tagNotes, results.muteList, results.hashtagCap, eventProvider, verdicts)
        val tags = (localTags + if (explicitHashtag) relayNotes else emptyList())
            .distinctBy { it.id }.sortedByDescending { it.createdAt }
        val candidates = filterSearchPeople(results.people, results.muteList)
        val policy = results.scoreFilter
        val scores = if (policy.enabled) candidates.associate { it.pubkey to wotLookup(it.pubkey) } else emptyMap()
        val lookup = if (policy.enabled) scores::getValue else wotLookup
        val ranked = sortPeopleForSearch(
            users = candidates,
            query = query,
            followedPubkeys = results.followedPubkeys,
            lookup = lookup,
            limit = if (policy.enabled) candidates.size else SEARCH_PEOPLE_LIMIT,
        )
        val people = if (policy.enabled) ranked.asSequence().filter { policy.admits(scores[it.pubkey]) }
            .take(SEARCH_PEOPLE_LIMIT).toList() else ranked
        val protected = if (people.isEmpty()) emptyList() else protectedProfiles()
        val risks = people.mapNotNull { user ->
            detectImpersonationRisk(user, lookup(user.pubkey), protected)?.let { user.pubkey to it }
        }.toMap()
        val subjects = buildSet {
            if (policy.enabled) {
                for (person in ranked) {
                    val key = person.pubkey
                    val score = scores.getValue(key)
                    if (score != WotLookup.Pending && !(score is WotLookup.Scored && score.cached)) continue
                    if (key in requestedPeople || requestedPeople.size < SEARCH_PEOPLE_LIMIT) {
                        requestedPeople.add(key)
                        add(key)
                    }
                }
            }
            people.mapTo(this, UserEntity::pubkey)
            addAll(wotSubjectsForFeedRows(notes, cachedModelProvider = cachedModelProvider))
            addAll(wotSubjectsForFeedRows(tags, cachedModelProvider = cachedModelProvider))
        }
        SearchResultProjection(notes, tags, people, policy.takeIf { it.enabled }?.minimum, risks, subjects)
    })
}.flowOn(Dispatchers.Default).buffer(Channel.CONFLATED)
